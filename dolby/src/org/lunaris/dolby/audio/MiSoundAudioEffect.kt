/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.audio

import android.media.audiofx.AudioEffect
import android.os.SystemProperties
import android.util.Log
import org.lunaris.dolby.DolbyConstants

/**
 * Manages Xiaomi's MiSound Audio Effect (libmisoundfx.so, UUID 5b8e36a5-144a-4c38-b1d7-0002a5d5c51b)
 * on global output session 0. Pushes live parameters via AudioEffect.setParameter() and releases
 * its Qualcomm GEF session immediately when disabled so it never collides with Dolby DAP.
 */
class MiSoundAudioEffect(
    private val priority: Int = 100,
    private val audioSession: Int = 0
) {

    private var audioEffect: AudioEffect? = null
    private var propDisabled = false

    val isEnabled: Boolean
        get() = try {
            audioEffect?.enabled == true
        } catch (_: Throwable) {
            false
        }

    fun hasControl(): Boolean = try {
        audioEffect?.hasControl() == true
    } catch (_: Throwable) {
        false
    }

    private fun obtainEffect(): AudioEffect? {
        audioEffect?.let { existing ->
            if (hasControl()) return existing
            release()
        }
        return try {
            AudioEffect(
                AudioEffect.EFFECT_TYPE_NULL,
                DolbyConstants.EFFECT_UUID_MISOUND,
                priority,
                audioSession
            ).also { audioEffect = it }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to instantiate MiSoundAudioEffect (5b8e36a5-144a-4c38-b1d7-0002a5d5c51b)", t)
            audioEffect = null
            null
        }
    }

    @Synchronized
    fun applyParameters(
        scenario: Int,
        surround3dEnabled: Boolean,
        surroundLevel: Int,
        vocalLevel: Int
    ) {
        val clampedScenario = scenario.coerceIn(0, 2)
        val clampedSurround = surroundLevel.coerceIn(0, 100)
        val clampedVocal = vocalLevel.coerceIn(0, 100)

        setPropSafe(DolbyConstants.PROP_MISOUND_AUTO_SCENARIO, if (clampedScenario == 0) "1" else "0")
        setPropSafe(DolbyConstants.PROP_MISOUND_SCENARIO, clampedScenario.toString())
        setPropSafe(DolbyConstants.PROP_MISOUND_MUSIC_STATE, if (clampedScenario == 2) "0" else "1")
        setPropSafe(DolbyConstants.PROP_MISOUND_3D_SURROUND, if (surround3dEnabled) "1" else "0")
        setPropSafe(DolbyConstants.PROP_MISOUND_SURROUND_LEVEL, clampedSurround.toString())
        setPropSafe(DolbyConstants.PROP_MISOUND_VOCAL_LEVEL, clampedVocal.toString())

        audioEffect?.let { fx ->
            if (hasControl()) {
                setIntParamSafe(fx, DolbyConstants.MISOUND_PARAM_SCENARIO, clampedScenario)
                setIntParamSafe(
                    fx,
                    DolbyConstants.MISOUND_PARAM_3D_SURROUND_ENABLE,
                    if (surround3dEnabled) 1 else 0
                )
                setIntParamSafe(fx, DolbyConstants.MISOUND_PARAM_SURROUND_LEVEL, clampedSurround)
                setIntParamSafe(fx, DolbyConstants.MISOUND_PARAM_VOCAL_LEVEL, clampedVocal)
            }
        }
    }

    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean {
        return try {
            if (!enabled && audioEffect == null && propDisabled) {
                return true
            }
            setPropSafe(DolbyConstants.PROP_MISOUND_DISABLE, if (enabled) "false" else "true")
            propDisabled = !enabled
            if (enabled) {
                val fx = obtainEffect() ?: return false
                setIntParamSafe(fx, DolbyConstants.MISOUND_PARAM_ENABLE, 1)
                val status = fx.setEnabled(true)
                if (status != AudioEffect.SUCCESS) {
                    Log.w(TAG, "MiSound setEnabled(true) returned status=$status, recreating")
                    release()
                    val retryFx = obtainEffect() ?: return false
                    setIntParamSafe(retryFx, DolbyConstants.MISOUND_PARAM_ENABLE, 1)
                    retryFx.setEnabled(true) == AudioEffect.SUCCESS
                } else {
                    true
                }
            } else {
                release()
                true
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to toggle MiSoundAudioEffect enabled=$enabled", t)
            if (!enabled) release()
            false
        }
    }

    @Synchronized
    fun release() {
        audioEffect?.let { fx ->
            try {
                setIntParamSafe(fx, DolbyConstants.MISOUND_PARAM_ENABLE, 0)
            } catch (_: Throwable) {
            }
            try {
                fx.enabled = false
            } catch (_: Throwable) {
            }
            try {
                fx.release()
            } catch (_: Throwable) {
            }
        }
        audioEffect = null
    }

    private fun setIntParamSafe(fx: AudioEffect, param: Int, value: Int) {
        try {
            fx.setParameter(param, value)
        } catch (t: Throwable) {
            DolbyConstants.dlog(TAG, "setParameter($param, $value) failed: ${t.message}")
        }
    }

    private fun setPropSafe(key: String, value: String) {
        try {
            SystemProperties.set(key, value)
        } catch (t: Throwable) {
            DolbyConstants.dlog(TAG, "SystemProperties.set($key=$value) skipped: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "MiSoundAudioEffect"
    }
}
