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
import org.lunaris.dolby.ActiveEngine
import org.lunaris.dolby.SoundEngineMode
import org.lunaris.dolby.audio.DolbyAudioEffect
import org.lunaris.dolby.audio.MiSoundAudioEffect
import org.lunaris.dolby.audio.MiSoundXmlParser
import org.lunaris.dolby.audio.QcomEffectsController
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

    private val qcomEffects = QcomEffectsController()
    private val miSoundEffect = MiSoundAudioEffect(MISOUND_EFFECT_PRIORITY, audioSession = 0)

    private val recoveryHandler: android.os.Handler by lazy {
        val t = android.os.HandlerThread("MiSoundRecovery").apply { start() }
        android.os.Handler(t.looper)
    }
    private var miSoundRecoveryAttempts = 0
    private val miSoundRecoveryRunnable = Runnable { recoverMiSound() }

    init {
        miSoundEffect.onRecoveryNeeded = {
            recoveryHandler.removeCallbacks(miSoundRecoveryRunnable)
            recoveryHandler.postDelayed(miSoundRecoveryRunnable, MISOUND_RECOVERY_DELAY_MS)
        }
    }

    @Synchronized
    private fun recoverMiSound() {
        if (isReleased || getSoundEngineMode() != SoundEngineMode.XIAOMI_SOUND || !getSavedDolbyEnabled()) {
            miSoundRecoveryAttempts = 0
            return
        }
        val ok = applyXiaomiSoundTuning(forceRebind = true)
        android.util.Log.i(TAG, "MiSound recovery ok=$ok attempt=$miSoundRecoveryAttempts ${miSoundEffect.describeState()}")
        if (ok) {
            miSoundRecoveryAttempts = 0
        } else if (miSoundRecoveryAttempts++ < MISOUND_RECOVERY_MAX_ATTEMPTS) {
            recoveryHandler.postDelayed(miSoundRecoveryRunnable, MISOUND_RECOVERY_DELAY_MS * miSoundRecoveryAttempts)
        }
    }

    private val _soundEngineMode = MutableStateFlow(
        SoundEngineMode.fromId(defaultPrefs.getInt(DolbyConstants.PREF_SOUND_ENGINE_MODE, 0))
    )
    val soundEngineMode: StateFlow<SoundEngineMode> = _soundEngineMode.asStateFlow()

    private val _miSoundScenario = MutableStateFlow(
        defaultPrefs.getInt(DolbyConstants.PREF_MISOUND_SCENARIO, 0).coerceIn(0, 2)
    )
    val miSoundScenario: StateFlow<Int> = _miSoundScenario.asStateFlow()

    fun getSoundEngineMode(): SoundEngineMode =
        SoundEngineMode.fromId(defaultPrefs.getInt(DolbyConstants.PREF_SOUND_ENGINE_MODE, 0))

    fun getMiSoundScenario(): Int =
        defaultPrefs.getInt(DolbyConstants.PREF_MISOUND_SCENARIO, 0).coerceIn(0, 2)

    private fun isDolbyActive(): Boolean =
        getSavedDolbyEnabled() && getSoundEngineMode() == SoundEngineMode.DOLBY_ATMOS

    fun getSavedEngineEnabled(mode: SoundEngineMode): Boolean {
        val key = when (mode) {
            SoundEngineMode.DOLBY_ATMOS -> DolbyConstants.PREF_DOLBY_MASTER_ENABLE
            SoundEngineMode.XIAOMI_SOUND -> DolbyConstants.PREF_MISOUND_MASTER_ENABLE
        }
        if (defaultPrefs.contains(key)) {
            return defaultPrefs.getBoolean(key, false)
        }
        // PREF_ENABLE predates the engine split and only ever described Dolby.
        return mode == SoundEngineMode.DOLBY_ATMOS &&
            defaultPrefs.getBoolean(DolbyConstants.PREF_ENABLE, false)
    }

    fun getActiveEngine(): ActiveEngine {
        val mode = getSoundEngineMode()
        if (!getSavedEngineEnabled(mode)) return ActiveEngine.NONE
        return if (mode == SoundEngineMode.DOLBY_ATMOS) ActiveEngine.DOLBY else ActiveEngine.MISOUND
    }

    private fun writeMasters(active: ActiveEngine) {
        defaultPrefs.edit()
            .putBoolean(DolbyConstants.PREF_DOLBY_MASTER_ENABLE, active == ActiveEngine.DOLBY)
            .putBoolean(DolbyConstants.PREF_MISOUND_MASTER_ENABLE, active == ActiveEngine.MISOUND)
            .putBoolean(DolbyConstants.PREF_ENABLE, active != ActiveEngine.NONE)
            .apply()
    }

    private fun getScenarioPrefs(scenario: Int): SharedPreferences =
        context.getSharedPreferences(
            "${DolbyConstants.PREF_MISOUND_SCENARIO_PREFIX}${scenario.coerceIn(0, 2)}",
            Context.MODE_PRIVATE
        )

    fun getMiSound3dSurround(scenario: Int = getMiSoundScenario()): Boolean {
        val tuning = MiSoundXmlParser.getTuning(context, scenario)
        return getScenarioPrefs(scenario).getBoolean(
            DolbyConstants.PREF_MISOUND_3D_SURROUND,
            tuning.surround3dEnabled
        )
    }

    fun getMiSoundSurroundLevel(scenario: Int = getMiSoundScenario()): Int {
        val tuning = MiSoundXmlParser.getTuning(context, scenario)
        return getScenarioPrefs(scenario).getInt(
            DolbyConstants.PREF_MISOUND_SURROUND_LEVEL,
            tuning.surroundLevel
        ).coerceIn(0, 100)
    }

    fun getMiSoundVocalLevel(scenario: Int = getMiSoundScenario()): Int {
        val tuning = MiSoundXmlParser.getTuning(context, scenario)
        return getScenarioPrefs(scenario).getInt(
            DolbyConstants.PREF_MISOUND_VOCAL_LEVEL,
            tuning.vocalLevel
        ).coerceIn(0, 100)
    }

    fun readMiSoundScenarioGeq(scenario: Int = getMiSoundScenario()): IntArray {
        val sp = getScenarioPrefs(scenario)
        val saved = sp.getString(DolbyConstants.PREF_MISOUND_EQ_GAINS, null)
        if (saved != null) {
            val parsed = saved.split(",").mapNotNull { it.toIntOrNull() }
            if (parsed.size == DolbyConstants.MISOUND_EQ_BAND_COUNT) {
                return IntArray(DolbyConstants.MISOUND_EQ_BAND_COUNT) { idx ->
                    parsed[idx].coerceIn(
                        DolbyConstants.MISOUND_EQ_MIN_TENTHS,
                        DolbyConstants.MISOUND_EQ_MAX_TENTHS
                    )
                }
            }
            if (parsed.size == 20) {
                return IntArray(DolbyConstants.MISOUND_EQ_BAND_COUNT) { idx ->
                    parsed[idx * 2].coerceIn(
                        DolbyConstants.MISOUND_EQ_MIN_TENTHS,
                        DolbyConstants.MISOUND_EQ_MAX_TENTHS
                    )
                }
            }
        }
        return MiSoundXmlParser.getTuning(context, scenario).eqGainsTenths.copyOf()
    }

    @Synchronized
    fun resetMiSoundEqGains(scenario: Int = getMiSoundScenario()) {
        getScenarioPrefs(scenario).edit().remove(DolbyConstants.PREF_MISOUND_EQ_GAINS).apply()
        if (getSavedDolbyEnabled() && getSoundEngineMode() == SoundEngineMode.XIAOMI_SOUND) {
            applyXiaomiSoundTuning(forceRebind = true)
        }
    }

    @Synchronized
    fun restoreMiSoundSnapshot(
        scenario: Int,
        surround3d: List<Boolean>,
        surroundLevels: List<Int>,
        vocalLevels: List<Int>,
        eqGainsList: List<String?>
    ) {
        if (isReleased) return
        val clampedScenario = scenario.coerceIn(0, 2)
        defaultPrefs.edit().putInt(DolbyConstants.PREF_MISOUND_SCENARIO, clampedScenario).apply()
        _miSoundScenario.value = clampedScenario
        for (s in 0..2) {
            val editor = getScenarioPrefs(s).edit()
            surround3d.getOrNull(s)?.let { editor.putBoolean(DolbyConstants.PREF_MISOUND_3D_SURROUND, it) }
            surroundLevels.getOrNull(s)?.let {
                editor.putInt(DolbyConstants.PREF_MISOUND_SURROUND_LEVEL, it.coerceIn(0, 100))
            }
            vocalLevels.getOrNull(s)?.let {
                editor.putInt(DolbyConstants.PREF_MISOUND_VOCAL_LEVEL, it.coerceIn(0, 100))
            }
            val eqStr = eqGainsList.getOrNull(s)
            if (!eqStr.isNullOrBlank()) {
                editor.putString(DolbyConstants.PREF_MISOUND_EQ_GAINS, eqStr)
            } else {
                editor.remove(DolbyConstants.PREF_MISOUND_EQ_GAINS)
            }
            editor.apply()
        }
        // Memory only: the caller applies once through setSoundEngineMode() -> routeEngine().
    }

    fun restoreSavedProfile(profile: Int) {
        if (isReleased) return
        defaultPrefs.edit().putString(DolbyConstants.PREF_PROFILE, profile.toString()).apply()
        _currentProfile.value = profile
    }

    fun getDolbyEqualizerGainsForSnapshot(profile: Int): List<BandGain> =
        deserializeGains(readUserBaseGeq(profile), BandMode.TWENTY_BAND)

    @Synchronized
    fun restoreEngineMasterStates(dolbyMaster: Boolean, misoundMaster: Boolean) {
        if (isReleased) return
        defaultPrefs.edit()
            .putBoolean(DolbyConstants.PREF_DOLBY_MASTER_ENABLE, dolbyMaster)
            .putBoolean(DolbyConstants.PREF_MISOUND_MASTER_ENABLE, misoundMaster)
            .apply()
    }

    @Synchronized
    fun restoreDolbyProfilePrefsForSnapshot(
        profile: Int,
        ieqPreset: Int,
        hpVirt: Boolean,
        spkVirt: Boolean,
        dialogueEnabled: Boolean,
        dialogueAmount: Int,
        volumeLeveler: Boolean?,
        stereoWidening: Int?,
        eqGains20: String?,
        bassCurve: Int,
        bassCutoff: Int,
        bassLevel: Int,
        clarityMode: Int,
        trebleLevel: Int,
        dynamicLevel: Int,
        speakerCorrection: Int,
        midLevel: Int,
        viperSurroundWidening: Int,
        viperSurroundDepth: Int,
        viperSurroundEnabled: Boolean
    ) {
        if (isReleased) return
        val editor = getProfilePrefs(profile).edit()
            .putString(DolbyConstants.PREF_IEQ, ieqPreset.toString())
            .putBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, hpVirt)
            .putBoolean(DolbyConstants.PREF_SPK_VIRTUALIZER, spkVirt)
            .putBoolean(DolbyConstants.PREF_DIALOGUE, dialogueEnabled)
            .putInt(DolbyConstants.PREF_DIALOGUE_AMOUNT, dialogueAmount)
            .putInt(DolbyConstants.PREF_BASS_CURVE, bassCurve.coerceIn(0, 2))
            .putInt(DolbyConstants.PREF_BASS_CUTOFF, bassCutoff.coerceIn(15, 150))
            .putInt(DolbyConstants.PREF_BASS_LEVEL, bassLevel.coerceIn(0, 100))
            .putBoolean(DolbyConstants.PREF_BASS, bassLevel > 0)
            .putInt(DolbyConstants.PREF_CLARITY_MODE, clarityMode)
            .putInt(DolbyConstants.PREF_TREBLE_LEVEL, trebleLevel.coerceIn(0, 100))
            .putBoolean(DolbyConstants.PREF_TREBLE, trebleLevel > 0)
            .putInt(DolbyConstants.PREF_DYNAMIC_LEVEL, dynamicLevel.coerceIn(0, 100))
            .putInt(DolbyConstants.PREF_SPEAKER_CORRECTION, speakerCorrection.coerceIn(0, 100))
            .putInt(DolbyConstants.PREF_MID_LEVEL, midLevel.coerceIn(0, 100))
            .putBoolean(DolbyConstants.PREF_MID, midLevel > 0)
            .putInt(DolbyConstants.PREF_VIPER_SURROUND_WIDENING, viperSurroundWidening.coerceIn(1, 100))
            .putInt(DolbyConstants.PREF_VIPER_SURROUND_DEPTH, viperSurroundDepth.coerceIn(0, 100))
            .putBoolean(DolbyConstants.PREF_VIPER_SURROUND, viperSurroundEnabled)
        volumeLeveler?.let { editor.putBoolean(DolbyConstants.PREF_VOLUME, it) }
        stereoWidening?.let { editor.putInt(DolbyConstants.PREF_STEREO_WIDENING, it) }
        if (!eqGains20.isNullOrBlank() && eqGains20.split(",").size == 20) {
            editor.putString(DolbyConstants.PREF_PRESET, eqGains20)
        }
        editor.apply()
    }

    private fun pushMiSoundParams(
        enabled: Boolean = getSavedDolbyEnabled() && getSoundEngineMode() == SoundEngineMode.XIAOMI_SOUND,
        scenario: Int = getMiSoundScenario()
    ) {
        miSoundEffect.syncProperties(
            enabled = enabled,
            uiScenario = scenario,
            surround3dEnabled = getMiSound3dSurround(scenario),
            surroundLevel = getMiSoundSurroundLevel(scenario),
            vocalLevel = getMiSoundVocalLevel(scenario),
            eqGainsTenths = readMiSoundScenarioGeq(scenario)
        )
    }

    private fun applyXiaomiSoundTuning(forceRebind: Boolean = false): Boolean {
        val scenario = getMiSoundScenario()
        val surroundOn = getMiSound3dSurround(scenario)
        val surroundLevel = getMiSoundSurroundLevel(scenario)
        val vocalLevel = getMiSoundVocalLevel(scenario)
        val scenarioEq = readMiSoundScenarioGeq(scenario)

        return miSoundEffect.applyAllParams(
            uiScenario = scenario,
            surround3dEnabled = surroundOn,
            surroundLevel = surroundLevel,
            vocalLevel = vocalLevel,
            eqGainsTenths = scenarioEq,
            force = forceRebind
        )
    }

    private fun applyXiaomiSoundTuningAndReport(): Boolean {
        val scenario = getMiSoundScenario()
        val surroundOn = getMiSound3dSurround(scenario)
        val surroundLevel = getMiSoundSurroundLevel(scenario)
        val vocalLevel = getMiSoundVocalLevel(scenario)
        val scenarioEq = readMiSoundScenarioGeq(scenario)
        return miSoundEffect.applyAllParams(
            uiScenario = scenario,
            surround3dEnabled = surroundOn,
            surroundLevel = surroundLevel,
            vocalLevel = vocalLevel,
            eqGainsTenths = scenarioEq,
            force = true
        )
    }

    @Synchronized
    fun setSoundEngineMode(mode: SoundEngineMode, carryEnabled: Boolean = true) {
        if (isReleased) return
        val prevMode = getSoundEngineMode()
        val changed = prevMode != mode
        // Exclusive switch: whatever was ON hands over to the new engine, OFF stays OFF.
        val targetEnabled = if (changed && carryEnabled) {
            getSavedEngineEnabled(prevMode)
        } else {
            getSavedEngineEnabled(mode)
        }
        defaultPrefs.edit().putInt(DolbyConstants.PREF_SOUND_ENGINE_MODE, mode.id).apply()
        writeMasters(
            when {
                !targetEnabled -> ActiveEngine.NONE
                mode == SoundEngineMode.DOLBY_ATMOS -> ActiveEngine.DOLBY
                else -> ActiveEngine.MISOUND
            }
        )
        _soundEngineMode.value = mode
        if (changed) invalidateDapCache()
        applySavedState()
        notifyStateUpdated()
    }

    @Synchronized
    fun setMiSoundScenario(scenario: Int) {
        if (isReleased) return
        val clamped = scenario.coerceIn(0, 2)
        defaultPrefs.edit().putInt(DolbyConstants.PREF_MISOUND_SCENARIO, clamped).apply()
        _miSoundScenario.value = clamped
        if (getSavedDolbyEnabled() && getSoundEngineMode() == SoundEngineMode.XIAOMI_SOUND) {
            applyXiaomiSoundTuning(forceRebind = true)
        } else {
            pushMiSoundParams(enabled = false, scenario = clamped)
        }
        notifyStateUpdated()
    }

    @Synchronized
    fun setMiSound3dSurround(enabled: Boolean) {
        if (isReleased) return
        val scenario = getMiSoundScenario()
        getScenarioPrefs(scenario).edit().putBoolean(DolbyConstants.PREF_MISOUND_3D_SURROUND, enabled).apply()
        if (getSavedDolbyEnabled() && getSoundEngineMode() == SoundEngineMode.XIAOMI_SOUND) {
            applyXiaomiSoundTuning(forceRebind = true)
        } else {
            pushMiSoundParams(enabled = false, scenario = scenario)
        }
        notifyStateUpdated()
    }

    @Synchronized
    fun setMiSoundSurroundLevel(level: Int) {
        if (isReleased) return
        val clamped = level.coerceIn(0, 100)
        val scenario = getMiSoundScenario()
        getScenarioPrefs(scenario).edit().putInt(DolbyConstants.PREF_MISOUND_SURROUND_LEVEL, clamped).apply()
        if (getSavedDolbyEnabled() && getSoundEngineMode() == SoundEngineMode.XIAOMI_SOUND) {
            applyXiaomiSoundTuning(forceRebind = true)
        } else {
            pushMiSoundParams(enabled = false, scenario = scenario)
        }
        notifyStateUpdated()
    }

    @Synchronized
    fun setMiSoundVocalLevel(level: Int) {
        if (isReleased) return
        val clamped = level.coerceIn(0, 100)
        val scenario = getMiSoundScenario()
        getScenarioPrefs(scenario).edit().putInt(DolbyConstants.PREF_MISOUND_VOCAL_LEVEL, clamped).apply()
        if (getSavedDolbyEnabled() && getSoundEngineMode() == SoundEngineMode.XIAOMI_SOUND) {
            applyXiaomiSoundTuning(forceRebind = true)
        } else {
            pushMiSoundParams(enabled = false, scenario = scenario)
        }
        notifyStateUpdated()
    }

    private fun dolbyIsOff(fx: DolbyAudioEffect): Boolean =
        runCatching { !fx.enabled }.getOrDefault(false)

    private fun stopDolby(): Boolean {
        runCatching { qcomEffects.disableAll(releaseHandles = false) }
        invalidateDapCache()
        val fx = SharedDolbyEffect.peek() ?: return true
        if (dolbyIsOff(fx)) return true
        runCatching { fx.dsOn = false }
        if (dolbyIsOff(fx)) return true
        val fresh = runCatching {
            recreateDolbyEffect()
            SharedDolbyEffect.peek()
        }.getOrNull() ?: return true
        runCatching { fresh.dsOn = false }
        val off = dolbyIsOff(fresh)
        if (!off) android.util.Log.w(TAG, "stopDolby: DAP still enabled after takeover")
        return off
    }

    private fun stopMiSound(syncProps: Boolean = true): Boolean {
        val ok = miSoundEffect.setEnabled(false)
        if (syncProps) pushMiSoundParams(enabled = false, scenario = getMiSoundScenario())
        if (!ok) android.util.Log.w(TAG, "stopMiSound: ${miSoundEffect.describeState()}")
        return ok
    }

    private fun startDolby(): Boolean {
        val fx = try {
            checkEffect()
            dolbyEffect
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "Initial DolbyAudioEffect creation failed, retrying", e)
            runCatching {
                recreateDolbyEffect()
                dolbyEffect
            }.getOrNull()
        } ?: return false

        val savedProfile = readSavedProfile() ?: 0
        try {
            if (fx.profile != savedProfile) {
                fx.profile = savedProfile
            }
        } catch (_: Throwable) {
            runCatching {
                recreateDolbyEffect()
                dolbyEffect.profile = savedProfile
            }
        }
        applyProfileSettings(savedProfile)
        var viperOk = applyViper(savedProfile, forceRebind = true)
        try {
            dolbyEffect.dsOn = true
        } catch (_: Throwable) {
            runCatching {
                recreateDolbyEffect()
                dolbyEffect.profile = savedProfile
                applyProfileSettings(savedProfile)
                viperOk = applyViper(savedProfile, forceRebind = true)
                dolbyEffect.dsOn = true
            }
        }
        val live = runCatching { dolbyEffect.dsOn }.getOrDefault(false)
        android.util.Log.i(TAG, "startDolby: live=$live viperOk=$viperOk profile=$savedProfile")
        return live && viperOk
    }

    private fun startMiSound(): Boolean {
        val ok = applyXiaomiSoundTuningAndReport()
        android.util.Log.i(TAG, "startMiSound: ok=$ok scenario=${getMiSoundScenario()} ${miSoundEffect.describeState()}")
        return ok
    }

    @Synchronized
    private fun routeEngine(): Boolean = when (getActiveEngine()) {
        ActiveEngine.DOLBY -> {
            val miOff = stopMiSound()
            startDolby() && miOff
        }
        ActiveEngine.MISOUND -> {
            val dolbyOff = stopDolby()
            startMiSound() && dolbyOff
        }
        ActiveEngine.NONE -> {
            val miOff = stopMiSound()
            val dolbyOff = stopDolby()
            miOff && dolbyOff
        }
    }

    private fun isInactiveEngineLeaking(): Boolean = try {
        when (getActiveEngine()) {
            ActiveEngine.DOLBY -> miSoundEffect.isEnabled
            ActiveEngine.MISOUND -> SharedDolbyEffect.peek()?.let { !dolbyIsOff(it) } == true
            ActiveEngine.NONE -> miSoundEffect.isEnabled ||
                SharedDolbyEffect.peek()?.let { !dolbyIsOff(it) } == true
        }
    } catch (_: Throwable) {
        false
    }

    private fun teardownDolbyCompletely() {
        stopDolby()
        SharedDolbyEffect.drop()
    }

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
        qcomEffects.invalidateCache()
    }

    private var cachedPresets: List<EqualizerPreset>? = null
    private val presetCacheLock = Any()

    private fun createDolbyEffect(): DolbyAudioEffect {
        return try {
            DolbyAudioEffect(EFFECT_PRIORITY, audioSession = 0)
        } catch (e: Throwable) {
            DolbyConstants.dlog(TAG, "Failed to create Dolby effect: ${e.message}")
            throw e
        }
    }

    @Synchronized
    private fun checkEffect() {
        if (isReleased || getSoundEngineMode() != SoundEngineMode.DOLBY_ATMOS) {
            DolbyConstants.dlog(TAG, "Skipping Dolby checkEffect (released=$isReleased mode=${getSoundEngineMode()})")
            return
        }

        try {
            if (!dolbyEffect.hasControl()) {
                DolbyConstants.dlog(TAG, "Lost audio effect control, recreating")
                recreateDolbyEffect()
                restoreSavedProfileIfNeeded()
            }
        } catch (e: Throwable) {
            DolbyConstants.dlog(TAG, "Error checking effect: ${e.message}")
            try {
                recreateDolbyEffect()
            } catch (e2: Throwable) {
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
        try {
            if (dolbyEffect.profile != savedProfile) {
                dolbyEffect.profile = savedProfile
            }
        } catch (_: Throwable) {
        }
        applyProfileSettings(savedProfile)
        applyViper(savedProfile, forceRebind = forceDapPush)
    }

    private fun applyProfileSettings(profile: Int) {
        try {
            val prefs = getProfilePrefs(profile)

            if (prefs.contains(DolbyConstants.PREF_IEQ)) {
                val ieqPreset = prefs.getString(DolbyConstants.PREF_IEQ, "0")?.toIntOrNull() ?: 0
                runCatching { dolbyEffect.setDapParameter(DsParam.IEQ_PRESET, ieqPreset, profile) }
            }

            if (prefs.contains(DolbyConstants.PREF_DIALOGUE)) {
                runCatching {
                    dolbyEffect.setDapParameter(
                        DsParam.DIALOGUE_ENHANCER_ENABLE,
                        prefs.getBoolean(DolbyConstants.PREF_DIALOGUE, false), profile
                    )
                }
            }

            if (prefs.contains(DolbyConstants.PREF_DIALOGUE_AMOUNT)) {
                runCatching {
                    dolbyEffect.setDapParameter(
                        DsParam.DIALOGUE_ENHANCER_AMOUNT,
                        prefs.getInt(DolbyConstants.PREF_DIALOGUE_AMOUNT, 6), profile
                    )
                }
            }

            if (volumeLevelerSupported && prefs.contains(DolbyConstants.PREF_VOLUME)) {
                runCatching {
                    dolbyEffect.setDapParameter(
                        DsParam.VOLUME_LEVELER_ENABLE,
                        prefs.getBoolean(DolbyConstants.PREF_VOLUME, false), profile
                    )
                }
            }

            DolbyConstants.dlog(TAG, "Successfully restored saved settings for profile $profile")
        } catch (e: Throwable) {
            DolbyConstants.dlog(TAG, "Failed to restore profile settings: ${e.message}")
        }
    }

    fun getSavedProfile(): Int = readSavedProfile() ?: 0

    fun getSavedDolbyEnabled(): Boolean =
        getSavedEngineEnabled(getSoundEngineMode())

    @Synchronized
    fun applySavedState(): Boolean {
        if (isReleased) return false
        return try {
            routeEngine().also {
                android.util.Log.i(TAG, "applySavedState: engine=${getActiveEngine()} ok=$it")
            }
        } catch (e: Throwable) {
            DolbyConstants.dlog(TAG, "applySavedState failed: ${e.message}")
            android.util.Log.w(TAG, "applySavedState failed: ${e.message}")
            false
        }
    }

    private fun checkIsOnSpeaker(): Boolean {
        return try {
            val device = audioManager.getDevicesForAttributes(ATTRIBUTES_MEDIA)[0]
            device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } catch (e: Throwable) {
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
            when (getSoundEngineMode()) {
                SoundEngineMode.DOLBY_ATMOS ->
                    SharedDolbyEffect.peek()?.dsOn == true
                SoundEngineMode.XIAOMI_SOUND -> miSoundEffect.isEnabled
            }
        } catch (e: Throwable) {
            DolbyConstants.dlog(TAG, "Error getting enabled state: ${e.message}")
            false
        }
    }

    @Synchronized
    fun setDolbyEnabled(enabled: Boolean) {
        if (isReleased) return
        val mode = getSoundEngineMode()
        writeMasters(
            when {
                !enabled -> ActiveEngine.NONE
                mode == SoundEngineMode.DOLBY_ATMOS -> ActiveEngine.DOLBY
                else -> ActiveEngine.MISOUND
            }
        )
        val ok = applySavedState()
        if (enabled && !ok) {
            if (mode == SoundEngineMode.DOLBY_ATMOS) {
                recreateDolbyEffect()
            } else {
                miSoundEffect.setEnabled(false)
            }
            applySavedState()
        }
    }

    @Synchronized
    fun syncPlaybackSessions(
        @Suppress("UNUSED_PARAMETER") activeSessionIds: Set<Int>,
        @Suppress("UNUSED_PARAMETER") restricted: Boolean = false
    ) {
        // Global session 0 single-driver mode.
    }

    @Synchronized
    fun refreshOnPlayback(
        @Suppress("UNUSED_PARAMETER") activeSessionIds: Set<Int> = emptySet(),
        restricted: Boolean = false,
        forceRestore: Boolean = false,
        trackChanged: Boolean = false
    ): Boolean {
        if (isReleased) return true
        try {
            val enabled = getSavedDolbyEnabled()
            val mode = getSoundEngineMode()
            if (!enabled) {
                val miOff = stopMiSound(syncProps = false)
                val dolbyOff = stopDolby()
                lastAppliedRestricted = false
                return miOff && dolbyOff
            }

            if (mode == SoundEngineMode.XIAOMI_SOUND) {
                if (!stopDolby()) return false
                val healthy = miSoundEffect.isHealthy()
                if (forceRestore || !healthy) {
                    val ok = applyXiaomiSoundTuning(forceRebind = true)
                    android.util.Log.i(
                        TAG,
                        "refreshOnPlayback(XIAOMI): force=$forceRestore track=$trackChanged " +
                            "healthy=$healthy applied=$ok ${miSoundEffect.describeState()}"
                    )
                    return ok
                }
                if (trackChanged) {
                    return applyXiaomiSoundTuning(forceRebind = false)
                }
                return true
            }

            if (!stopMiSound(syncProps = false)) return false
            checkEffect()
            val effectiveRestricted = restricted ||
                DolbyEffectService.hasRestrictedPlayback(audioManager.activePlaybackConfigurations)
            val savedProfile = readSavedProfile() ?: 0
            val geqDrifted = try {
                val expected = lastAppliedGeq
                expected == null || !dolbyEffect.getDapParameter(DsParam.GEQ_BAND_GAINS, savedProfile).contentEquals(expected)
            } catch (_: Throwable) {
                true
            }
            val virtDrifted = !effectiveRestricted && isVirtualizerDrifted(savedProfile)
            val needsRestore = forceRestore ||
                !dolbyEffect.dsOn ||
                dolbyEffect.profile != savedProfile ||
                effectiveRestricted != lastAppliedRestricted ||
                geqDrifted ||
                virtDrifted

            if (!needsRestore) return true

            if (forceRestore) {
                invalidateDapCache()
            }
            lastAppliedRestricted = effectiveRestricted
            runCatching {
                if (dolbyEffect.profile != savedProfile) {
                    dolbyEffect.profile = savedProfile
                }
            }
            applyProfileSettings(savedProfile)
            var ok = true
            if (!effectiveRestricted) {
                ok = applyViper(savedProfile, forceRebind = forceRestore || geqDrifted || virtDrifted)
            } else {
                qcomEffects.disableAll(releaseHandles = false)
                restoreProfilePreset(savedProfile)
                invalidateDapCache()
            }
            if (!dolbyEffect.dsOn) {
                runCatching { dolbyEffect.dsOn = true }
            }
            return ok && runCatching { dolbyEffect.dsOn }.getOrDefault(false)
        } catch (e: Throwable) {
            DolbyConstants.dlog(TAG, "refreshOnPlayback failed: ${e.message}")
            return false
        }
    }

    private fun isVirtualizerDrifted(profile: Int): Boolean {
        val expectOn = getProfilePrefs(profile).getBoolean(DolbyConstants.PREF_VIPER_SURROUND, false)
        return try {
            dolbyEffect.getDapParameterBool(DsParam.HEADPHONE_VIRTUALIZER, profile) != expectOn ||
                dolbyEffect.getDapParameterBool(DsParam.SPEAKER_VIRTUALIZER, profile) != expectOn
        } catch (_: Throwable) {
            true
        }
    }

    @Synchronized
    fun ensureLiveState() {
        if (isReleased) return
        try {
            if (getDolbyEnabled() != getSavedDolbyEnabled() || isInactiveEngineLeaking()) {
                DolbyConstants.dlog(TAG, "Live effect state differs from saved switch, re-applying")
                applySavedState()
            }
        } catch (e: Throwable) {
            DolbyConstants.dlog(TAG, "ensureLiveState failed: ${e.message}")
        }
    }

    fun setDolbyEnabledFromUser(enabled: Boolean) {
        setDolbyEnabled(enabled)
        DolbyEffectService.startFromUser(context)
    }

    fun registerEnabledListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        defaultPrefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterEnabledListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        defaultPrefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun getCurrentProfile(): Int {
        if (!getSavedDolbyEnabled() || getSoundEngineMode() != SoundEngineMode.DOLBY_ATMOS) {
            return readSavedProfile() ?: 0
        }
        return try {
            checkEffect()
            val saved = readSavedProfile()
            if (saved != null && dolbyEffect.profile != saved) {
                runCatching { dolbyEffect.profile = saved }
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
            if (!enabled || getSoundEngineMode() != SoundEngineMode.DOLBY_ATMOS) {
                DolbyConstants.dlog(TAG, "Profile saved (Dolby inactive): $profile")
                return
            }
            checkEffect()
            runCatching { dolbyEffect.profile = profile }
            if (dolbyEffect.profile != profile) {
                recreateDolbyEffect()
                runCatching { dolbyEffect.profile = profile }
            }
            applyProfileSettings(profile)
            applyViper(profile, forceRebind = true)
            val asserted = runCatching {
                dolbyEffect.dsOn = true
                dolbyEffect.dsOn
            }.getOrDefault(false)
            if (!asserted) {
                recreateDolbyEffect()
                runCatching { dolbyEffect.profile = profile }
                applyProfileSettings(profile)
                applyViper(profile, forceRebind = true)
                runCatching { dolbyEffect.dsOn = true }
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

    private fun readUserBaseGeq(profile: Int): IntArray {
        val prefs = getProfilePrefs(profile)
        val saved = prefs.getString(DolbyConstants.PREF_PRESET, null)
        if (saved != null) {
            val gains = saved.split(",").mapNotNull { it.toIntOrNull() }
            if (gains.size == 20) return gains.toIntArray()
        }
        return IntArray(20) { 0 }
    }

    @Synchronized
    private fun syncViperState(profile: Int, forceRebind: Boolean = false) {
        if (isReleased) return
        if (!applyViper(profile, forceRebind)) {
            // Native state is not what the UI/prefs say: let the service retry with backoff.
            runCatching { DolbyEffectService.start(context) }
        }
    }

    @Synchronized
    fun applyViper(profile: Int = readSavedProfile() ?: 0, forceRebind: Boolean = false): Boolean =
        applyViperInternal(profile, forceRebind, canRetry = true)

    private fun writeDap(param: DsParam, values: IntArray, profile: Int): Boolean = try {
        dolbyEffect.setDapParameter(param, values, profile)
        true
    } catch (t: Throwable) {
        android.util.Log.w(TAG, "DAP write failed param=$param profile=$profile: ${t.message}")
        false
    }

    private fun syncVirtualizer(profile: Int, virtOn: Boolean, wideningAmt: Int, force: Boolean): Boolean {
        fun writeAll(f: Boolean): Boolean {
            var ok = true
            val flag = intArrayOf(if (virtOn) 1 else 0)
            if (f || lastAppliedHpVirt != virtOn) {
                if (writeDap(DsParam.HEADPHONE_VIRTUALIZER, flag, profile)) lastAppliedHpVirt = virtOn
                else { lastAppliedHpVirt = null; ok = false }
            }
            if (f || lastAppliedSpkVirt != virtOn) {
                if (writeDap(DsParam.SPEAKER_VIRTUALIZER, flag, profile)) lastAppliedSpkVirt = virtOn
                else { lastAppliedSpkVirt = null; ok = false }
            }
            if (stereoWideningSupported && (f || lastAppliedWidening != wideningAmt)) {
                if (writeDap(DsParam.STEREO_WIDENING_AMOUNT, intArrayOf(wideningAmt), profile)) lastAppliedWidening = wideningAmt
                else { lastAppliedWidening = null; ok = false }
            }
            return ok
        }

        // null = native readback unavailable, trust the write status
        fun verified(): Boolean? = try {
            val hp = dolbyEffect.getDapParameterBool(DsParam.HEADPHONE_VIRTUALIZER, profile)
            val spk = dolbyEffect.getDapParameterBool(DsParam.SPEAKER_VIRTUALIZER, profile)
            if (stereoWideningSupported) {
                val w = dolbyEffect.getDapParameterInt(DsParam.STEREO_WIDENING_AMOUNT, profile)
                if (w != wideningAmt) {
                    DolbyConstants.dlog(TAG, "DAP widening readback $w != written $wideningAmt (may be clamped)")
                }
            }
            hp == virtOn && spk == virtOn
        } catch (_: Throwable) {
            null
        }

        var ok = writeAll(force)
        if (ok && verified() == false) {
            android.util.Log.w(TAG, "DAP virtualizer readback mismatch (want $virtOn), rewriting")
            lastAppliedHpVirt = null; lastAppliedSpkVirt = null; lastAppliedWidening = null
            ok = writeAll(true) && verified() != false
        }
        return ok
    }

    private fun applyViperInternal(profile: Int, forceRebind: Boolean, canRetry: Boolean): Boolean {
        if (isReleased) return true
        if (!getSavedDolbyEnabled() || getSoundEngineMode() != SoundEngineMode.DOLBY_ATMOS) return true
        return try {
            checkEffect()
            val force = forceRebind || lastAppliedProfile != profile
            if (dolbyEffect.profile != profile) {
                runCatching { dolbyEffect.profile = profile }
            }
            var allOk = true
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

            val clampedCurve = curve.coerceIn(0, 2)
            val targetQcomBass = ViperEffectModel.computeQcomBassStrength(bassLevel, clampedCurve, dynamicLevel)
            val targetQcomVirt = ViperEffectModel.computeQcomVirtualizerStrength(surroundOn, surroundWidening, surroundDepth)
            val qcomResult = qcomEffects.apply(targetQcomBass, targetQcomVirt, force = force)
            // Failing to enable the QCOM proxy is a soft fallback (GEQ only); failing to turn it OFF is not.
            if (!qcomResult.virtStateOk) {
                android.util.Log.w(TAG, "QCOM virtualizer could not be confirmed off")
                allOk = false
            }

            val baseGeq = readUserBaseGeq(profile)
            val effectiveGeq = ViperEffectModel.computeEffectiveGeq(
                baseGeq = baseGeq,
                freqs = DAX_GEQ_FREQUENCIES,
                bassLevel = bassLevel,
                bassCurve = clampedCurve,
                bassCutoffHz = bassCutoff,
                dynamicLevel = dynamicLevel,
                clarityLevel = trebleLevel,
                clarityMode = clarityMode,
                speakerLevel = speakerLevel,
                surroundEnabled = surroundOn,
                surroundWidening = surroundWidening,
                surroundDepth = surroundDepth,
                midLevel = midLevel,
                qcomBassStrength = qcomResult.appliedBassStrength,
                qcomVirtActive = qcomResult.virtActive,
            )
            val clampedGeq = IntArray(20) { idx -> effectiveGeq.getOrElse(idx) { 0 }.coerceIn(-150, 150) }
            if (force || lastAppliedGeq == null || !lastAppliedGeq!!.contentEquals(clampedGeq)) {
                if (writeDap(DsParam.GEQ_BAND_GAINS, clampedGeq, profile)) {
                    lastAppliedGeq = clampedGeq.copyOf()
                } else {
                    lastAppliedGeq = null
                    allOk = false
                }
            }

            val bassEnhancerOn = bassLevel > 0 || dynamicLevel > 0 || prefs.getBoolean(DolbyConstants.PREF_BASS, false)
            if (force || lastAppliedBassEnhancer != bassEnhancerOn) {
                if (writeDap(DsParam.BASS_ENHANCER_ENABLE, intArrayOf(if (bassEnhancerOn) 1 else 0), profile)) {
                    lastAppliedBassEnhancer = bassEnhancerOn
                } else {
                    lastAppliedBassEnhancer = null
                    allOk = false
                }
            }

            val wideningAmt = if (surroundOn) {
                (4 + (surroundWidening * 42 / 100) + (surroundDepth * 18 / 100)).coerceIn(4, 64)
            } else {
                0
            }
            if (!syncVirtualizer(profile, surroundOn, wideningAmt, force)) allOk = false

            if (allOk) {
                lastAppliedProfile = profile
                true
            } else if (canRetry) {
                // Control was likely lost or the native object died mid-write: rebuild and redo it once.
                android.util.Log.w(TAG, "applyViper had failed writes, recreating DAP effect and retrying")
                invalidateDapCache()
                runCatching {
                    recreateDolbyEffect()
                    dolbyEffect.profile = profile
                    dolbyEffect.dsOn = true
                }
                applyViperInternal(profile, forceRebind = true, canRetry = false)
            } else {
                lastAppliedProfile = -1
                false
            }
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
        if (!isDolbyActive()) return false
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
            syncViperState(profile, forceRebind = true)
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
        val clamped = curve.coerceIn(0, 2)
        getProfilePrefs(profile).edit().putInt(DolbyConstants.PREF_BASS_CURVE, clamped).apply()
        syncViperState(profile, forceRebind = false)
        notifyStateUpdated()
    }

    fun getBassCutoff(profile: Int): Int {
        return getProfilePrefs(profile).getInt(DolbyConstants.PREF_BASS_CUTOFF, 60)
    }

    @Synchronized
    fun setBassCutoff(profile: Int, hz: Int) {
        if (isReleased) return
        getProfilePrefs(profile).edit().putInt(DolbyConstants.PREF_BASS_CUTOFF, hz.coerceIn(15, 150)).apply()
        syncViperState(profile, forceRebind = false)
        notifyStateUpdated()
    }

    @Synchronized
    fun setBassLevel(profile: Int, level: Int) {
        if (isReleased) return
        if (level !in 0..100) {
            throw IllegalArgumentException("Bass level must be between 0 and 100")
        }
        getProfilePrefs(profile).edit().apply {
            putInt(DolbyConstants.PREF_BASS_LEVEL, level)
            putBoolean(DolbyConstants.PREF_BASS, level > 0)
            if (level > 0) putInt(DolbyConstants.PREF_BASS_LAST_LEVEL, level)
            apply()
        }
        syncViperState(profile, forceRebind = false)
        notifyStateUpdated()
    }

    fun getSpeakerCorrection(profile: Int): Int {
        return getProfilePrefs(profile).getInt(DolbyConstants.PREF_SPEAKER_CORRECTION, 0)
    }

    @Synchronized
    fun setSpeakerCorrection(profile: Int, level: Int) {
        if (isReleased) return
        val newLevel = level.coerceIn(0, 100)
        getProfilePrefs(profile).edit().apply {
            putInt(DolbyConstants.PREF_SPEAKER_CORRECTION, newLevel)
            if (newLevel > 0) putInt(DolbyConstants.PREF_SPEAKER_CORRECTION_LAST_LEVEL, newLevel)
            apply()
        }
        syncViperState(profile, forceRebind = false)
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
        syncViperState(profile, forceRebind = false)
        notifyStateUpdated()
    }

    @Synchronized
    fun setTrebleLevel(profile: Int, level: Int) {
        if (isReleased) return
        if (level !in 0..100) {
            throw IllegalArgumentException("Treble level must be between 0 and 100")
        }
        getProfilePrefs(profile).edit().apply {
            putInt(DolbyConstants.PREF_TREBLE_LEVEL, level)
            putBoolean(DolbyConstants.PREF_TREBLE, level > 0)
            if (level > 0) putInt(DolbyConstants.PREF_TREBLE_LAST_LEVEL, level)
            apply()
        }
        syncViperState(profile, forceRebind = false)
        notifyStateUpdated()
    }

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
        getProfilePrefs(profile).edit().apply {
            putInt(DolbyConstants.PREF_DYNAMIC_LEVEL, level)
            if (level > 0) putInt(DolbyConstants.PREF_DYNAMIC_LAST_LEVEL, level)
            apply()
        }
        syncViperState(profile, forceRebind = false)
        notifyStateUpdated()
    }

    fun getViperSurroundEnabled(profile: Int): Boolean {
        return getProfilePrefs(profile).getBoolean(DolbyConstants.PREF_VIPER_SURROUND, false)
    }

    @Synchronized
    fun setViperSurroundEnabled(profile: Int, enabled: Boolean) {
        if (isReleased) return
        getProfilePrefs(profile).edit()
            .putBoolean(DolbyConstants.PREF_VIPER_SURROUND, enabled)
            // Keep the legacy per-route flags in step so snapshots/readers never see a stale "on".
            .putBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, enabled)
            .putBoolean(DolbyConstants.PREF_SPK_VIRTUALIZER, enabled)
            .apply()
        syncViperState(profile, forceRebind = true)
        notifyStateUpdated()
    }

    fun getViperSurroundWidening(profile: Int): Int {
        return getProfilePrefs(profile).getInt(DolbyConstants.PREF_VIPER_SURROUND_WIDENING, 50).coerceIn(1, 100)
    }

    @Synchronized
    fun setViperSurroundWidening(profile: Int, widening: Int) {
        if (isReleased) return
        getProfilePrefs(profile).edit().putInt(DolbyConstants.PREF_VIPER_SURROUND_WIDENING, widening.coerceIn(1, 100)).apply()
        syncViperState(profile, forceRebind = false)
        notifyStateUpdated()
    }

    fun getViperSurroundDepth(profile: Int): Int {
        return getProfilePrefs(profile).getInt(DolbyConstants.PREF_VIPER_SURROUND_DEPTH, 35).coerceIn(0, 100)
    }

    @Synchronized
    fun setViperSurroundDepth(profile: Int, depth: Int) {
        if (isReleased) return
        getProfilePrefs(profile).edit().putInt(DolbyConstants.PREF_VIPER_SURROUND_DEPTH, depth.coerceIn(0, 100)).apply()
        syncViperState(profile, forceRebind = false)
        notifyStateUpdated()
    }

    fun getVolumeLevelerEnabled(profile: Int): Boolean {
        if (!volumeLevelerSupported) return false
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_VOLUME)) {
            return prefs.getBoolean(DolbyConstants.PREF_VOLUME, false)
        }
        if (!isDolbyActive()) return false
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
            if (getSavedDolbyEnabled() && getSoundEngineMode() == SoundEngineMode.DOLBY_ATMOS) {
                checkEffect()
                dolbyEffect.setDapParameter(DsParam.VOLUME_LEVELER_ENABLE, enabled, profile)
            }
            notifyStateUpdated()
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting volume leveler: ${e.message}")
        }
    }

    fun getIeqPreset(profile: Int): Int {
        val saved = getProfilePrefs(profile).getString(DolbyConstants.PREF_IEQ, null)?.toIntOrNull()
        if (saved != null) return saved
        if (!getSavedDolbyEnabled() || getSoundEngineMode() != SoundEngineMode.DOLBY_ATMOS) return 0
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
            if (getSavedDolbyEnabled() && getSoundEngineMode() == SoundEngineMode.DOLBY_ATMOS) {
                checkEffect()
                dolbyEffect.setDapParameter(DsParam.IEQ_PRESET, preset, profile)
                applyViper(profile, forceRebind = true)
                if (dolbyEffect.profile == profile) {
                    runCatching { dolbyEffect.dsOn = true }
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
        if (!isDolbyActive()) return false
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
            syncViperState(profile, forceRebind = true)
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
        if (!isDolbyActive()) return false
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
            syncViperState(profile, forceRebind = true)
            notifyStateUpdated()
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting speaker virtualizer: ${e.message}")
        }
    }

    fun getStereoWideningAmount(profile: Int): Int {
        if (!stereoWideningSupported) return 0
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_STEREO_WIDENING)) return prefs.getInt(DolbyConstants.PREF_STEREO_WIDENING, 32)
        if (!getSavedDolbyEnabled() || getSoundEngineMode() != SoundEngineMode.DOLBY_ATMOS) return 32
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
            syncViperState(profile, forceRebind = false)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting stereo widening: ${e.message}")
        }
    }

    fun getDialogueEnhancerEnabled(profile: Int): Boolean {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_DIALOGUE)) {
            return prefs.getBoolean(DolbyConstants.PREF_DIALOGUE, false)
        }
        if (!isDolbyActive()) return false
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
            if (getSavedDolbyEnabled() && getSoundEngineMode() == SoundEngineMode.DOLBY_ATMOS) {
                checkEffect()
                dolbyEffect.setDapParameter(DsParam.DIALOGUE_ENHANCER_ENABLE, enabled, profile)
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting dialogue enhancer: ${e.message}")
        }
    }

    fun getDialogueEnhancerAmount(profile: Int): Int {
        val prefs = getProfilePrefs(profile)
        if (prefs.contains(DolbyConstants.PREF_DIALOGUE_AMOUNT)) return prefs.getInt(DolbyConstants.PREF_DIALOGUE_AMOUNT, 6)
        if (!getSavedDolbyEnabled() || getSoundEngineMode() != SoundEngineMode.DOLBY_ATMOS) return 6
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
            if (getSavedDolbyEnabled() && getSoundEngineMode() == SoundEngineMode.DOLBY_ATMOS) {
                checkEffect()
                dolbyEffect.setDapParameter(DsParam.DIALOGUE_ENHANCER_AMOUNT, amount, profile)
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting dialogue enhancer amount: ${e.message}")
        }
    }

    fun getEqualizerGains(profile: Int, bandMode: BandMode): List<BandGain> {
        return try {
            if (getSoundEngineMode() == SoundEngineMode.XIAOMI_SOUND) {
                val gains10 = readMiSoundScenarioGeq(getMiSoundScenario())
                return MISOUND_BAND_FREQUENCIES_10.mapIndexed { idx, freq ->
                    BandGain(frequency = freq, gain = gains10.getOrElse(idx) { 0 })
                }
            }
            deserializeGains(readUserBaseGeq(profile), bandMode)
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error getting equalizer gains: ${e.message}")
            if (getSoundEngineMode() == SoundEngineMode.XIAOMI_SOUND) {
                return MISOUND_BAND_FREQUENCIES_10.map { BandGain(frequency = it, gain = 0) }
            }
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
            if (getSoundEngineMode() == SoundEngineMode.XIAOMI_SOUND) {
                val gains10 = extractMiSound10BandGains(bandGains, bandMode)
                val scenario = getMiSoundScenario()
                getScenarioPrefs(scenario).edit()
                    .putString(DolbyConstants.PREF_MISOUND_EQ_GAINS, gains10.joinToString(","))
                    .apply()
                if (getSavedDolbyEnabled()) {
                    if (miSoundEffect.isEnabled) {
                        miSoundEffect.setEqGains(gains10, force = true)
                    } else {
                        applyXiaomiSoundTuning(forceRebind = true)
                    }
                }
            } else {
                val gains = serializeGains(bandGains, bandMode)
                val gainsString = gains.joinToString(",")
                getProfilePrefs(profile).edit().putString(DolbyConstants.PREF_PRESET, gainsString).apply()
                if (getSavedDolbyEnabled()) {
                    applyViper(profile, forceRebind = false)
                }
            }
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error setting equalizer gains: ${e.message}")
        }
    }

    private fun extractMiSound10BandGains(bandGains: List<BandGain>, bandMode: BandMode): IntArray {
        val gains10 = when {
            bandGains.size == DolbyConstants.MISOUND_EQ_BAND_COUNT ->
                IntArray(DolbyConstants.MISOUND_EQ_BAND_COUNT) { idx -> bandGains[idx].gain }
            bandGains.size == 20 ->
                IntArray(DolbyConstants.MISOUND_EQ_BAND_COUNT) { idx -> bandGains[idx * 2].gain }
            else -> {
                val gains20 = serializeGains(bandGains, bandMode)
                IntArray(DolbyConstants.MISOUND_EQ_BAND_COUNT) { idx -> gains20[idx * 2] }
            }
        }
        for (i in gains10.indices) {
            gains10[i] = gains10[i].coerceIn(
                DolbyConstants.MISOUND_EQ_MIN_TENTHS,
                DolbyConstants.MISOUND_EQ_MAX_TENTHS
            )
        }
        return gains10
    }

    fun getPresetName(profile: Int): String {
        return try {
            val tenBandGains = if (getSoundEngineMode() == SoundEngineMode.XIAOMI_SOUND) {
                readMiSoundScenarioGeq(getMiSoundScenario())
            } else {
                readUserBaseGeq(profile).filterIndexed { index, _ -> index % 2 == 0 }.toIntArray()
            }
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
            if (getSavedDolbyEnabled() && getSoundEngineMode() == SoundEngineMode.DOLBY_ATMOS) {
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
            if (getSoundEngineMode() == SoundEngineMode.XIAOMI_SOUND) {
                for (s in 0..2) {
                    context.deleteSharedPreferences("${DolbyConstants.PREF_MISOUND_SCENARIO_PREFIX}$s")
                }
                defaultPrefs.edit().putInt(DolbyConstants.PREF_MISOUND_SCENARIO, 0).apply()
                _miSoundScenario.value = 0
                if (getSavedDolbyEnabled()) {
                    applyXiaomiSoundTuning(forceRebind = true)
                } else {
                    pushMiSoundParams(enabled = false, scenario = 0)
                }
                notifyStateUpdated()
                return
            }

            if (getSavedDolbyEnabled()) {
                checkEffect()
            }
            context.resources.getStringArray(R.array.dolby_profile_values)
                .map { it.toInt() }
                .forEach { resetProfile(it) }
            setCurrentProfile(0)
            notifyStateUpdated()
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
        getProfilePrefs(profile).edit().apply {
            putInt(DolbyConstants.PREF_MID_LEVEL, level)
            putBoolean(DolbyConstants.PREF_MID, level > 0)
            if (level > 0) putInt(DolbyConstants.PREF_MID_LAST_LEVEL, level)
            apply()
        }
        syncViperState(profile, forceRebind = false)
        notifyStateUpdated()
    }


    private fun getLastNonZeroLevel(
        profile: Int,
        currentKey: String,
        lastKey: String,
        defaultLevel: Int
    ): Int {
        val prefs = getProfilePrefs(profile)
        val current = prefs.getInt(currentKey, 0)
        if (current > 0) return current.coerceIn(1, 100)
        val saved = prefs.getInt(lastKey, 0)
        return if (saved > 0) saved.coerceIn(1, 100) else defaultLevel
    }

    fun getLastBassLevel(profile: Int): Int =
        getLastNonZeroLevel(profile, DolbyConstants.PREF_BASS_LEVEL, DolbyConstants.PREF_BASS_LAST_LEVEL, 50)

    fun getLastSpeakerCorrection(profile: Int): Int =
        getLastNonZeroLevel(profile, DolbyConstants.PREF_SPEAKER_CORRECTION, DolbyConstants.PREF_SPEAKER_CORRECTION_LAST_LEVEL, 50)

    fun getLastTrebleLevel(profile: Int): Int =
        getLastNonZeroLevel(profile, DolbyConstants.PREF_TREBLE_LEVEL, DolbyConstants.PREF_TREBLE_LAST_LEVEL, 30)

    fun getLastDynamicLevel(profile: Int): Int =
        getLastNonZeroLevel(profile, DolbyConstants.PREF_DYNAMIC_LEVEL, DolbyConstants.PREF_DYNAMIC_LAST_LEVEL, 45)

    fun getLastMidLevel(profile: Int): Int =
        getLastNonZeroLevel(profile, DolbyConstants.PREF_MID_LEVEL, DolbyConstants.PREF_MID_LAST_LEVEL, 40)

    override fun close() {
    }

    companion object {
        @Volatile
        private var instance: DolbyRepository? = null

        fun get(context: Context): DolbyRepository =
            instance ?: synchronized(this) {
                instance ?: DolbyRepository(context).also { instance = it }
            }

        private const val TAG = "DolbyRepository"
        private const val EFFECT_PRIORITY = 100
        private const val MISOUND_EFFECT_PRIORITY = 100
        private const val MISOUND_RECOVERY_DELAY_MS = 300L
        private const val MISOUND_RECOVERY_MAX_ATTEMPTS = 5

        private val ATTRIBUTES_MEDIA = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        val BAND_FREQUENCIES_10 = listOf(32, 64, 125, 250, 500, 1000, 2250, 5000, 10000, 19688)

        val MISOUND_BAND_FREQUENCIES_10 = listOf(
            32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000
        )

        val BAND_FREQUENCIES_15 = listOf(
            32, 47, 94, 141, 234, 469, 844, 1313, 2250, 3750, 5813, 9000, 11250, 13875, 19688
        )

        val DAX_GEQ_FREQUENCIES = listOf(
            47, 141, 234, 328, 469, 656, 844, 1031, 1313, 1688,
            2250, 3000, 3750, 4688, 5813, 7125, 9000, 11250, 13875, 19688
        )

        val BAND_FREQUENCIES_20 = DAX_GEQ_FREQUENCIES

        private val TEN_BAND_INDICES = listOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18)

        private val FIFTEEN_BAND_INDICES = listOf(0, 1, 2, 3, 4, 5, 6, 8, 11, 12, 14, 15, 17, 18, 19)
    }
}

private object SharedDolbyEffect {
    private var effect: DolbyAudioEffect? = null

    @Synchronized
    fun get(create: () -> DolbyAudioEffect): DolbyAudioEffect =
        effect ?: create().also { effect = it }

    @Synchronized
    fun peek(): DolbyAudioEffect? = effect

    @Synchronized
    fun drop() {
        try {
            effect?.release()
        } catch (_: Exception) {
        }
        effect = null
    }
}