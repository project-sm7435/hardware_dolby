/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import android.content.Context
import android.media.audiofx.AudioEffect
import android.os.SystemClock
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.audio.ViperAudioEffect

/**
 * Runs ViPERBass, ViPERClarity, DynamicSystem, and SpeakerCorrection in the real ViPER DSP engine
 * (libv4a_re.so) via ViperEffectModel instead of baking curves into Dolby's graphic equalizer.
 */
class ViperController private constructor() {

    data class Settings(
        val bassOn: Boolean,
        val bassMode: Int,
        val bassFrequency: Int,
        val bassGain: Int,
        val clarityOn: Boolean,
        val clarityMode: Int,
        val clarityGain: Int,
        val dynamicOn: Boolean,
        val dynamicStrength: Int,
        val speakerCorrectionOn: Boolean = false,
        val speakerStrength: Int = 0,
    ) {
        val anyOn: Boolean get() = bassOn || clarityOn || dynamicOn || speakerCorrectionOn
    }

    private var effect: ViperAudioEffect? = null
    private var nextTryAtMs = 0L

    @Synchronized
    fun isAvailable(): Boolean = obtain() != null

    @Synchronized
    fun apply(settings: Settings, master: Boolean, forceRebind: Boolean = false): Boolean {
        if (!(master && settings.anyOn)) {
            ViperEffectModel.applyToDspEngine(effect, settings, masterEnabled = false)
            if (effect != null) {
                try {
                    effect?.setEnabled(false)
                } catch (ex: Exception) {
                    DolbyConstants.dlog(TAG, "ViPER disable failed: ${ex.message}")
                }
                drop()
                DolbyConstants.dlog(TAG, "ViPER effect released (all rows off)")
            }
            return true
        }

        if (forceRebind && effect != null) {
            drop()
        }
        repeat(2) {
            val wasCreated = effect == null || effect?.isReady() != true
            val e = obtain() ?: return false
            val ok = try {
                if (wasCreated) {
                    // Prime AudioFlinger's effect chain on fresh creation so libv4a_re.so processes
                    // audio on the very first click without requiring an off/on toggle.
                    e.setEnabled(true)
                    ViperEffectModel.applyToDspEngine(e, settings, master)
                    e.setEnabled(false)
                    e.setEnabled(true)
                }
                val pushed = ViperEffectModel.applyToDspEngine(e, settings, master)
                val enabledOk = e.enabled || e.setEnabled(true) == AudioEffect.SUCCESS
                pushed && enabledOk
            } catch (ex: Exception) {
                DolbyConstants.dlog(TAG, "ViPER push failed: ${ex.message}")
                false
            }
            if (ok) return true
            drop()
        }
        return false
    }

    private fun obtain(): ViperAudioEffect? {
        effect?.let { existing ->
            if (existing.isReady()) return existing
            drop()
        }
        val now = SystemClock.elapsedRealtime()
        if (now < nextTryAtMs) return null
        return try {
            ViperAudioEffect(EFFECT_PRIORITY, 0).also {
                effect = it
                DolbyConstants.dlog(TAG, "ViPER effect created")
            }
        } catch (e: Exception) {
            nextTryAtMs = now + RETRY_DELAY_MS
            DolbyConstants.dlog(TAG, "ViPER effect not available: ${e.message}")
            null
        }
    }

    private fun drop() {
        try {
            effect?.release()
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error releasing ViPER effect: ${e.message}")
        }
        effect = null
    }

    companion object {
        private const val TAG = "ViperController"
        private const val EFFECT_PRIORITY = 100
        private const val RETRY_DELAY_MS = 2_000L

        @Volatile
        private var instance: ViperController? = null

        fun get(@Suppress("UNUSED_PARAMETER") context: Context): ViperController =
            instance ?: synchronized(this) {
                instance ?: ViperController().also { instance = it }
            }
    }
}
