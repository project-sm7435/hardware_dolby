/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.data.DeviceStateManager
import org.lunaris.dolby.data.DolbyRepository
import org.lunaris.dolby.tile.DolbyTileService
import java.lang.reflect.Method

class DolbyEffectService : Service() {

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val dolbyPrefs: SharedPreferences by lazy {
        getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
    }
    private val isDeviceStateMemoryEnabled: Boolean
        get() = dolbyPrefs.getBoolean(DolbyConstants.PREF_DEVICE_STATE_MEMORY, false)

    private lateinit var workerThread: HandlerThread
    private lateinit var handler: Handler
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
        DolbyConstants.dlog(TAG, "applySavedState ok=$ok attempt=$retryAttempt")
        if (ok) {
            retryAttempt = 0
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
                handler.post {
                    val dev = getCurrentOutputDevice()
                    if (dev != null) {
                        previousActiveDevice = dev
                        val devKey = deviceStateManager.deviceKey(dev)
                        activeDeviceKey = devKey
                        deviceStateManager.saveSnapshot(devKey, repository)
                    }
                }
            }
        } else if (isDeviceStateMemoryEnabled && !isRestoringDeviceSnapshot && key != "device_snapshot_restored") {
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
            DolbyConstants.dlog(TAG, "Devices added: ${addedDevices.size}")
            scheduleDeviceChange()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            DolbyConstants.dlog(TAG, "Devices removed: ${removedDevices.size}")
            scheduleDeviceChange()
        }
    }

    private var wasPlaybackActive = false
    private var wasRestricted = false
    private var pendingForceRestore = false
    private var lastPlaybackStoppedAtMs = 0L

    private val playbackRefreshRunnable = Runnable {
        try {
            val configs = audioManager.activePlaybackConfigurations
            val restricted = hasRestrictedPlayback(configs)
            val forceRestore = pendingForceRestore
            pendingForceRestore = false
            repository.refreshOnPlayback(emptySet(), restricted, forceRestore)
        } catch (e: Exception) {
            Log.w(TAG, "refreshOnPlayback failed", e)
        }
    }

    private fun schedulePlaybackRefresh(forceRestore: Boolean = false) {
        if (forceRestore) {
            pendingForceRestore = true
        }
        handler.removeCallbacks(playbackRefreshRunnable)
        handler.postDelayed(playbackRefreshRunnable, PLAYBACK_REFRESH_DELAY_MS)
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val isActive = configs?.any { isConfigActive(it) } == true
            val restricted = hasRestrictedPlayback(configs)
            val now = SystemClock.elapsedRealtime()

            if (restricted) {
                schedulePlaybackRefresh(forceRestore = !wasRestricted)
            } else if (isActive) {
                if (!wasPlaybackActive) {
                    val idleDurationMs = if (lastPlaybackStoppedAtMs == 0L) Long.MAX_VALUE else (now - lastPlaybackStoppedAtMs)
                    val shouldForce = idleDurationMs >= STANDBY_IDLE_THRESHOLD_MS || lastPlaybackStoppedAtMs > 0L
                    if (shouldForce) {
                        pendingForceRestore = true
                        try {
                            repository.ensureLiveState()
                        } catch (e: Exception) {
                            Log.w(TAG, "ensureLiveState on playback failed", e)
                        }
                    }
                    schedulePlaybackRefresh(forceRestore = shouldForce)
                } else {
                    schedulePlaybackRefresh(forceRestore = true)
                }
            } else if (wasPlaybackActive) {
                lastPlaybackStoppedAtMs = now
            }
            wasPlaybackActive = isActive
            wasRestricted = restricted
        }
    }

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("DolbyEffectWorker", Process.THREAD_PRIORITY_AUDIO).apply { start() }
        handler = Handler(workerThread.looper)
        deviceStateManager = DeviceStateManager(this)
        dolbyPrefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        val userStart = consumePendingUserStart()

        handler.post {
            try {
                val currentDevice = getCurrentOutputDevice()
                if (currentDevice != null) {
                    previousActiveDevice = currentDevice
                    val key = deviceStateManager.deviceKey(currentDevice)
                    activeDeviceKey = key
                    if (isDeviceStateMemoryEnabled) {
                        isRestoringDeviceSnapshot = true
                        val restored = try {
                            deviceStateManager.restoreSnapshot(
                                key,
                                repository,
                                restoreEnabled = !userStart
                            )
                        } finally {
                            isRestoringDeviceSnapshot = false
                        }
                        if (!restored) {
                            applyStateWithRetry()
                            deviceStateManager.saveSnapshot(key, repository)
                        } else {
                            if (userStart) {
                                deviceStateManager.saveSnapshot(key, repository)
                            }
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
        }

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
        DolbyConstants.dlog(TAG, "Dolby effect service created")
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
            repository.updateSpeakerState()
            previousActiveDevice = newDevice ?: oldDevice
            return
        }

        if (oldKey != null && isDeviceStateMemoryEnabled && !isRestoringDeviceSnapshot) {
            handler.removeCallbacks(snapshotSaveRunnable)
            DolbyConstants.dlog(TAG, "Saving snapshot for previous device: $oldKey")
            deviceStateManager.saveSnapshot(oldKey, repository)
        }

        if (newDevice != null && newKey != null) {
            activeDeviceKey = newKey
            previousActiveDevice = newDevice
            if (isDeviceStateMemoryEnabled) {
                DolbyConstants.dlog(TAG, "Restoring snapshot for new device: $newKey")
                isRestoringDeviceSnapshot = true
                val restored = try {
                    deviceStateManager.restoreSnapshot(newKey, repository)
                } finally {
                    isRestoringDeviceSnapshot = false
                }
                if (!restored) {
                    repository.applySavedState()
                    deviceStateManager.saveSnapshot(newKey, repository)
                }
            } else {
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
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromUser = intent?.getBooleanExtra(EXTRA_FROM_USER, false) == true || consumeSkipNextStartApply()
        if (!fromUser) {
            handler.post {
                retryAttempt = 0
                applyStateWithRetry()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        dolbyPrefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        handler.removeCallbacksAndMessages(null)
        val repo = repositoryOrNull
        if (repo != null && isDeviceStateMemoryEnabled && !isRestoringDeviceSnapshot) {
            val key = activeDeviceKey ?: previousActiveDevice?.let { deviceStateManager.deviceKey(it) }
            if (key != null) {
                try {
                    deviceStateManager.saveSnapshot(key, repo)
                } catch (_: Exception) {
                }
            }
        }
        try {
            repo?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing repository", e)
        }
        repositoryOrNull = null
        workerThread.quitSafely()
        DolbyConstants.dlog(TAG, "Dolby effect service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DolbyEffectService"
        private const val PLAYBACK_REFRESH_DELAY_MS = 150L
        private const val STANDBY_IDLE_THRESHOLD_MS = 2_500L
        private const val DEVICE_CHANGE_DEBOUNCE_MS = 500L
        private val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000, 60_000, 120_000)

        private val ATTRIBUTES_MEDIA = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        // Cached reflection handles (resolved once per process instead of every playback callback)
        private val isActiveMethod: Method? by lazy {
            runCatching { AudioPlaybackConfiguration::class.java.getMethod("isActive") }.getOrNull()
        }
        private val getAudioAttributesMethod: Method? by lazy {
            runCatching { AudioPlaybackConfiguration::class.java.getMethod("getAudioAttributes") }.getOrNull()
        }
        private val getFormatMethod: Method? by lazy {
            runCatching { AudioPlaybackConfiguration::class.java.getMethod("getFormat") }.getOrNull()
        }

        private fun isConfigActive(cfg: AudioPlaybackConfiguration): Boolean =
            try {
                isActiveMethod?.invoke(cfg) as? Boolean == true
            } catch (_: Exception) {
                false
            }

        @Volatile
        private var pendingUserStart = false

        @Volatile
        private var skipNextStartApply = false

        private const val EXTRA_FROM_USER = "extra_from_user"

        private fun consumePendingUserStart(): Boolean {
            val v = pendingUserStart
            pendingUserStart = false
            return v
        }

        private fun consumeSkipNextStartApply(): Boolean {
            val v = skipNextStartApply
            skipNextStartApply = false
            return v
        }

        fun start(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.startService(intent)
        }

        fun startFromUser(context: Context) {
            pendingUserStart = true
            skipNextStartApply = true
            val intent = Intent(context, DolbyEffectService::class.java)
                .putExtra(EXTRA_FROM_USER, true)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DolbyEffectService::class.java)
            context.stopService(intent)
        }

        fun hasRestrictedPlayback(configs: List<AudioPlaybackConfiguration>?): Boolean {
            if (configs == null) return false
            try {
                for (cfg in configs) {
                    if (!isConfigActive(cfg)) continue
                    val attr = getAudioAttributesMethod?.invoke(cfg) as? AudioAttributes
                    if (attr != null && attr.contentType == AudioAttributes.CONTENT_TYPE_MOVIE) {
                        return true
                    }
                    val format = getFormatMethod?.invoke(cfg) as? AudioFormat
                    if (format != null && format.channelCount > 2) {
                        return true
                    }
                }
            } catch (_: Exception) {
            }
            return false
        }
    }
}