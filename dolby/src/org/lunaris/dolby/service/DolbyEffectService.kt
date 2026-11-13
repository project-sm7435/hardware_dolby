/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.service

import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.content.SharedPreferences
import android.os.IBinder
import android.content.ComponentName
import android.service.quicksettings.TileService
import android.util.Log
import org.lunaris.dolby.tile.DolbyTileService
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.data.DeviceStateManager
import org.lunaris.dolby.data.DolbyRepository

class DolbyEffectService : Service() {

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val dolbyPrefs: SharedPreferences by lazy {
        getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
    }
    private val isDeviceStateMemoryEnabled: Boolean
        get() = dolbyPrefs.getBoolean(DolbyConstants.PREF_DEVICE_STATE_MEMORY, false)
    private val handler = Handler()
    private var repositoryOrNull: DolbyRepository? = null

    private val repository: DolbyRepository
        get() = repositoryOrNull ?: DolbyRepository.get(this).also { repositoryOrNull = it }
    private lateinit var deviceStateManager: DeviceStateManager
    private var previousActiveDevice: AudioDeviceInfo? = null
    private var retryAttempt = 0
    private val retryRunnable = Runnable { applyStateWithRetry() }

    /**
     * Applies the saved master switch/profile and keeps retrying with a growing delay until the
     * effect reports the saved state. Fixes Dolby being off after reboot.
     */
    private fun applyStateWithRetry() {
        handler.removeCallbacks(retryRunnable)
        val ok = try {
            repository.applySavedState()
        } catch (e: Exception) {
            Log.w(TAG, "applySavedState threw", e)
            false
        }
        Log.i(TAG, "applySavedState ok=$ok attempt=$retryAttempt")
        if (ok) {
            retryAttempt = 0
            try {
                val activeSessions = resolveActiveSessionIds(audioManager.activePlaybackConfigurations)
                if (activeSessions.isNotEmpty()) {
                    repository.syncPlaybackSessions(activeSessions)
                }
            } catch (_: Exception) {
            }
            try {
                TileService.requestListeningState(
                    this,
                    ComponentName(this, DolbyTileService::class.java)
                )
            } catch (_: Exception) {
            }
        } else if (retryAttempt < RETRY_DELAYS_MS.size) {
            handler.postDelayed(retryRunnable, RETRY_DELAYS_MS[retryAttempt++])
        }
    }

    private var activeDeviceKey: String? = null
    private var isRestoringDeviceSnapshot = false

    private val snapshotSaveRunnable = Runnable {
        if (!isDeviceStateMemoryEnabled || isRestoringDeviceSnapshot) return@Runnable
        val key = activeDeviceKey ?: getCurrentOutputDevice()?.let { deviceStateManager.deviceKey(it) } ?: return@Runnable
        try {
            deviceStateManager.saveSnapshot(key, repository)
        } catch (e: Exception) {
            Log.w(TAG, "Failed auto-saving device snapshot for $key", e)
        }
    }

