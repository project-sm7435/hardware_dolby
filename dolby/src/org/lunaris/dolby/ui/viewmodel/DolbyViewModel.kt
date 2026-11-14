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

    private val repository = DolbyRepository(application)

    private val _uiState = MutableStateFlow<DolbyUiState>(DolbyUiState.Loading)
    val uiState: StateFlow<DolbyUiState> = _uiState.asStateFlow()
    val currentProfile: StateFlow<Int> = repository.currentProfile
    
    private var speakerStateJob: Job? = null
    private var profileChangeJob: Job? = null
    @Volatile private var isCleared = false

    private val effectOps = LinkedHashMap<String, suspend () -> Unit>()
    private val effectLock = Any()
    private val effectWake = Channel<Unit>(Channel.CONFLATED)
    private var loadJob: Job? = null
    @Volatile private var stateMutationGen = 0L

    // Reloads the card when the master switch is flipped somewhere else (the QS tile).
    private val enabledListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DolbyConstants.PREF_ENABLE && !isCleared) loadSettings()
    }

    init {
        DolbyConstants.dlog(TAG, "ViewModel initialized")
        repository.registerEnabledListener(enabledListener)
        loadSettings()
        startEffectWorker()
        observeSpeakerState()
        observeProfileChanges()
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
            repository.isOnSpeaker.collect { 
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
            repository.currentProfile.collect {
                if (!isCleared) {
                    DolbyConstants.dlog(TAG, "Profile changed to: $it")
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
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val enabled = repository.getSavedDolbyEnabled()
                val profile = repository.getCurrentProfile()
                val bandMode = repository.getBandMode()
                
                val settings = DolbySettings(
                    enabled = enabled,
                    currentProfile = profile,
                    bassEnhancerEnabled = repository.getBassEnhancerEnabled(profile),
                    volumeLevelerEnabled = repository.getVolumeLevelerEnabled(profile),
                    bandMode = bandMode
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
                    dseeEnabled = repository.getDseeEnabled(profile)
                )
                
                if (!isCleared && genAtStart == stateMutationGen) {
                    _uiState.value = DolbyUiState.Success(
                        settings = settings,
                        profileSettings = profileSettings,
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
                loadSettings()
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profile = repository.getCurrentProfile()
                repository.setBassEnhancerEnabled(profile, enabled)
                loadSettings()
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
                loadSettings()
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
                loadSettings()
            } catch (e: IllegalArgumentException) {
                DolbyConstants.dlog(TAG, "Invalid bass level: ${e.message}")
                _uiState.value = DolbyUiState.Error("Invalid bass level: ${e.message}")
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting bass level: ${e.message}")
                _uiState.value = DolbyUiState.Error("Failed to set bass level")
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
                loadSettings()
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
                loadSettings()
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
                loadSettings()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting speaker correction: ${e.message}")
            }
        }
    }


    fun setDseeEnabled(enabled: Boolean) {
        stateMutationGen++
        val current = _uiState.value
        if (current is DolbyUiState.Success) {
            _uiState.value = current.copy(
                profileSettings = current.profileSettings.copy(dseeEnabled = enabled)
            )
        }
        submitEffectOp("setDseeEnabled") {
            try {
                val profile = repository.getCurrentProfile()
                repository.setDseeEnabled(profile, enabled)
                loadSettings()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting DSEE: ${e.message}")
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
                loadSettings()
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
                loadSettings()
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
                loadSettings()
            } catch (e: IllegalArgumentException) {
                DolbyConstants.dlog(TAG, "Invalid mid level: ${e.message}")
                _uiState.value = DolbyUiState.Error("Invalid mid level: ${e.message}")
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting mid level: ${e.message}")
                _uiState.value = DolbyUiState.Error("Failed to set mid level")
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
                loadSettings()
            } catch (e: IllegalArgumentException) {
                DolbyConstants.dlog(TAG, "Invalid treble level: ${e.message}")
                _uiState.value = DolbyUiState.Error("Invalid treble level: ${e.message}")
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting treble level: ${e.message}")
                _uiState.value = DolbyUiState.Error("Failed to set treble level")
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
                loadSettings()
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
                loadSettings()
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
                loadSettings()
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
                loadSettings()
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
                loadSettings()
            } catch (e: Exception) {
                DolbyConstants.dlog(TAG, "Error setting stereo widening: ${e.message}")
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
                loadSettings()
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
                loadSettings()
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
        repository.close()
        super.onCleared()
    }
    
    companion object {
        private const val TAG = "DolbyViewModel"
    }
}
