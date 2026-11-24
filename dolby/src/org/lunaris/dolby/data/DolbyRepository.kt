/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.DolbyConstants.DsParam
import org.lunaris.dolby.R
import org.lunaris.dolby.audio.DolbyAudioEffect
import org.lunaris.dolby.domain.models.*
import org.lunaris.dolby.service.DolbyEffectService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DolbyRepository private constructor(appContext: Context) : AutoCloseable {

    private val context: Context = appContext.applicationContext

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val dolbyEffect: DolbyAudioEffect get() = SharedDolbyEffect.get { createDolbyEffect() }

    @Synchronized
    private fun recreateDolbyEffect() {
        invalidateDapCache()
        SharedDolbyEffect.drop()
        SharedDolbyEffect.get { createDolbyEffect() }
    }
    
    private val defaultPrefs = context.getSharedPreferences("dolby_prefs", Context.MODE_PRIVATE)
    private val presetsPrefs = context.getSharedPreferences(DolbyConstants.PREF_FILE_PRESETS, Context.MODE_PRIVATE)
    
    private val _isOnSpeaker = MutableStateFlow(checkIsOnSpeaker())
    val isOnSpeaker: StateFlow<Boolean> = _isOnSpeaker.asStateFlow()
    
    private val _currentProfile = MutableStateFlow(defaultPrefs.getString(DolbyConstants.PREF_PROFILE, null)?.toIntOrNull() ?: 0)
    val currentProfile: StateFlow<Int> = _currentProfile.asStateFlow()

    fun notifyStateUpdated() {
        defaultPrefs.edit().putLong("device_state_revision", System.nanoTime()).apply()
    }

    fun notifySnapshotRestored() {
        defaultPrefs.edit().putLong("device_snapshot_restored", System.nanoTime()).apply()
    }

    fun syncMidLevelState(profile: Int, level: Int) {
        val clamped = level.coerceIn(0, 100)
        getProfilePrefs(profile).edit()
            .putInt(DolbyConstants.PREF_MID_LEVEL, clamped)
            .putBoolean(DolbyConstants.PREF_MID, clamped > 0)
            .apply()
    }

    val stereoWideningSupported = context.resources.getBoolean(R.bool.dolby_stereo_widening_supported)
    val volumeLevelerSupported = context.resources.getBoolean(R.bool.dolby_volume_leveler_supported)
    
    private var isReleased = false
    private var lastAppliedRestricted = false
    private var lastAppliedProfile: Int = -1
    private var lastAppliedGeq: IntArray? = null
    private var lastAppliedBassEnhancer: Boolean? = null
    private var lastAppliedHpVirt: Boolean? = null
    private var lastAppliedSpkVirt: Boolean? = null
    private var lastAppliedWidening: Int? = null

    private fun invalidateDapCache() {
        lastAppliedProfile = -1
        lastAppliedGeq = null
        lastAppliedBassEnhancer = null
        lastAppliedHpVirt = null
        lastAppliedSpkVirt = null
        lastAppliedWidening = null
    }
    
    private var cachedPresets: List<EqualizerPreset>? = null
    private val presetCacheLock = Any()

    private fun createDolbyEffect(): DolbyAudioEffect {
        return try {
            DolbyAudioEffect(EFFECT_PRIORITY, audioSession = 0)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Failed to create Dolby effect: ${e.message}")
            throw e
        }
    }

    @Synchronized
    private fun checkEffect() {
        if (isReleased) {
            DolbyConstants.dlog(TAG, "Repository released, skipping effect check")
            return
        }
        
        try {
            if (!dolbyEffect.hasControl()) {
                DolbyConstants.dlog(TAG, "Lost audio effect control, recreating")
                recreateDolbyEffect()
                restoreSavedProfileIfNeeded()
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error checking effect: ${e.message}")
            try {
                recreateDolbyEffect()
            } catch (e2: Exception) {
                DolbyConstants.dlog(TAG, "Could not recreate effect: ${e2.message}")
            }
        }
    }

    private fun readSavedProfile(): Int? {
        return defaultPrefs.getString(DolbyConstants.PREF_PROFILE, null)
            ?.toIntOrNull()
    }

    private fun restoreSavedProfileIfNeeded(forceDapPush: Boolean = true) {
        val savedProfile = readSavedProfile() ?: return
        if (dolbyEffect.profile != savedProfile) {
            dolbyEffect.profile = savedProfile
        }
        applyProfileSettings(savedProfile)
        applyViper(savedProfile, forceRebind = forceDapPush)
    }

    /**
     * Applies non-ViPER profile DAP parameters (IEQ, Dialogue Enhancer, Volume Leveler).
     * GEQ, Bass Enhancer, Virtualizer, and Stereo Widening are owned exclusively by applyViper()
     * so libhwdap.so never experiences an off-then-on parameter double-flip.
     */
    private fun applyProfileSettings(profile: Int) {
        try {
            val prefs = getProfilePrefs(profile)

            if (prefs.contains(DolbyConstants.PREF_IEQ)) {
                val ieqPreset = prefs.getString(DolbyConstants.PREF_IEQ, "0")?.toIntOrNull() ?: 0
                dolbyEffect.setDapParameter(DsParam.IEQ_PRESET, ieqPreset, profile)
            }

            if (prefs.contains(DolbyConstants.PREF_DIALOGUE)) {
                dolbyEffect.setDapParameter(
                    DsParam.DIALOGUE_ENHANCER_ENABLE,
                    prefs.getBoolean(DolbyConstants.PREF_DIALOGUE, false), profile
                )
            }

            if (prefs.contains(DolbyConstants.PREF_DIALOGUE_AMOUNT)) {
                dolbyEffect.setDapParameter(
                    DsParam.DIALOGUE_ENHANCER_AMOUNT,
                    prefs.getInt(DolbyConstants.PREF_DIALOGUE_AMOUNT, 6), profile
                )
            }

            if (volumeLevelerSupported && prefs.contains(DolbyConstants.PREF_VOLUME)) {
                dolbyEffect.setDapParameter(
                    DsParam.VOLUME_LEVELER_ENABLE,
                    prefs.getBoolean(DolbyConstants.PREF_VOLUME, false), profile
                )
            }

            DolbyConstants.dlog(TAG, "Successfully restored saved settings for profile $profile")
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Failed to restore profile settings: ${e.message}")
        }
    }

    fun getSavedProfile(): Int = readSavedProfile() ?: 0

    fun getSavedDolbyEnabled(): Boolean =
        defaultPrefs.getBoolean(DolbyConstants.PREF_ENABLE, false)

    @Synchronized
    fun applySavedState(): Boolean {
        return try {
            checkEffect()
            val enabled = getSavedDolbyEnabled()
            val savedProfile = readSavedProfile() ?: 0
            if (enabled) {
                if (dolbyEffect.profile != savedProfile) {
                    dolbyEffect.profile = savedProfile
                }
                applyProfileSettings(savedProfile)
                applyViper(savedProfile, forceRebind = true)
            } else {
                invalidateDapCache()
            }
            dolbyEffect.dsOn = enabled
            if (dolbyEffect.dsOn != enabled) {
                // Early boot: audioserver/DAP HAL may have restarted after initial handle creation; recreate handle.
                recreateDolbyEffect()
                if (enabled) {
                    dolbyEffect.profile = savedProfile
                    applyProfileSettings(savedProfile)
                    applyViper(savedProfile, forceRebind = true)
                }
                dolbyEffect.dsOn = enabled
            }
            val live = dolbyEffect.dsOn
            DolbyConstants.dlog(TAG, "applySavedState: saved=$enabled live=$live profile=$savedProfile")
            android.util.Log.i(TAG, "applySavedState: saved=$enabled live=$live profile=$savedProfile")
            live == enabled
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "applySavedState failed: ${e.message}")
            android.util.Log.w(TAG, "applySavedState failed: ${e.message}")
            try {
                recreateDolbyEffect()
            } catch (_: Exception) {
            }
            false
        }
    }

    private fun checkIsOnSpeaker(): Boolean {
        return try {
            val device = audioManager.getDevicesForAttributes(ATTRIBUTES_MEDIA)[0]
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error checking speaker state: ${e.message}")
            false
        }
    }

    fun updateSpeakerState() {
        if (!isReleased) {
            _isOnSpeaker.value = checkIsOnSpeaker()
        }
    }

    fun getDolbyEnabled(): Boolean {
        return try {
            dolbyEffect.dsOn
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting Dolby enabled state: ${e.message}")
            false
        }
    }

    @Synchronized
    fun setDolbyEnabled(enabled: Boolean) {
        if (isReleased) return

        defaultPrefs.edit().putBoolean(DolbyConstants.PREF_ENABLE, enabled).apply()
        if (!enabled) {
            invalidateDapCache()
        }
        try {
            checkEffect()
            if (enabled) {
                restoreSavedProfileIfNeeded(forceDapPush = true)
            }
            dolbyEffect.dsOn = enabled
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting Dolby enabled: ${e.message}")
            try {
                recreateDolbyEffect()
                if (enabled) {
                    restoreSavedProfileIfNeeded(forceDapPush = true)
                }
                dolbyEffect.dsOn = enabled
            } catch (e2: Exception) {
                DolbyConstants.dlog(TAG, "Retry setting Dolby enabled failed: ${e2.message}")
            }
        }
        // The switch must really have reached the effect. If not, take a fresh handle and set it once.
        try {
            if (dolbyEffect.dsOn != enabled) {
                DolbyConstants.dlog(TAG, "Dolby switch did not stick, retrying with a new handle")
                recreateDolbyEffect()
                if (enabled) {
                    restoreSavedProfileIfNeeded(forceDapPush = true)
                }
                dolbyEffect.dsOn = enabled
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Verifying Dolby switch failed: ${e.message}")
        }
    }

    /**
     * If the saved switch says on but the live effect is off (for example after a reboot or an
     * audioserver restart), applies the saved state again so the screen never lies about it.
     */
    /**
     * Light re-push of the saved state when a new track/stream starts. Unlike applySavedState()
     * it never drops/rebinds the ViPER handles, so it can run during playback.
     */
    @Synchronized
    fun syncPlaybackSessions(
        @Suppress("UNUSED_PARAMETER") activeSessionIds: Set<Int>,
        @Suppress("UNUSED_PARAMETER") restricted: Boolean = false
    ) {
        // Single-driver mode (libhwdap.so on global session 0): no per-session ViPER handles needed.
    }

    @Synchronized
    fun refreshOnPlayback(
        @Suppress("UNUSED_PARAMETER") activeSessionIds: Set<Int> = emptySet(),
        restricted: Boolean = false,
        forceRestore: Boolean = false
    ) {
        if (isReleased) return
        try {
            checkEffect()
            val enabled = getSavedDolbyEnabled()
            if (!enabled) {
                if (dolbyEffect.dsOn) {
                    dolbyEffect.dsOn = false
                }
                lastAppliedRestricted = false
                invalidateDapCache()
                return
            }
            val effectiveRestricted = restricted ||
                DolbyEffectService.hasRestrictedPlayback(audioManager.activePlaybackConfigurations)
            val savedProfile = readSavedProfile() ?: 0
            val geqDrifted = try {
                val expected = lastAppliedGeq
                expected == null || !dolbyEffect.getDapParameter(DsParam.GEQ_BAND_GAINS, savedProfile).contentEquals(expected)
            } catch (_: Exception) {
                true
            }
            val needsRestore = forceRestore ||
                !dolbyEffect.dsOn ||
                dolbyEffect.profile != savedProfile ||
                effectiveRestricted != lastAppliedRestricted ||
                geqDrifted

            // Normal track-to-track skip while already active & synced: skip re-push to avoid DSP pop.
            // After automatic stop / standby (forceRestore=true or geqDrifted=true): restore full state cleanly.
            if (!needsRestore) return

            lastAppliedRestricted = effectiveRestricted
            if (dolbyEffect.profile != savedProfile) {
                dolbyEffect.profile = savedProfile
            }
            applyProfileSettings(savedProfile)
            if (!effectiveRestricted) {
                applyViper(savedProfile, forceRebind = forceRestore || geqDrifted)
            } else {
                restoreProfilePreset(savedProfile)
                invalidateDapCache()
            }
            if (!dolbyEffect.dsOn) {
                dolbyEffect.dsOn = true
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "refreshOnPlayback failed: ${e.message}")
        }
    }

    @Synchronized
    fun ensureLiveState() {
        if (isReleased) return
        try {
            if (getDolbyEnabled() != getSavedDolbyEnabled()) {
                DolbyConstants.dlog(TAG, "Live Dolby state differs from the saved switch, re-applying")
                applySavedState()
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "ensureLiveState failed: ${e.message}")
        }
    }

    fun setDolbyEnabledFromUser(enabled: Boolean) {
        setDolbyEnabled(enabled)
        if (enabled) {
            DolbyEffectService.startFromUser(context)
        } else {
            DolbyEffectService.stop(context)
        }
    }

    /** Calls [onChange] whenever the master switch changes, from the app or from the QS tile. */
    fun registerEnabledListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        defaultPrefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterEnabledListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        defaultPrefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun getCurrentProfile(): Int {
        if (!getSavedDolbyEnabled()) {
            return readSavedProfile() ?: 0
        }
        return try {
            checkEffect()
            val saved = readSavedProfile()
            if (saved != null && dolbyEffect.profile != saved) {
                dolbyEffect.profile = saved
            }
            dolbyEffect.profile
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting current profile: ${e.message}")
            readSavedProfile() ?: 0
        }
    }

    @Synchronized
    fun setCurrentProfile(profile: Int) {
        if (isReleased) return
        
        try {
            defaultPrefs.edit().putString(DolbyConstants.PREF_PROFILE, profile.toString()).apply()
            val enabled = getSavedDolbyEnabled()
            _currentProfile.value = profile
            if (!enabled) {
                DolbyConstants.dlog(TAG, "Profile saved (Dolby OFF): $profile")
                return
            }
            checkEffect()
            dolbyEffect.profile = profile
            if (dolbyEffect.profile != profile) {
                recreateDolbyEffect()
                dolbyEffect.profile = profile
            }
            applyProfileSettings(profile)
            applyViper(profile, forceRebind = true)
            // Re-assert dsOn after profile & parameters are written so Xiaomi/Qualcomm libhwdap.so
            // commits the selected profile's DSP coefficients immediately on the first click.
            dolbyEffect.dsOn = true
            if (!dolbyEffect.dsOn) {
                recreateDolbyEffect()
                dolbyEffect.profile = profile
                applyProfileSettings(profile)
                applyViper(profile, forceRebind = true)
                dolbyEffect.dsOn = true
            }
            DolbyConstants.dlog(TAG, "Profile set to: $profile")
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting current profile: ${e.message}")
        }
    }

    private fun restoreProfilePreset(profile: Int) {
        try {
            val prefs = getProfilePrefs(profile)
            val savedPresetGains = prefs.getString(DolbyConstants.PREF_PRESET, null)
            
            if (savedPresetGains != null) {
                val gains = savedPresetGains.split(",").mapNotNull { it.toIntOrNull() }.toIntArray()
                if (gains.size == 20) {
                    dolbyEffect.setDapParameter(DsParam.GEQ_BAND_GAINS, gains, profile)
                    DolbyConstants.dlog(TAG, "Restored preset for profile $profile")
                }
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Failed to restore preset for profile $profile: ${e.message}")
        }
    }

    fun verifyProfileSaved(profile: Int): Boolean {
        val prefs = defaultPrefs.getString(DolbyConstants.PREF_PROFILE, "0")?.toIntOrNull()
        val saved = prefs == profile
        DolbyConstants.dlog(TAG, "Profile verification: requested=$profile, saved=$prefs, match=$saved")
        return saved
    }

    private fun getProfilePrefs(profile: Int): SharedPreferences {
        return context.getSharedPreferences("profile_$profile", Context.MODE_PRIVATE)
    }

    fun getBandMode(): BandMode {
        val mode = defaultPrefs.getString(DolbyConstants.PREF_BAND_MODE, "10")
        return when (mode) {
            "10" -> BandMode.TEN_BAND
            "15" -> BandMode.FIFTEEN_BAND
            "20" -> BandMode.TWENTY_BAND
            else -> BandMode.TEN_BAND
        }
    }

    fun setBandMode(mode: BandMode) {
        defaultPrefs.edit().putString(DolbyConstants.PREF_BAND_MODE, mode.value).apply()
    }

    private fun readApplied(prefs: SharedPreferences, key: String): IntArray? {
        val parts = prefs.getString(key, null)
            ?.split(",")?.mapNotNull { it.toIntOrNull() } ?: return null
        return if (parts.size == 20) parts.toIntArray() else null
    }

    private fun replaceEffectDelta(
        prefs: SharedPreferences,
        key: String,
        gains: IntArray,
        legacyOldDelta: IntArray?,
        newDelta: IntArray?,
    ) {
        val applied = readApplied(prefs, key)
        val old = applied ?: legacyOldDelta
        if (old != null) {
            for (i in gains.indices) {
                if (i < old.size) gains[i] = (gains[i] - old[i]).coerceIn(-150, 150)
            }
        }
        if (newDelta != null) {
            val added = IntArray(20)
            for (i in newDelta.indices) {
                if (i >= gains.size || i >= added.size) break
                val updated = (gains[i] + newDelta[i]).coerceIn(-150, 150)
                added[i] = updated - gains[i]
                gains[i] = updated
            }
            prefs.edit().putString(key, added.joinToString(",")).apply()
        } else {
            prefs.edit().remove(key).apply()
        }
    }

    private fun bassDelta(level: Int, curve: Int, cutoff: Int): IntArray? =
        if (level > 0) ViperEffectModel.bassGains(curve, level, DAX_GEQ_FREQUENCIES, cutoff.toDouble()) else null

    private fun clarityDelta(level: Int, mode: Int): IntArray? =
        if (level > 0) ViperEffectModel.clarityGains(mode, level, DAX_GEQ_FREQUENCIES) else null

    /**
     * Reads the user's clean base 20-band GEQ from SharedPreferences (never contaminated by ViPER deltas).
     */
    private fun readUserBaseGeq(profile: Int): IntArray {
        val prefs = getProfilePrefs(profile)
        val saved = prefs.getString(DolbyConstants.PREF_PRESET, null)
        if (saved != null) {
            val gains = saved.split(",").mapNotNull { it.toIntOrNull() }
            if (gains.size == 20) return gains.toIntArray()
        }
        return IntArray(20) { 0 }
    }

    /**
     * Single-driver (libhwdap.so) ViPER effects merge:
     * Computes userBaseGeq + ViPER Bass + Dynamic System + ViPER Clarity + Speaker Correction + 3D Surround
     * via ViperEffectModel.kt and pushes the merged 20-band GEQ + native Dolby DAP parameters directly to libhwdap.so.
     */
    @Synchronized
    private fun syncViperState(profile: Int, forceRebind: Boolean = false) {
        if (isReleased) return
        applyViper(profile, forceRebind)
    }

    @Synchronized
    fun applyViper(profile: Int = readSavedProfile() ?: 0, forceRebind: Boolean = false): Boolean {
        if (isReleased) return true
        if (!getSavedDolbyEnabled()) return true
        return try {
            checkEffect()
            val force = forceRebind || lastAppliedProfile != profile
            if (dolbyEffect.profile != profile) {
                dolbyEffect.profile = profile
            }
            val prefs = getProfilePrefs(profile)
            var bassLevel = prefs.getInt(DolbyConstants.PREF_BASS_LEVEL, 0)
            var curve = prefs.getInt(DolbyConstants.PREF_BASS_CURVE, 0)
            val dynamicLevel = getDynamicLevel(profile)
            if (curve == ViperEffectModel.BASS_DYNAMIC) {
                val editor = prefs.edit()
                if (!prefs.contains(DolbyConstants.PREF_DYNAMIC_LEVEL) && bassLevel > 0) {
                    editor.putInt(DolbyConstants.PREF_DYNAMIC_LEVEL, bassLevel)
                }
                editor.putInt(DolbyConstants.PREF_BASS_CURVE, 0)
                editor.putInt(DolbyConstants.PREF_BASS_LEVEL, 0)
                editor.putBoolean(DolbyConstants.PREF_BASS, false)
                editor.apply()
                curve = 0
                bassLevel = 0
            }
            val bassCutoff = prefs.getInt(DolbyConstants.PREF_BASS_CUTOFF, 60)
            val trebleLevel = prefs.getInt(DolbyConstants.PREF_TREBLE_LEVEL, 0)
            val clarityMode = prefs.getInt(DolbyConstants.PREF_CLARITY_MODE, 0)
            val speakerLevel = prefs.getInt(DolbyConstants.PREF_SPEAKER_CORRECTION, 0)
            val surroundOn = prefs.getBoolean(DolbyConstants.PREF_VIPER_SURROUND, false)
            val surroundWidening = prefs.getInt(DolbyConstants.PREF_VIPER_SURROUND_WIDENING, 50).coerceIn(1, 100)
            val surroundDepth = prefs.getInt(DolbyConstants.PREF_VIPER_SURROUND_DEPTH, 35).coerceIn(0, 100)
            val midLevel = prefs.getInt(DolbyConstants.PREF_MID_LEVEL, 0)

            val baseGeq = readUserBaseGeq(profile)
            val effectiveGeq = ViperEffectModel.computeEffectiveGeq(
                baseGeq = baseGeq,
                freqs = DAX_GEQ_FREQUENCIES,
                bassLevel = bassLevel,
                bassCurve = curve.coerceIn(0, 2),
                bassCutoffHz = bassCutoff,
                dynamicLevel = dynamicLevel,
                clarityLevel = trebleLevel,
                clarityMode = clarityMode,
                speakerLevel = speakerLevel,
                surroundEnabled = surroundOn,
                surroundWidening = surroundWidening,
                surroundDepth = surroundDepth,
                midLevel = midLevel,
            )
            if (force || lastAppliedGeq == null || !lastAppliedGeq!!.contentEquals(effectiveGeq)) {
                dolbyEffect.setDapParameter(DsParam.GEQ_BAND_GAINS, effectiveGeq, profile)
                lastAppliedGeq = effectiveGeq.copyOf()
            }

            // Pair ViPER Bass / Dynamic System with Dolby's native Bass Enhancer (deduplicated)
            val bassEnhancerOn = bassLevel > 0 || dynamicLevel > 0 || prefs.getBoolean(DolbyConstants.PREF_BASS, false)
            if (force || lastAppliedBassEnhancer != bassEnhancerOn) {
                dolbyEffect.setDapParameter(DsParam.BASS_ENHANCER_ENABLE, bassEnhancerOn, profile)
                lastAppliedBassEnhancer = bassEnhancerOn
            }

            // Pair ViPER 3D Surround with Dolby's native Virtualizer + Stereo Widening (deduplicated)
            // (libhwdap.so requires Virtualizer enabled for STEREO_WIDENING_AMOUNT to be audible)
            val hpVirt = surroundOn || prefs.getBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, false)
            val spkVirt = surroundOn || prefs.getBoolean(DolbyConstants.PREF_SPK_VIRTUALIZER, false)
            if (force || lastAppliedHpVirt != hpVirt) {
                dolbyEffect.setDapParameter(DsParam.HEADPHONE_VIRTUALIZER, hpVirt, profile)
                lastAppliedHpVirt = hpVirt
            }
            if (force || lastAppliedSpkVirt != spkVirt) {
                dolbyEffect.setDapParameter(DsParam.SPEAKER_VIRTUALIZER, spkVirt, profile)
                lastAppliedSpkVirt = spkVirt
            }
            if (stereoWideningSupported) {
                val wideningAmt = if (surroundOn) {
                    (4 + (surroundWidening * 42 / 100) + (surroundDepth * 18 / 100)).coerceIn(4, 64)
                } else {
                    prefs.getInt(DolbyConstants.PREF_STEREO_WIDENING, 32)
                }
                if (force || lastAppliedWidening != wideningAmt) {
                    dolbyEffect.setDapParameter(DsParam.STEREO_WIDENING_AMOUNT, wideningAmt, profile)
                    lastAppliedWidening = wideningAmt
                }
            }
            lastAppliedProfile = profile
            true
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error applying ViPER model to Dolby DAP: ${e.message}")
            false
        }
    }

    fun getBassEnhancerEnabled(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_BASS)) {
            return prefs.getBoolean(DolbyConstants.PREF_BASS, false)
        }
        return try {
            dolbyEffect.getDapParameterBool(DsParam.BASS_ENHANCER_ENABLE, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting bass enhancer: ${e.message}")
            false
        }
    }

    fun setBassEnhancerEnabled(profile: Int, enabled: Boolean) {
        if (isReleased) return
        try {
            getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_BASS, enabled).apply()
            syncViperState(profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting bass enhancer: ${e.message}")
        }
    }

    fun getBassLevel(profile: Int): Int {
        val prefs = getProfilePrefs(profile)
        if (prefs.getInt(DolbyConstants.PREF_BASS_CURVE, 0) == ViperEffectModel.BASS_DYNAMIC) {
            return 0
        }
        return prefs.getInt(DolbyConstants.PREF_BASS_LEVEL, 0)
    }

    fun getBassCurve(profile: Int): Int {
        val prefs = getProfilePrefs(profile)
        val curve = prefs.getInt(DolbyConstants.PREF_BASS_CURVE, 0)
        return if (curve == ViperEffectModel.BASS_DYNAMIC) 0 else curve.coerceIn(0, 2)
    }

    @Synchronized
    fun setBassCurve(profile: Int, curve: Int) {
        if (isReleased) return
        getProfilePrefs(profile).edit().putInt(DolbyConstants.PREF_BASS_CURVE, curve).apply()
        syncViperState(profile)
        notifyStateUpdated()
    }

    fun getBassCutoff(profile: Int): Int {
        return getProfilePrefs(profile).getInt(DolbyConstants.PREF_BASS_CUTOFF, 60)
    }

    /** ViPER "speaker" crossover frequency (15-150 Hz) used by Natural Bass and Pure Bass. */
    @Synchronized
    fun setBassCutoff(profile: Int, hz: Int) {
        if (isReleased) return
        getProfilePrefs(profile).edit().putInt(DolbyConstants.PREF_BASS_CUTOFF, hz.coerceIn(15, 150)).apply()
        syncViperState(profile)
        notifyStateUpdated()
    }

    @Synchronized
    fun setBassLevel(profile: Int, level: Int) {
        if (isReleased) return
        if (level !in 0..100) {
            throw IllegalArgumentException("Bass level must be between 0 and 100")
        }
        getProfilePrefs(profile).edit()
            .putInt(DolbyConstants.PREF_BASS_LEVEL, level)
            .putBoolean(DolbyConstants.PREF_BASS, level > 0)
            .apply()
        syncViperState(profile)
        notifyStateUpdated()
    }

    fun getSpeakerCorrection(profile: Int): Int {
        return getProfilePrefs(profile).getInt(DolbyConstants.PREF_SPEAKER_CORRECTION, 0)
    }

    /** ViPER speaker correction strength, 0 (off) to 100 (full ViPER curve). */
    @Synchronized
    fun setSpeakerCorrection(profile: Int, level: Int) {
        if (isReleased) return
        val newLevel = level.coerceIn(0, 100)
        getProfilePrefs(profile).edit().putInt(DolbyConstants.PREF_SPEAKER_CORRECTION, newLevel).apply()
        syncViperState(profile)
        notifyStateUpdated()
    }

    fun getTrebleEnhancerEnabled(profile: Int): Boolean {
        return getProfilePrefs(profile).getBoolean(DolbyConstants.PREF_TREBLE, false)
    }

    fun setTrebleEnhancerEnabled(profile: Int, enabled: Boolean) {
        getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_TREBLE, enabled).apply()
    }

    fun getTrebleLevel(profile: Int): Int {
        return getProfilePrefs(profile).getInt(DolbyConstants.PREF_TREBLE_LEVEL, 0)
    }

    fun getClarityMode(profile: Int): Int {
        return getProfilePrefs(profile).getInt(DolbyConstants.PREF_CLARITY_MODE, 0)
    }

    @Synchronized
    fun setClarityMode(profile: Int, mode: Int) {
        if (isReleased) return
        getProfilePrefs(profile).edit().putInt(DolbyConstants.PREF_CLARITY_MODE, mode).apply()
        syncViperState(profile)
        notifyStateUpdated()
    }

    @Synchronized
    fun setTrebleLevel(profile: Int, level: Int) {
        if (isReleased) return
        if (level !in 0..100) {
            throw IllegalArgumentException("Treble level must be between 0 and 100")
        }
        getProfilePrefs(profile).edit()
            .putInt(DolbyConstants.PREF_TREBLE_LEVEL, level)
            .putBoolean(DolbyConstants.PREF_TREBLE, level > 0)
            .apply()
        syncViperState(profile)
        notifyStateUpdated()
    }

    /** Dynamic system strength, 0 (off) to 100. Older saves kept it as bass curve 3. */
    fun getDynamicLevel(profile: Int): Int {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_DYNAMIC_LEVEL)) {
            return prefs.getInt(DolbyConstants.PREF_DYNAMIC_LEVEL, 0)
        }
        val curve = prefs.getInt(DolbyConstants.PREF_BASS_CURVE, 0)
        return if (curve == ViperEffectModel.BASS_DYNAMIC) prefs.getInt(DolbyConstants.PREF_BASS_LEVEL, 0) else 0
    }

    @Synchronized
    fun setDynamicLevel(profile: Int, level: Int) {
        if (isReleased) return
        if (level !in 0..100) throw IllegalArgumentException("Dynamic system level must be between 0 and 100")
        getProfilePrefs(profile).edit().putInt(DolbyConstants.PREF_DYNAMIC_LEVEL, level).apply()
        syncViperState(profile)
        notifyStateUpdated()
    }

    fun getViperSurroundEnabled(profile: Int): Boolean {
        return getProfilePrefs(profile).getBoolean(DolbyConstants.PREF_VIPER_SURROUND, false)
    }

    @Synchronized
    fun setViperSurroundEnabled(profile: Int, enabled: Boolean) {
        if (isReleased) return
        getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_VIPER_SURROUND, enabled).apply()
        syncViperState(profile)
        notifyStateUpdated()
    }

    fun getViperSurroundWidening(profile: Int): Int {
        return getProfilePrefs(profile).getInt(DolbyConstants.PREF_VIPER_SURROUND_WIDENING, 50).coerceIn(1, 100)
    }

    @Synchronized
    fun setViperSurroundWidening(profile: Int, widening: Int) {
        if (isReleased) return
        getProfilePrefs(profile).edit().putInt(DolbyConstants.PREF_VIPER_SURROUND_WIDENING, widening.coerceIn(1, 100)).apply()
        syncViperState(profile)
        notifyStateUpdated()
    }

    fun getViperSurroundDepth(profile: Int): Int {
        return getProfilePrefs(profile).getInt(DolbyConstants.PREF_VIPER_SURROUND_DEPTH, 35).coerceIn(0, 100)
    }

    @Synchronized
    fun setViperSurroundDepth(profile: Int, depth: Int) {
        if (isReleased) return
        getProfilePrefs(profile).edit().putInt(DolbyConstants.PREF_VIPER_SURROUND_DEPTH, depth.coerceIn(0, 100)).apply()
        syncViperState(profile)
        notifyStateUpdated()
    }

    fun getVolumeLevelerEnabled(profile: Int): Boolean {
        if (!volumeLevelerSupported) return false
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_VOLUME)) {
            return prefs.getBoolean(DolbyConstants.PREF_VOLUME, false)
        }
        return try {
            dolbyEffect.getDapParameterBool(DsParam.VOLUME_LEVELER_ENABLE, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting volume leveler: ${e.message}")
            false
        }
    }

    fun setVolumeLevelerEnabled(profile: Int, enabled: Boolean) {
        if (!volumeLevelerSupported || isReleased) return
        
        try {
            getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_VOLUME, enabled).apply()
            if (getSavedDolbyEnabled()) {
                checkEffect()
                dolbyEffect.setDapParameter(DsParam.VOLUME_LEVELER_ENABLE, enabled, profile)
            }
            notifyStateUpdated()
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting volume leveler: ${e.message}")
        }
    }

    fun getIeqPreset(profile: Int): Int {
        return try {
            dolbyEffect.getDapParameterInt(DsParam.IEQ_PRESET, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting IEQ preset: ${e.message}")
            0
        }
    }

    fun setIeqPreset(profile: Int, preset: Int) {
        if (isReleased) return
        
        try {
            getProfilePrefs(profile).edit().putString(DolbyConstants.PREF_IEQ, preset.toString()).apply()
            if (getSavedDolbyEnabled()) {
                checkEffect()
                dolbyEffect.setDapParameter(DsParam.IEQ_PRESET, preset, profile)
                applyViper(profile, forceRebind = true)
                if (dolbyEffect.profile == profile) {
                    dolbyEffect.dsOn = true
                }
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting IEQ preset: ${e.message}")
        }
    }

    fun getHeadphoneVirtualizerEnabled(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_HP_VIRTUALIZER)) {
            return prefs.getBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, false)
        }
        return try {
            dolbyEffect.getDapParameterBool(DsParam.HEADPHONE_VIRTUALIZER, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting headphone virtualizer: ${e.message}")
            false
        }
    }

    fun setHeadphoneVirtualizerEnabled(profile: Int, enabled: Boolean) {
        if (isReleased) return
        
        try {
            getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, enabled).apply()
            syncViperState(profile)
            notifyStateUpdated()
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting headphone virtualizer: ${e.message}")
        }
    }

    fun getSpeakerVirtualizerEnabled(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_SPK_VIRTUALIZER)) {
            return prefs.getBoolean(DolbyConstants.PREF_SPK_VIRTUALIZER, false)
        }
        return try {
            dolbyEffect.getDapParameterBool(DsParam.SPEAKER_VIRTUALIZER, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting speaker virtualizer: ${e.message}")
            false
        }
    }

    fun setSpeakerVirtualizerEnabled(profile: Int, enabled: Boolean) {
        if (isReleased) return
        
        try {
            getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_SPK_VIRTUALIZER, enabled).apply()
            syncViperState(profile)
            notifyStateUpdated()
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting speaker virtualizer: ${e.message}")
        }
    }

    fun getStereoWideningAmount(profile: Int): Int {
        if (!stereoWideningSupported) return 0
        return try {
            dolbyEffect.getDapParameterInt(DsParam.STEREO_WIDENING_AMOUNT, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting stereo widening: ${e.message}")
            32
        }
    }

    fun setStereoWideningAmount(profile: Int, amount: Int) {
        if (!stereoWideningSupported || isReleased) return
        
        try {
            getProfilePrefs(profile).edit().putInt(DolbyConstants.PREF_STEREO_WIDENING, amount).apply()
            syncViperState(profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting stereo widening: ${e.message}")
        }
    }

    fun getDialogueEnhancerEnabled(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_DIALOGUE)) {
            return prefs.getBoolean(DolbyConstants.PREF_DIALOGUE, false)
        }
        return try {
            dolbyEffect.getDapParameterBool(DsParam.DIALOGUE_ENHANCER_ENABLE, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting dialogue enhancer: ${e.message}")
            false
        }
    }

    fun setDialogueEnhancerEnabled(profile: Int, enabled: Boolean) {
        if (isReleased) return
        
        try {
            getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_DIALOGUE, enabled).apply()
            if (getSavedDolbyEnabled()) {
                checkEffect()
                dolbyEffect.setDapParameter(DsParam.DIALOGUE_ENHANCER_ENABLE, enabled, profile)
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting dialogue enhancer: ${e.message}")
        }
    }

    fun getDialogueEnhancerAmount(profile: Int): Int {
        return try {
            dolbyEffect.getDapParameterInt(DsParam.DIALOGUE_ENHANCER_AMOUNT, profile)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting dialogue enhancer amount: ${e.message}")
            6
        }
    }

    fun setDialogueEnhancerAmount(profile: Int, amount: Int) {
        if (isReleased) return
        
        try {
            getProfilePrefs(profile).edit().putInt(DolbyConstants.PREF_DIALOGUE_AMOUNT, amount).apply()
            if (getSavedDolbyEnabled()) {
                checkEffect()
                dolbyEffect.setDapParameter(DsParam.DIALOGUE_ENHANCER_AMOUNT, amount, profile)
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting dialogue enhancer amount: ${e.message}")
        }
    }

    fun getEqualizerGains(profile: Int, bandMode: BandMode): List<BandGain> {
        return try {
            val gains = readUserBaseGeq(profile)
            deserializeGains(gains, bandMode)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting equalizer gains: ${e.message}")
            val frequencies = when (bandMode) {
                BandMode.TEN_BAND -> BAND_FREQUENCIES_10
                BandMode.FIFTEEN_BAND -> BAND_FREQUENCIES_15
                BandMode.TWENTY_BAND -> BAND_FREQUENCIES_20
            }
            frequencies.map { BandGain(frequency = it, gain = 0) }
        }
    }

    fun setEqualizerGains(profile: Int, bandGains: List<BandGain>, bandMode: BandMode) {
        if (isReleased) return
        
        try {
            val gains = serializeGains(bandGains, bandMode)
            val gainsString = gains.joinToString(",")
            getProfilePrefs(profile).edit().putString(DolbyConstants.PREF_PRESET, gainsString).apply()
            if (getSavedDolbyEnabled()) {
                applyViper(profile)
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting equalizer gains: ${e.message}")
        }
    }

    fun getPresetName(profile: Int): String {
        return try {
            val gains = readUserBaseGeq(profile)
            
            val tenBandGains = gains.filterIndexed { index, _ -> index % 2 == 0 }
            val currentGainsString = tenBandGains.joinToString(",")
            
            val presetValues = context.resources.getStringArray(R.array.dolby_preset_values)
            val presetNames = context.resources.getStringArray(R.array.dolby_preset_entries)
            
            presetValues.forEachIndexed { index, preset ->
                val presetTenBand = convertTo10Band(preset)
                if (gainsMatch(presetTenBand, currentGainsString)) {
                    return presetNames[index]
                }
            }
            
            presetsPrefs.all.forEach { (name, value) ->
                val presetTenBand = convertTo10Band(value.toString())
                if (gainsMatch(presetTenBand, currentGainsString)) {
                    return name
                }
            }
            
            context.getString(R.string.dolby_preset_custom)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting preset name: ${e.message}")
            context.getString(R.string.dolby_preset_custom)
        }
    }

    private fun convertTo10Band(gainsString: String): String {
        val gains = gainsString.split(",").map { it.trim().toIntOrNull() ?: 0 }
        
        if (gains.size == 10) {
            return gains.joinToString(",")
        }
        
        if (gains.size == 20) {
            val tenBand = gains.filterIndexed { index, _ -> index % 2 == 0 }
            return tenBand.joinToString(",")
        }
        
        return gainsString
    }

    private fun gainsMatch(gains1: String, gains2: String): Boolean {
        val g1 = gains1.split(",").map { it.trim().toIntOrNull() ?: 0 }
        val g2 = gains2.split(",").map { it.trim().toIntOrNull() ?: 0 }
        
        if (g1.size != g2.size) return false
        
        return g1.zip(g2).all { (a, b) -> kotlin.math.abs(a - b) <= 1 }
    }

    fun getUserPresets(): List<EqualizerPreset> {
        synchronized(presetCacheLock) {
            cachedPresets?.let { return it }
            
            val bandMode = getBandMode()
            val presets = presetsPrefs.all.mapNotNull { (name, value) ->
                try {
                    val valueStr = value as? String ?: return@mapNotNull null
                    parsePreset(name, valueStr)
                } catch (e: Exception) {
                    DolbyConstants.dlog(TAG, "Error parsing preset $name: ${e.message}")
                    null
                }
            }
            
            cachedPresets = presets
            return presets
        }
    }

    private fun parsePreset(name: String, valueStr: String): EqualizerPreset? {
        return try {
            if (valueStr.contains("|")) {
                val parts = valueStr.split("|")
                val presetBandMode = BandMode.fromValue(parts[1])
                val gains = parts[0].split(",").map { it.toInt() }.toIntArray()
                EqualizerPreset(
                    name = name,
                    bandGains = deserializeGains(gains, presetBandMode),
                    isUserDefined = true,
                    bandMode = presetBandMode
                )
            } else {
                val gains = valueStr.split(",").map { it.toInt() }.toIntArray()
                EqualizerPreset(
                    name = name,
                    bandGains = deserializeGains(gains, BandMode.TEN_BAND),
                    isUserDefined = true,
                    bandMode = BandMode.TEN_BAND
                )
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error parsing preset value: ${e.message}")
            null
        }
    }

    fun addUserPreset(name: String, bandGains: List<BandGain>, bandMode: BandMode) {
        try {
            val gains = serializeGains(bandGains, bandMode).joinToString(",")
            val value = "$gains|${bandMode.value}"
            presetsPrefs.edit().putString(name, value).apply()
            
            synchronized(presetCacheLock) {
                cachedPresets = null
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error adding user preset: ${e.message}")
        }
    }

    fun deleteUserPreset(name: String) {
        try {
            presetsPrefs.edit().remove(name).apply()
            
            synchronized(presetCacheLock) {
                cachedPresets = null
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error deleting user preset: ${e.message}")
        }
    }

    fun resetProfile(profile: Int) {
        if (isReleased) return
        
        try {
            invalidateDapCache()
            context.deleteSharedPreferences("profile_$profile")
            if (getSavedDolbyEnabled()) {
                checkEffect()
                dolbyEffect.resetProfileSpecificSettings(profile)
                syncViperState(profile, forceRebind = true)
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error resetting profile: ${e.message}")
        }
    }

    fun resetAllProfiles() {
        if (isReleased) return
        
        try {
            checkEffect()
            context.resources.getStringArray(R.array.dolby_profile_values)
                .map { it.toInt() }
                .forEach { resetProfile(it) }
            setCurrentProfile(0)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error resetting all profiles: ${e.message}")
        }
    }

    private fun deserializeGains(gains: IntArray, bandMode: BandMode): List<BandGain> {
        val frequencies = when (bandMode) {
            BandMode.TEN_BAND -> BAND_FREQUENCIES_10
            BandMode.FIFTEEN_BAND -> BAND_FREQUENCIES_15
            BandMode.TWENTY_BAND -> BAND_FREQUENCIES_20
        }
        
        val indices = when (bandMode) {
            BandMode.TEN_BAND -> TEN_BAND_INDICES
            BandMode.FIFTEEN_BAND -> FIFTEEN_BAND_INDICES
            BandMode.TWENTY_BAND -> (0..19).toList()
        }
        
        return frequencies.mapIndexed { index, freq ->
            val gainIndex = indices.getOrNull(index) ?: index
            BandGain(frequency = freq, gain = gains.getOrElse(gainIndex) { 0 })
        }
    }

    private fun serializeGains(bandGains: List<BandGain>, bandMode: BandMode): IntArray {
        val result = IntArray(20) { 0 }
        
        when (bandMode) {
            BandMode.TEN_BAND -> {
                TEN_BAND_INDICES.forEachIndexed { index, targetIndex ->
                    if (index < bandGains.size && targetIndex < 20) {
                        result[targetIndex] = bandGains[index].gain
                    }
                }
                for (i in 0 until 19 step 2) {
                    if (i + 2 < 20) {
                        result[i + 1] = (result[i] + result[i + 2]) / 2
                    }
                }   
                result[19] = result[18]
            }
            BandMode.FIFTEEN_BAND -> {
                FIFTEEN_BAND_INDICES.forEachIndexed { index, targetIndex ->
                    if (index < bandGains.size && targetIndex < 20) {
                        result[targetIndex] = bandGains[index].gain
                    }
                }
                val missing = (0..19).filter { it !in FIFTEEN_BAND_INDICES }
                missing.forEach { idx ->
                    val prev = FIFTEEN_BAND_INDICES.filter { it < idx }.maxOrNull() ?: 0
                    val next = FIFTEEN_BAND_INDICES.filter { it > idx }.minOrNull() ?: 19
                    
                    if (prev < idx && next > idx && prev < 20 && next < 20) {
                        val prevValue = result[prev]
                        val nextValue = result[next]
                        val ratio = (idx - prev).toFloat() / (next - prev)
                        result[idx] = (prevValue + ratio * (nextValue - prevValue)).toInt()
                    } else if (prev < 20) {
                        result[idx] = result[prev]
                    }
                }
            }
            BandMode.TWENTY_BAND -> {
                bandGains.forEachIndexed { index, bandGain ->
                    if (index < 20) {
                        result[index] = bandGain.gain
                    }
                }
            }
        }
        
        return result
    }

    fun getMidEnhancerEnabled(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        return prefs.getBoolean(DolbyConstants.PREF_MID, false)
    }

    fun setMidEnhancerEnabled(profile: Int, enabled: Boolean) {
        getProfilePrefs(profile).edit().putBoolean(DolbyConstants.PREF_MID, enabled).apply()
    }

    fun getMidLevel(profile: Int): Int {
        val prefs = getProfilePrefs(profile)
        return prefs.getInt(DolbyConstants.PREF_MID_LEVEL, 0)
    }

    @Synchronized
    fun setMidLevel(profile: Int, level: Int) {
        if (isReleased) return
        if (level !in 0..100) {
            throw IllegalArgumentException("Mid level must be between 0 and 100")
        }
        getProfilePrefs(profile).edit()
            .putInt(DolbyConstants.PREF_MID_LEVEL, level)
            .putBoolean(DolbyConstants.PREF_MID, level > 0)
            .apply()
        syncViperState(profile)
        notifyStateUpdated()
    }

    /** One shared repository per process, so there is nothing to release per caller. */
    override fun close() {
    }

    companion object {
        @Volatile
        private var instance: DolbyRepository? = null

        /**
         * One repository per process. The Dolby effect handle is shared, so the @Synchronized
         * methods must lock on the same object for the UI, the service, the tile and the provider.
         */
        fun get(context: Context): DolbyRepository =
            instance ?: synchronized(this) {
                instance ?: DolbyRepository(context).also { instance = it }
            }

        private const val TAG = "DolbyRepository"
        private const val EFFECT_PRIORITY = 100
        
        private const val MID_GAIN_MULTIPLIER = 1.3f
        
        private val ATTRIBUTES_MEDIA = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val BAND_FREQUENCIES_10 = listOf(32, 64, 125, 250, 500, 1000, 2250, 5000, 10000, 19688)
        
        val BAND_FREQUENCIES_15 = listOf(
            32, 47, 94, 141, 234, 469, 844, 1313, 2250, 3750, 5813, 9000, 11250, 13875, 19688
        )
        
        /**
         * The 20 graphic equalizer bands of the Dolby engine, as written in dax-default.xml. The ViPER
         * curves are worked out on these so each band gets the gain of its own frequency.
         */
        val DAX_GEQ_FREQUENCIES = listOf(
            47, 141, 234, 328, 469, 656, 844, 1031, 1313, 1688,
            2250, 3000, 3750, 4688, 5813, 7125, 9000, 11250, 13875, 19688
        )

        val BAND_FREQUENCIES_20 = listOf(
            32, 47, 141, 234, 328, 469, 656, 844, 1031, 1313,
            1688, 2250, 3000, 3750, 4688, 5813, 7125, 9000, 11250, 19688
        )
        
        private val TEN_BAND_INDICES = listOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18)
        
        private val FIFTEEN_BAND_INDICES = listOf(0, 1, 2, 3, 4, 5, 6, 8, 11, 12, 14, 15, 17, 18, 19)
    }
}

/** The single Dolby effect handle of this process, shared by every [DolbyRepository]. */
private object SharedDolbyEffect {
    private var effect: DolbyAudioEffect? = null

    @Synchronized
    fun get(create: () -> DolbyAudioEffect): DolbyAudioEffect =
        effect ?: create().also { effect = it }

    @Synchronized
    fun drop() {
        try {
            effect?.release()
        } catch (_: Exception) {
        }
        effect = null
    }
}