    private fun scheduleSnapshotSave() {
        if (!isDeviceStateMemoryEnabled || isRestoringDeviceSnapshot) return
        handler.removeCallbacks(snapshotSaveRunnable)
        handler.postDelayed(snapshotSaveRunnable, 250L)
    }

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DolbyConstants.PREF_DEVICE_STATE_MEMORY) {
            if (isDeviceStateMemoryEnabled) {
                val dev = getCurrentOutputDevice()
                if (dev != null) {
                    previousActiveDevice = dev
                    val devKey = deviceStateManager.deviceKey(dev)
                    activeDeviceKey = devKey
                    deviceStateManager.saveSnapshot(devKey, repository)
                }
            }
        } else if (isDeviceStateMemoryEnabled && !isRestoringDeviceSnapshot) {
            scheduleSnapshotSave()
        }
    }

    // The route is not settled when the callback fires, so wait a moment and read it then.
    private val deviceChangeRunnable = Runnable { handleDeviceChange() }

    private fun scheduleDeviceChange() {
        handler.removeCallbacks(deviceChangeRunnable)
        handler.postDelayed(deviceChangeRunnable, DEVICE_CHANGE_DEBOUNCE_MS)
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            Log.d(TAG, "Devices added: ${addedDevices.map { it.debugString() }}")
            scheduleDeviceChange()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            Log.d(TAG, "Devices removed: ${removedDevices.map { it.debugString() }}")
            scheduleDeviceChange()
        }
    }

    private var wasPlaybackActive = false
    private val trackedSessions = LinkedHashSet<Int>()
    private val trackedPiids = LinkedHashSet<Int>()
    private var isSessionReceiverRegistered = false

    private val sessionBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
            if (sessionId <= 0) return
            when (action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    trackedSessions.add(sessionId)
                    repository.syncPlaybackSessions(trackedSessions.toSet())
                    schedulePlaybackRefresh()
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    trackedSessions.remove(sessionId)
                    repository.syncPlaybackSessions(trackedSessions.toSet())
                }
            }
        }
    }

    private val playbackRefreshRunnable = Runnable {
        try {
            val sessions = resolveActiveSessionIds(audioManager.activePlaybackConfigurations)
            repository.refreshOnPlayback(sessions)
        } catch (e: Exception) {
            Log.w(TAG, "refreshOnPlayback failed", e)
        }
    }

    private fun schedulePlaybackRefresh() {
        handler.removeCallbacks(playbackRefreshRunnable)
        handler.postDelayed(playbackRefreshRunnable, PLAYBACK_REFRESH_DELAY_MS)
    }

    private fun resolveActiveSessionIds(
        configs: List<AudioPlaybackConfiguration>?,
        includeTracked: Boolean = true
    ): Set<Int> {
        val result = LinkedHashSet<Int>(if (includeTracked) trackedSessions else emptySet())
        if (configs == null) return result
        try {
            val cls = AudioPlaybackConfiguration::class.java
            val isActiveMethod = cls.getMethod("isActive")
            val getSessionIdMethod = cls.getMethod("getSessionId")
            for (cfg in configs) {
                if (isActiveMethod.invoke(cfg) as? Boolean != true) continue
                val sid = getSessionIdMethod.invoke(cfg) as? Int ?: continue
                if (sid > 0) result.add(sid)
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun resolveActivePiids(configs: List<AudioPlaybackConfiguration>?): Set<Int> {
        val result = LinkedHashSet<Int>()
        if (configs == null) return result
        try {
            val cls = AudioPlaybackConfiguration::class.java
            val isActiveMethod = cls.getMethod("isActive")
            val getPiidMethod = cls.getMethod("getPlayerInterfaceId")
            for (cfg in configs) {
                if (isActiveMethod.invoke(cfg) as? Boolean != true) continue
                val piid = getPiidMethod.invoke(cfg) as? Int ?: continue
                if (piid != 0) result.add(piid)
            }
        } catch (_: Exception) {
        }
        return result
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val isActive = configs?.any { it.isActive } == true
            // Only what is really playing, so closed sessions leave trackedSessions.
            val activeSessions = resolveActiveSessionIds(configs, includeTracked = false)
            val activePiids = resolveActivePiids(configs)
            val newSessionStarted = (activeSessions - trackedSessions).isNotEmpty()
            val newPlayerStarted = (activePiids - trackedPiids).isNotEmpty()

            trackedSessions.clear()
            trackedSessions.addAll(activeSessions)
            trackedPiids.clear()
            trackedPiids.addAll(activePiids)

            if (isActive) {
                if (!wasPlaybackActive) {
                    try {
                        repository.ensureLiveState()
                    } catch (e: Exception) {
                        Log.w(TAG, "ensureLiveState on playback failed", e)
                    }
                }
                if (newSessionStarted || newPlayerStarted || !wasPlaybackActive) {
                    repository.syncPlaybackSessions(activeSessions)
                    schedulePlaybackRefresh()
                }
            } else if (wasPlaybackActive) {
                repository.syncPlaybackSessions(emptySet())
            }
            wasPlaybackActive = isActive
        }
    }

    override fun onCreate() {
        super.onCreate()
        deviceStateManager = DeviceStateManager(this)
        dolbyPrefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        try {
            val currentDevice = getCurrentOutputDevice()
            if (currentDevice != null) {
                previousActiveDevice = currentDevice
                val key = deviceStateManager.deviceKey(currentDevice)
                activeDeviceKey = key
                if (isDeviceStateMemoryEnabled) {
                    isRestoringDeviceSnapshot = true
                    val restored = try {
                        deviceStateManager.restoreSnapshot(key, repository)
                    } finally {
                        isRestoringDeviceSnapshot = false
                    }
                    if (!restored) {
                        applyStateWithRetry()
                        deviceStateManager.saveSnapshot(key, repository)
                    } else {
                        // audioserver/DAP HAL may still be starting: check again shortly.
                        handler.postDelayed(retryRunnable, 2_000L)
                    }
                } else {
                    applyStateWithRetry()
                }
            } else {
                applyStateWithRetry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Initial Dolby setup failed, will retry", e)
            retryAttempt = 0
            handler.postDelayed(retryRunnable, RETRY_DELAYS_MS[0])
        }

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
        if (!isSessionReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            }
            registerReceiver(sessionBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
            isSessionReceiverRegistered = true
        }
        Log.d(TAG, "Dolby effect service created")
    }

    private fun handleDeviceChange() {
        try {
            handleDeviceChangeInner()
        } catch (e: Exception) {
            Log.w(TAG, "Device change handling failed, will retry", e)
            retryAttempt = 0
            handler.postDelayed(retryRunnable, RETRY_DELAYS_MS[0])
        }
    }

    private fun handleDeviceChangeInner() {
        val newDevice = getCurrentOutputDevice()
        val oldDevice = previousActiveDevice
        val newKey = newDevice?.let { deviceStateManager.deviceKey(it) }
        val oldKey = activeDeviceKey ?: oldDevice?.let { deviceStateManager.deviceKey(it) }

        if ((oldDevice != null && newDevice != null && oldDevice.id == newDevice.id) ||
            (newKey != null && newKey == oldKey)
        ) {
            // Same output as before (a mic or another device came or went): nothing to save or
            // restore, and no reason to rebind the effects in the middle of playback.
            repository.updateSpeakerState()
            previousActiveDevice = newDevice ?: oldDevice
            return
        }

        if (oldKey != null && isDeviceStateMemoryEnabled && !isRestoringDeviceSnapshot) {
            handler.removeCallbacks(snapshotSaveRunnable)
            Log.d(TAG, "Saving snapshot for previous device: $oldKey")
            deviceStateManager.saveSnapshot(oldKey, repository)
        }

        if (newDevice != null && newKey != null) {
            activeDeviceKey = newKey
            previousActiveDevice = newDevice
            if (isDeviceStateMemoryEnabled) {
                Log.d(TAG, "Restoring snapshot for new device: $newKey")
                isRestoringDeviceSnapshot = true
                val restored = try {
                    deviceStateManager.restoreSnapshot(newKey, repository)
                } finally {
                    isRestoringDeviceSnapshot = false
                }
                if (!restored) {
                    Log.d(TAG, "First time device $newKey, saving current state as baseline")
                    repository.applySavedState()
                    deviceStateManager.saveSnapshot(newKey, repository)
                }
            } else {
                Log.d(TAG, "Device state memory disabled, applying saved state")
                repository.applySavedState()
            }
        } else {
            activeDeviceKey = null
            previousActiveDevice = null
            repository.updateSpeakerState()
            repository.applySavedState()
        }
    }

    private fun getCurrentOutputDevice(): AudioDeviceInfo? {
        val routedDevice = try {
            audioManager
                .getDevicesForAttributes(ATTRIBUTES_MEDIA)
                .firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get active media route", e)
            null
        } ?: return null

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val routedAddress = routedDevice.address.orEmpty()

        return outputs.firstOrNull { device ->
            device.isSink &&
                device.type == routedDevice.type &&
                (routedAddress.isEmpty() || device.address == routedAddress)
        } ?: outputs.firstOrNull { device ->
            device.isSink && device.type == routedDevice.type
        }.also { device ->
            if (device == null) {
                Log.w(
                    TAG,
                    "Unable to map active media route: " +
                        "type=${routedDevice.type}, address=${routedDevice.address}"
                )
            }
        }
    }

    private fun AudioDeviceInfo.debugString(): String =
        "name=$productName,type=$type,id=$id,address=$address,isSink=$isSink"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        retryAttempt = 0
        applyStateWithRetry()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        dolbyPrefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        val repo = repositoryOrNull
        if (repo != null && isDeviceStateMemoryEnabled && !isRestoringDeviceSnapshot) {
            val key = activeDeviceKey ?: previousActiveDevice?.let { deviceStateManager.deviceKey(it) }
            if (key != null) {
                deviceStateManager.saveSnapshot(key, repo)
            }
        }
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        if (isSessionReceiverRegistered) {
            try {
                unregisterReceiver(sessionBroadcastReceiver)
            } catch (_: Exception) {
            }
            isSessionReceiverRegistered = false
        }
        handler.removeCallbacksAndMessages(null)
        try {
            repo?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing repository", e)
        }
        repositoryOrNull = null
        Log.d(TAG, "Dolby effect service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DolbyEffectService"
        private const val PLAYBACK_REFRESH_DELAY_MS = 150L
        private const val DEVICE_CHANGE_DEBOUNCE_MS = 500L
        private val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000, 60_000, 120_000)

        private val ATTRIBUTES_MEDIA = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        fun start(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.stopService(intent)
        }
    }
}
