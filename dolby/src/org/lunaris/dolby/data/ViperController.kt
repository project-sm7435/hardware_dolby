/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import android.content.Context
import android.media.audiofx.AudioEffect
import android.os.SystemClock
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.audio.ViperAudioEffect
import kotlin.math.roundToInt

/**
 * Runs bass, clarity and Dynamic system in the real ViPER FX effect instead of writing them into
 * the Dolby equalizer bands, so the equalizer stays exactly as the user set it.
 *
 * There is one controller per process, so the app, the QS tile and the effect service all share one
 * effect handle and never fight each other for control of it.
 */
class ViperController private constructor() {

    /** What ViPER should do. Gains are the 0-100 slider values of the Dolby app. */
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
    ) {
        val anyOn: Boolean get() = bassOn || clarityOn || dynamicOn
    }

    private var effect: ViperAudioEffect? = null
    private var nextTryAtMs = 0L

    /** True when the ViPER effect exists in this ROM. If not, the app keeps using the old method. */
    @Synchronized
    fun isAvailable(): Boolean = obtain() != null

    /** Pushes [settings] to ViPER. [master] is the Dolby on/off switch: off turns ViPER off too. */
    @Synchronized
    fun apply(settings: Settings, master: Boolean): Boolean {
        repeat(2) {
            val e = obtain() ?: return false
            val ok = try {
                push(e, settings, master)
            } catch (ex: Exception) {
                DolbyConstants.dlog(TAG, "ViPER push failed: ${ex.message}")
                false
            }
            if (ok) return true
            // Probably another app took the control. Take a fresh handle and try once more.
            drop()
        }
        return false
    }

    private fun push(e: ViperAudioEffect, s: Settings, master: Boolean): Boolean {
        val bassGainValue = (s.bassGain * ViperEffectModel.MAX_BASS_FACTOR).roundToInt()
        val clarityGainValue = (s.clarityGain * ViperEffectModel.MAX_CLARITY_GAIN).roundToInt()
        return e.send(PARAM_BASS_MODE, s.bassMode.coerceIn(0, 2)) &&
            e.send(PARAM_BASS_FREQUENCY, s.bassFrequency.coerceIn(15, 150)) &&
            e.send(PARAM_BASS_GAIN, bassGainValue) &&
            e.send(PARAM_BASS_ENABLE, if (master && s.bassOn) 1 else 0) &&
            e.send(PARAM_CLARITY_MODE, s.clarityMode.coerceIn(0, 2)) &&
            e.send(PARAM_CLARITY_GAIN, clarityGainValue) &&
            e.send(PARAM_CLARITY_ENABLE, if (master && s.clarityOn) 1 else 0) &&
            e.send(PARAM_DYNAMIC_STRENGTH, s.dynamicStrength) &&
            e.send(PARAM_DYNAMIC_ENABLE, if (master && s.dynamicOn) 1 else 0) &&
            e.setEnabled(master && s.anyOn) == AudioEffect.SUCCESS
    }

    private fun obtain(): ViperAudioEffect? {
        effect?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (now < nextTryAtMs) return null
        return try {
            ViperAudioEffect(EFFECT_PRIORITY, 0).also {
                effect = it
                DolbyConstants.dlog(TAG, "ViPER effect created")
            }
        } catch (e: Exception) {
            // Not installed (or not ready yet). Look again in a few seconds, not on every slider tick.
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
        private const val RETRY_DELAY_MS = 10_000L

        // Command ids, from the comments in ViPER::DispatchCommand
        private const val PARAM_DYNAMIC_ENABLE = 0x10021
        private const val PARAM_DYNAMIC_STRENGTH = 0x10025
        private const val PARAM_BASS_ENABLE = 0x10026
        private const val PARAM_BASS_MODE = 0x10027
        private const val PARAM_BASS_FREQUENCY = 0x10028
        private const val PARAM_BASS_GAIN = 0x10029
        private const val PARAM_CLARITY_ENABLE = 0x1002A
        private const val PARAM_CLARITY_MODE = 0x1002B
        private const val PARAM_CLARITY_GAIN = 0x1002C

        @Volatile
        private var instance: ViperController? = null

        fun get(@Suppress("UNUSED_PARAMETER") context: Context): ViperController =
            instance ?: synchronized(this) {
                instance ?: ViperController().also { instance = it }
            }
    }
}
