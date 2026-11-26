/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.ui.viewmodel

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.SoundEngineMode
import org.lunaris.dolby.data.DolbyRepository
import org.lunaris.dolby.domain.models.*
import org.lunaris.dolby.service.DolbyEffectService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel

class DolbyViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DolbyRepository.get(application)

    private val _uiState = MutableStateFlow<DolbyUiState>(DolbyUiState.Loading)
    val uiState: StateFlow<DolbyUiState> = _uiState.asStateFlow()
    val currentProfile: StateFlow<Int> = repository.currentProfile
    
    private var speakerStateJob: Job? = null
    private var profileChangeJob: Job? = null
    private var miSoundScenarioJob: Job? = null
    @Volatile private var isCleared = false

    private val effectOps = LinkedHashMap<String, suspend () -> Unit>()
    private val effectLock = Any()
    private val effectWake = Channel<Unit>(Channel.CONFLATED)
    private var loadJob: Job? = null
    @Volatile private var stateMutationGen = 0L
    private val loadSeq = java.util.concurrent.atomic.AtomicLong()

    // Reloads the card when the master switch is flipped somewhere else (the QS tile).
    private val enabledListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!isCleared && (
                key == "device_snapshot_restored" ||
                key == DolbyConstants.PREF_ENABLE ||
                key == DolbyConstants.PREF_DOLBY_MASTER_ENABLE ||
                key == DolbyConstants.PREF_MISOUND_MASTER_ENABLE ||
                key == DolbyConstants.PREF_SOUND_ENGINE_MODE ||
                key == DolbyConstants.PREF_MISOUND_SCENARIO
            )
        ) {
            loadSettings()
        }
    }

    init {
        DolbyConstants.dlog(TAG, "ViewModel initialized")
        repository.registerEnabledListener(enabledListener)
        loadSettings()
        startEffectWorker()
        observeSpeakerState()
        observeProfileChanges()
        observeMiSoundScenarioChanges()
    }
    
    private fun startEffectWorker() {
        viewModelScope.launch(Dispatchers.IO) {
            for (wake in effectWake) {
                while (true) {
                    val op = synchronized(effectLock) {
                        val key = effectOps.keys.firstOrNull()
                        if (key == null) null else effectOps.remove(key)
                    } ?: break
                    try {
                        op()
                    } catch (e: Exception) {
                        DolbyConstants.dlog(TAG, "Effect request failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun submitEffectOp(key: String, op: suspend () -> Unit) {
        synchronized(effectLock) {
            effectOps.remove(key)
            effectOps[key] = op
        }
        effectWake.trySend(Unit)
    }

    private fun observeSpeakerState() {
        speakerStateJob?.cancel()
        speakerStateJob = viewModelScope.launch {
            repository.isOnSpeaker.drop(1).collect { 
                if (!isCleared) {
                    DolbyConstants.dlog(TAG, "Speaker state changed: $it")
                    loadSettings()
                }
            }
        }
    }
    
    private fun observeProfileChanges() {
        profileChangeJob?.cancel()
        profileChangeJob = viewModelScope.launch {
            repository.currentProfile.drop(1).collect {
                if (!isCleared) {
                    DolbyConstants.dlog(TAG, "Profile changed to: $it")
                    loadSettings()
                }
            }
        }
    }

    private fun observeMiSoundScenarioChanges() {
        miSoundScenarioJob?.cancel()
        miSoundScenarioJob = viewModelScope.launch {
            repository.miSoundScenario.drop(1).collect {
                if (!isCleared) {
                    DolbyConstants.dlog(TAG, "MiSound scenario changed to: $it")
                    loadSettings()
                }
            }
        }
    }

    fun loadSettings() {
        if (isCleared) {
            DolbyConstants.dlog(TAG, "ViewModel cleared, skipping loadSettings")
            return
        }
        
        loadJob?.cancel()
        val genAtStart = stateMutationGen
        val seq = loadSeq.incrementAndGet()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.ensureLiveState()
                val enabled = repository.getSavedDolbyEnabled()
                val soundEngineMode = repository.getSoundEngineMode()
                val profile = if (soundEngineMode == SoundEngineMode.DOLBY_ATMOS) {
                    repository.getCurrentProfile()
                } else {
                    repository.getSavedProfile()
                }
                val bandMode = repository.getBandMode()
                
                val settings = DolbySettings(
                    enabled = enabled,
                    soundEngineMode = soundEngineMode,
                    currentProfile = profile,
                    bassEnhancerEnabled = repository.getBassEnhancerEnabled(profile),
                    volumeLevelerEnabled = repository.getVolumeLevelerEnabled(profile),
                    bandMode = bandMode
                )

                val miSoundSettings = MiSoundSettings(
                    scenario = repository.getMiSoundScenario(),
                    surround3dEnabled = repository.getMiSound3dSurround(),
                    surroundLevel = repository.getMiSoundSurroundLevel(),
                    vocalLevel = repository.getMiSoundVocalLevel()
                )
                
                val profileSettings = ProfileSettings(
                    profile = profile,
                    ieqPreset = repository.getIeqPreset(profile),
                    headphoneVirtualizerEnabled = repository.getHeadphoneVirtualizerEnabled(profile),
                    speakerVirtualizerEnabled = repository.getSpeakerVirtualizerEnabled(profile),
                    stereoWideningAmount = repository.getStereoWideningAmount(profile),
                    dialogueEnhancerEnabled = repository.getDialogueEnhancerEnabled(profile),
                    dialogueEnhancerAmount = repository.getDialogueEnhancerAmount(profile),
                    bassLevel = repository.getBassLevel(profile),
                    midLevel = repository.getMidLevel(profile),
                    trebleLevel = repository.getTrebleLevel(profile),
                    bassCurve = repository.getBassCurve(profile),
                    clarityMode = repository.getClarityMode(profile),
                    bassCutoff = repository.getBassCutoff(profile),
                    speakerCorrection = repository.getSpeakerCorrection(profile),
                    dynamicLevel = repository.getDynamicLevel(profile),
                    viperSurroundEnabled = repository.getViperSurroundEnabled(profile),
                    viperSurroundWidening = repository.getViperSurroundWidening(profile),
                    viperSurroundDepth = repository.getViperSurroundDepth(profile)
                )
                
                // A cancelled load keeps running, so only the newest load may publish its result.
                if (!isCleared && genAtStart == stateMutationGen && seq == loadSeq.get()) {
                    _uiState.value = DolbyUiState.Success(
                        settings = settings,
                        profileSettings = profileSettings,
                        miSoundSettings = miSoundSettings,
                        currentPresetName = repository.getPresetName(profile),
                        isOnSpeaker = repository.isOnSpeaker.value
                    )
                }
            } catch (e: Exception) {
                if (!isCleared) {
                    DolbyConstants.dlog(TAG, "Error loading settings: ${e.message}")
                    _uiState.value = DolbyUiState.Error(e.message ?: "Unknown error")
                }
            }
        }
    }

    fun setSoundEngineMode(mode: SoundEngineMode) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                settings = current.settings.copy(soundEngineMode = mode)
            )
        }
        submitEffectOp("setSoundEngineMode") {
            try {
                repository.setSoundEngineMode(mode)
                loadSettings()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting sound engine mode: ${e.message}")
            }
        }
    }

    fun setMiSoundScenario(scenario: Int) {
        stateMutationGen++
        val clamped = scenario.coerceIn(0, 2)
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                miSoundSettings = MiSoundSettings(
                    scenario = clamped,
                    surround3dEnabled = repository.getMiSound3dSurround(clamped),
                    surroundLevel = repository.getMiSoundSurroundLevel(clamped),
                    vocalLevel = repository.getMiSoundVocalLevel(clamped)
                )
            )
        }
        submitEffectOp("setMiSoundScenario") {
            try {
                repository.setMiSoundScenario(clamped)
                loadSettings()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting MiSound scenario: ${e.message}")
            }
        }
    }

    fun setMiSound3dSurround(enabled: Boolean) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                miSoundSettings = current.miSoundSettings.copy(surround3dEnabled = enabled)
            )
        }
        submitEffectOp("setMiSound3dSurround") {
            try {
                repository.setMiSound3dSurround(enabled)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting MiSound 3D surround: ${e.message}")
            }
        }
    }

    fun setMiSoundSurroundLevel(level: Int) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                miSoundSettings = current.miSoundSettings.copy(surroundLevel = level.coerceIn(0, 100))
            )
        }
        submitEffectOp("setMiSoundSurroundLevel") {
            try {
                repository.setMiSoundSurroundLevel(level)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting MiSound surround level: ${e.message}")
            }
        }
    }

    fun setMiSoundVocalLevel(level: Int) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                miSoundSettings = current.miSoundSettings.copy(vocalLevel = level.coerceIn(0, 100))
            )
        }
        submitEffectOp("setMiSoundVocalLevel") {
            try {
                repository.setMiSoundVocalLevel(level)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting MiSound vocal level: ${e.message}")
            }
        }
    }

    fun setDolbyEnabled(enabled: Boolean) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                settings = current.settings.copy(enabled = enabled)
            )
        }
        submitEffectOp("setDolbyEnabled") {
            try {
                repository.setDolbyEnabledFromUser(enabled)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting Dolby enabled: ${e.message}")
            }
        }
    }

    fun setProfile(profile: Int) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                settings = current.settings.copy(currentProfile = profile)
            )
        }
        submitEffectOp("setProfile") {
            try {
                repository.setCurrentProfile(profile)
                loadSettings()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting profile: ${e.message}")
            }
        }
    }

    fun setBassEnhancer(enabled: Boolean) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                settings = current.settings.copy(bassEnhancerEnabled = enabled)
            )
        }
        submitEffectOp("setBassEnhancer") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setBassEnhancerEnabled(profile, enabled)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting bass enhancer: ${e.message}")
            }
        }
    }

    fun enableViperBass(level: Int) {
        stateMutationGen++
        val clamped = level.coerceIn(1, 100)
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            val fixedCurve = if (current.profileSettings.bassCurve == 3) 0 else current.profileSettings.bassCurve
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(
                    bassCurve = fixedCurve,
                    bassLevel = clamped
                )
            )
        }
        submitEffectOp("setBassLevel") {
            try {
                val profile = repository.getCurrentProfile()
                if (repository.getBassCurve(profile) == 3) {
                    repository.setBassCurve(profile, 0)
                }
                repository.setBassLevel(profile, clamped)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error enabling ViPER bass: ${e.message}")
            }
        }
    }

    fun setBassLevel(level: Int) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(bassLevel = level.coerceIn(0, 100))
            )
        }
        submitEffectOp("setBassLevel") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setBassLevel(profile, level)
            } catch (e: IllegalArgumentException) {
                DolbyConstants.dlog(TAG, "Invalid bass level: ${e.message}")
                loadSettings() // a failed change reloads the real state instead of blanking the screen
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting bass level: ${e.message}")
                loadSettings() // a failed change reloads the real state instead of blanking the screen
            }
        }
    }

    fun setBassCurve(curve: Int) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(bassCurve = curve)
            )
        }
        submitEffectOp("setBassCurve") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setBassCurve(profile, curve)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting bass curve: ${e.message}")
            }
        }
    }

    fun setBassCutoff(hz: Int) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(bassCutoff = hz.coerceIn(15, 150))
            )
        }
        submitEffectOp("setBassCutoff") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setBassCutoff(profile, hz)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting bass cutoff: ${e.message}")
            }
        }
    }

    fun setSpeakerCorrection(level: Int) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(speakerCorrection = level.coerceIn(0, 100))
            )
        }
        submitEffectOp("setSpeakerCorrection") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setSpeakerCorrection(profile, level)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting speaker correction: ${e.message}")
            }
        }
    }

    fun setDynamicLevel(level: Int) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(dynamicLevel = level.coerceIn(0, 100))
            )
        }
        submitEffectOp("setDynamicLevel") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setDynamicLevel(profile, level)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting dynamic system: ${e.message}")
            }
        }
    }

    fun setClarityMode(mode: Int) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(clarityMode = mode)
            )
        }
        submitEffectOp("setClarityMode") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setClarityMode(profile, mode)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting clarity mode: ${e.message}")
            }
        }
    }

    fun setMidLevel(level: Int) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(midLevel = level.coerceIn(0, 100))
            )
        }
        submitEffectOp("setMidLevel") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setMidLevel(profile, level)
            } catch (e: IllegalArgumentException) {
                DolbyConstants.dlog(TAG, "Invalid mid level: ${e.message}")
                loadSettings() // a failed change reloads the real state instead of blanking the screen
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting mid level: ${e.message}")
                loadSettings() // a failed change reloads the real state instead of blanking the screen
            }
        }
    }

    fun setTrebleLevel(level: Int) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(trebleLevel = level.coerceIn(0, 100))
            )
        }
        submitEffectOp("setTrebleLevel") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setTrebleLevel(profile, level)
            } catch (e: IllegalArgumentException) {
                DolbyConstants.dlog(TAG, "Invalid treble level: ${e.message}")
                loadSettings() // a failed change reloads the real state instead of blanking the screen
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting treble level: ${e.message}")
                loadSettings() // a failed change reloads the real state instead of blanking the screen
            }
        }
    }

    fun setVolumeLeveler(enabled: Boolean) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                settings = current.settings.copy(volumeLevelerEnabled = enabled)
            )
        }
        submitEffectOp("setVolumeLeveler") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setVolumeLevelerEnabled(profile, enabled)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting volume leveler: ${e.message}")
            }
        }
    }

    fun setIeqPreset(preset: Int) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(ieqPreset = preset)
            )
        }
        submitEffectOp("setIeqPreset") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setIeqPreset(profile, preset)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting IEQ preset: ${e.message}")
            }
        }
    }

    fun setHeadphoneVirtualizer(enabled: Boolean) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(headphoneVirtualizerEnabled = enabled)
            )
        }
        submitEffectOp("setHeadphoneVirtualizer") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setHeadphoneVirtualizerEnabled(profile, enabled)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting headphone virtualizer: ${e.message}")
            }
        }
    }

    fun setSpeakerVirtualizer(enabled: Boolean) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(speakerVirtualizerEnabled = enabled)
            )
        }
        submitEffectOp("setSpeakerVirtualizer") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setSpeakerVirtualizerEnabled(profile, enabled)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting speaker virtualizer: ${e.message}")
            }
        }
    }

    fun setStereoWidening(amount: Int) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(stereoWideningAmount = amount)
            )
        }
        submitEffectOp("setStereoWidening") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setStereoWideningAmount(profile, amount)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting stereo widening: ${e.message}")
            }
        }
    }

    fun setViperSurroundEnabled(enabled: Boolean) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(viperSurroundEnabled = enabled)
            )
        }
        submitEffectOp("setViperSurroundEnabled") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setViperSurroundEnabled(profile, enabled)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting 3D surround: ${e.message}")
            }
        }
    }

    fun setViperSurroundWidening(widening: Int) {
        stateMutationGen++
        val clamped = widening.coerceIn(1, 100)
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(viperSurroundWidening = clamped)
            )
        }
        submitEffectOp("setViperSurroundWidening") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setViperSurroundWidening(profile, clamped)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting 3D surround widening: ${e.message}")
            }
        }
    }

    fun setViperSurroundDepth(depth: Int) {
        stateMutationGen++
        val clamped = depth.coerceIn(0, 100)
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(viperSurroundDepth = clamped)
            )
        }
        submitEffectOp("setViperSurroundDepth") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setViperSurroundDepth(profile, clamped)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting 3D surround depth: ${e.message}")
            }
        }
    }

    fun setDialogueEnhancer(enabled: Boolean) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(dialogueEnhancerEnabled = enabled)
            )
        }
        submitEffectOp("setDialogueEnhancer") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setDialogueEnhancerEnabled(profile, enabled)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting dialogue enhancer: ${e.message}")
            }
        }
    }

    fun setDialogueEnhancerAmount(amount: Int) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(dialogueEnhancerAmount = amount)
            )
        }
        submitEffectOp("setDialogueEnhancerAmount") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setDialogueEnhancerAmount(profile, amount)
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting dialogue enhancer amount: ${e.message}")
            }
        }
    }

    fun resetAllProfiles() {
        stateMutationGen++
        submitEffectOp("resetAllProfiles") {
            try {
                repository.resetAllProfiles()
                loadSettings()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error resetting profiles: ${e.message}")
            }
        }
    }

    fun updateSpeakerState() {
        if (!isCleared) {
            viewModelScope.launch(Dispatchers.IO) {
                repository.updateSpeakerState()
            }
        }
    }
    
    override fun onCleared() {
        DolbyConstants.dlog(TAG, "ViewModel onCleared")
        isCleared = true
        repository.unregisterEnabledListener(enabledListener)
        viewModelScope.coroutineContext.cancelChildren()
        speakerStateJob?.cancel()
        speakerStateJob = null
        profileChangeJob?.cancel()
        profileChangeJob = null
        miSoundScenarioJob?.cancel()
        miSoundScenarioJob = null
        repository.close()
        super.onCleared()
    }
    
    companion object {
        private const val TAG = "DolbyViewModel"
    }
}
