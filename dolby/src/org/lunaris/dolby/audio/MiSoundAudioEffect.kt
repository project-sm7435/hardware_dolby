/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.audio

import android.media.audiofx.AudioEffect
import android.os.SystemClock
import android.os.SystemProperties
import android.util.Log
import org.lunaris.dolby.DolbyConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs

class MiSoundAudioEffect(
    private val priority: Int = 0,
    private val audioSession: Int = 0,
    private val useFloatEqPayload: Boolean = false
) {

    private var audioEffect: AudioEffect? = null
    private var lastEnabled: Boolean? = null
    private var lastScenario: Int? = null
    private var lastMovieMode: Boolean? = null
    private var lastMusicMode: Boolean? = null
    private var lastSurround3d: Boolean? = null
    private var lastSurroundLevel: Int? = null
    private var lastVocalLevel: Int? = null
    private var lastEqDb: FloatArray? = null
    private var lastFailedCreateMs: Long = 0L

    val isEnabled: Boolean
        @Synchronized get() = try {
            val fx = audioEffect ?: return false
            fx.hasControl() && fx.enabled && (lastEnabled != false)
        } catch (_: Throwable) {
            false
        }

    @Synchronized
    fun hasControl(): Boolean = try {
        audioEffect?.hasControl() == true
    } catch (_: Throwable) {
        false
    }

    @Synchronized
    fun isHealthy(): Boolean {
        val fx = audioEffect ?: return false
        return try {
            if (!fx.hasControl() || !fx.enabled || lastEnabled == false) return false
            // Lightweight native liveness check; catches ERROR_DEAD_OBJECT / bypass
            setIntParam(fx, DolbyConstants.MISOUND_PARAM_ENABLE, 1)
        } catch (_: Throwable) {
            false
        }
    }

    @Synchronized
    fun syncProperties(
        enabled: Boolean,
        uiScenario: Int,
        surround3dEnabled: Boolean,
        surroundLevel: Int,
        vocalLevel: Int,
        eqGainsTenths: IntArray? = null
    ) {
        val clampedScenario = uiScenario.coerceIn(0, 2)
        val nativeScenario = DolbyConstants.uiScenarioToNative(clampedScenario)
        val effectiveSurround = if (surround3dEnabled) surroundLevel.coerceIn(0, 100) else 0
        val clampedVocal = vocalLevel.coerceIn(0, 100)

        setPropSafe(DolbyConstants.PROP_MISOUND_DISABLE, if (enabled) "false" else "true")
        setPropSafe(
            DolbyConstants.PROP_MISOUND_AUTO_SCENARIO,
            if (nativeScenario == DolbyConstants.MISOUND_SCENARIO_SMART) "true" else "false"
        )
        setPropSafe(DolbyConstants.PROP_MISOUND_SCENARIO, nativeScenario.toString())
        setPropSafe(DolbyConstants.PROP_MISOUND_MUSIC_STATE, if (clampedScenario == 2) "0" else "1")
        setPropSafe(DolbyConstants.PROP_MISOUND_3D_SURROUND, if (surround3dEnabled) "1" else "0")
        setPropSafe(DolbyConstants.PROP_MISOUND_SURROUND_LEVEL, effectiveSurround.toString())
        setPropSafe(DolbyConstants.PROP_MISOUND_VOCAL_LEVEL, clampedVocal.toString())

        if (eqGainsTenths != null && eqGainsTenths.size == DolbyConstants.MISOUND_EQ_BAND_COUNT) {
            val formattedEq = eqGainsTenths.joinToString(",") { tenths ->
                val db = (tenths / 10f).coerceIn(
                    DolbyConstants.MISOUND_EQ_MIN_DB,
                    DolbyConstants.MISOUND_EQ_MAX_DB
                )
                String.format(Locale.US, "%.2f", db)
            }
            setPropSafe(DolbyConstants.PROP_MISOUND_EQ, formattedEq)
        }
    }

    /**
     * Disables Mi Sound cleanly without calling AudioEffect.release() so AudioFlinger's
     * session-0 mixer chain is never torn down mid-stream.
     */
    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean {
        return try {
            setPropSafe(DolbyConstants.PROP_MISOUND_DISABLE, if (enabled) "false" else "true")
            if (!enabled) {
                val fx = audioEffect
                var disabledOk = true
                if (fx != null) {
                    runCatching {
                        if (fx.hasControl()) {
                            setIntParam(fx, DolbyConstants.MISOUND_PARAM_MUSIC, 0)
                            setIntParam(fx, DolbyConstants.MISOUND_PARAM_MOVIE_MODE_ENABLE, 0)
                            setIntParam(fx, DolbyConstants.MISOUND_PARAM_ENABLE, 0)
                            if (fx.enabled) {
                                disabledOk = fx.setEnabled(false) == AudioEffect.SUCCESS
                            }
                        }
                    }.onFailure {
                        disabledOk = false
                    }
                }
                lastEnabled = false
                lastScenario = null
                lastMusicMode = null
                lastMovieMode = null
                lastSurround3d = null
                lastSurroundLevel = null
                lastVocalLevel = null
                lastEqDb = null
                return disabledOk
            }

            val fx = ensureEffect(ignoreBackoff = true) ?: return false
            val paramOk = setIntParam(fx, DolbyConstants.MISOUND_PARAM_ENABLE, 1)
            val routeOk = if (!fx.enabled) fx.setEnabled(true) == AudioEffect.SUCCESS else true
            if (!paramOk || !routeOk || !fx.hasControl()) {
                Log.w(TAG, "setEnabled(true) failed (paramOk=$paramOk, routeOk=$routeOk), recreating")
                dropHandleOnly()
                val retryFx = ensureEffect(ignoreBackoff = true) ?: return false
                val retryParamOk = setIntParam(retryFx, DolbyConstants.MISOUND_PARAM_ENABLE, 1)
                val retryRouteOk = if (!retryFx.enabled) {
                    retryFx.setEnabled(true) == AudioEffect.SUCCESS
                } else {
                    true
                }
                val ok = retryParamOk && retryRouteOk
                lastEnabled = if (ok) true else null
                return ok
            }
            lastEnabled = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to toggle MiSoundAudioEffect enabled=$enabled", t)
            if (!enabled) {
                lastEnabled = false
            }
            false
        }
    }

    @Synchronized
    fun applyAllParams(
        uiScenario: Int,
        surround3dEnabled: Boolean,
        surroundLevel: Int,
        vocalLevel: Int,
        eqGainsTenths: IntArray,
        force: Boolean = false
    ): Boolean {
        val clampedScenario = uiScenario.coerceIn(0, 2)
        syncProperties(
            enabled = true,
            uiScenario = clampedScenario,
            surround3dEnabled = surround3dEnabled,
            surroundLevel = surroundLevel,
            vocalLevel = vocalLevel,
            eqGainsTenths = eqGainsTenths
        )

        var fx = ensureEffect(ignoreBackoff = force) ?: return false
        var attemptedRetry = false

        while (true) {
            try {
                if (!fx.hasControl()) {
                    throw IllegalStateException("Lost control of MiSoundAudioEffect")
                }

                val nativeScenario = DolbyConstants.uiScenarioToNative(clampedScenario)
                val isMovie = clampedScenario == 2
                val isMusic = !isMovie
                val effectiveSurround = if (surround3dEnabled) surroundLevel.coerceIn(0, 100) else 0
                val clampedVocal = vocalLevel.coerceIn(0, 100)
                val scenarioChanged = lastScenario != nativeScenario
                val effectiveForce = force || scenarioChanged

                var allOk = true

                if (effectiveForce || lastEnabled != true || !fx.enabled) {
                    val pOk = setIntParam(fx, DolbyConstants.MISOUND_PARAM_ENABLE, 1)
                    val eOk = if (!fx.enabled) fx.setEnabled(true) == AudioEffect.SUCCESS else true
                    if (pOk && eOk) {
                        lastEnabled = true
                    } else {
                        allOk = false
                    }
                }

                if (effectiveForce || lastScenario != nativeScenario) {
                    if (setIntParam(fx, DolbyConstants.MISOUND_PARAM_SCENARIO, nativeScenario)) {
                        lastScenario = nativeScenario
                    } else {
                        allOk = false
                    }
                }
                if (effectiveForce || lastMovieMode != isMovie) {
                    if (setIntParam(fx, DolbyConstants.MISOUND_PARAM_MOVIE_MODE_ENABLE, if (isMovie) 1 else 0)) {
                        lastMovieMode = isMovie
                    } else {
                        allOk = false
                    }
                }
                if (effectiveForce || lastMusicMode != isMusic) {
                    if (setIntParam(fx, DolbyConstants.MISOUND_PARAM_MUSIC, if (isMusic) 1 else 0)) {
                        lastMusicMode = isMusic
                    } else {
                        allOk = false
                    }
                }

                if (effectiveForce || lastSurround3d != surround3dEnabled) {
                    if (setIntParam(
                            fx,
                            DolbyConstants.MISOUND_PARAM_3D_SURROUND_ENABLE,
                            if (surround3dEnabled) 1 else 0
                        )
                    ) {
                        lastSurround3d = surround3dEnabled
                    } else {
                        allOk = false
                    }
                }
                if (effectiveForce || lastSurroundLevel != effectiveSurround) {
                    if (setIntParam(fx, DolbyConstants.MISOUND_PARAM_SURROUND_LEVEL, effectiveSurround)) {
                        lastSurroundLevel = effectiveSurround
                    } else {
                        allOk = false
                    }
                }
                if (effectiveForce || lastVocalLevel != clampedVocal) {
                    if (setIntParam(fx, DolbyConstants.MISOUND_PARAM_VOCAL_LEVEL, clampedVocal)) {
                        lastVocalLevel = clampedVocal
                    } else {
                        allOk = false
                    }
                }

                val eqOk = applyEqInternal(fx, eqGainsTenths, effectiveForce)
                val finalEnableOk = setIntParam(fx, DolbyConstants.MISOUND_PARAM_ENABLE, 1)
                if (!allOk || !eqOk || !finalEnableOk) {
                    throw IllegalStateException("MiSound param push failed (allOk=$allOk eqOk=$eqOk enableOk=$finalEnableOk)")
                }
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "applyAllParams failed (retry=$attemptedRetry): ${t.message}")
                if (attemptedRetry) return false
                attemptedRetry = true
                dropHandleOnly()
                fx = ensureEffect(ignoreBackoff = true) ?: return false
            }
        }
    }

    @Synchronized
    fun setEqGains(eqGainsTenths: IntArray, force: Boolean = false): Boolean {
        val formattedEq = eqGainsTenths.take(DolbyConstants.MISOUND_EQ_BAND_COUNT).joinToString(",") { tenths ->
            val db = (tenths / 10f).coerceIn(DolbyConstants.MISOUND_EQ_MIN_DB, DolbyConstants.MISOUND_EQ_MAX_DB)
            String.format(Locale.US, "%.2f", db)
        }
        setPropSafe(DolbyConstants.PROP_MISOUND_EQ, formattedEq)

        val fx = ensureEffect(ignoreBackoff = force) ?: return false
        return try {
            val eqOk: Boolean
            val enableOk: Boolean
            if (!fx.hasControl()) {
                dropHandleOnly()
                val retryFx = ensureEffect(ignoreBackoff = true) ?: return false
                eqOk = applyEqInternal(retryFx, eqGainsTenths, force = true)
                enableOk = setIntParam(retryFx, DolbyConstants.MISOUND_PARAM_ENABLE, 1)
            } else {
                eqOk = applyEqInternal(fx, eqGainsTenths, force = force)
                enableOk = setIntParam(fx, DolbyConstants.MISOUND_PARAM_ENABLE, 1)
            }
            eqOk && enableOk
        } catch (t: Throwable) {
            Log.w(TAG, "setEqGains failed: ${t.message}")
            false
        }
    }

    /**
     * Releases the session-0 native handle. Must ONLY be called on process shutdown.
     */
    @Synchronized
    fun release() {
        setPropSafe(DolbyConstants.PROP_MISOUND_DISABLE, "true")
        audioEffect?.let { fx ->
            runCatching {
                if (fx.hasControl()) {
                    setIntParam(fx, DolbyConstants.MISOUND_PARAM_MUSIC, 0)
                    setIntParam(fx, DolbyConstants.MISOUND_PARAM_MOVIE_MODE_ENABLE, 0)
                    setIntParam(fx, DolbyConstants.MISOUND_PARAM_ENABLE, 0)
                    if (fx.enabled) {
                        fx.setEnabled(false)
                    }
                }
            }
            runCatching { fx.release() }
        }
        audioEffect = null
        invalidateParamCache()
    }

    private fun applyEqInternal(fx: AudioEffect, eqGainsTenths: IntArray, force: Boolean): Boolean {
        val currentCache = lastEqDb ?: FloatArray(DolbyConstants.MISOUND_EQ_BAND_COUNT) { Float.NaN }
        var ok = true
        for (band in 0 until DolbyConstants.MISOUND_EQ_BAND_COUNT) {
            val tenths = eqGainsTenths.getOrElse(band) { 0 }
            val db = (tenths / 10f).coerceIn(DolbyConstants.MISOUND_EQ_MIN_DB, DolbyConstants.MISOUND_EQ_MAX_DB)
            val prev = currentCache[band]
            if (force || prev.isNaN() || abs(prev - db) > 0.005f) {
                if (setEqBandParam(fx, band, db)) {
                    currentCache[band] = db
                } else {
                    ok = false
                }
            }
        }
        lastEqDb = currentCache
        return ok
    }

    private fun ensureEffect(ignoreBackoff: Boolean = false): AudioEffect? {
        audioEffect?.let { existing ->
            val inControl = try {
                existing.hasControl()
            } catch (_: Throwable) {
                false
            }
            if (inControl) return existing
            dropHandleOnly()
        }

        val now = SystemClock.elapsedRealtime()
        if (!ignoreBackoff && now - lastFailedCreateMs < CREATE_RETRY_BACKOFF_MS) {
            return null
        }

        return try {
            AudioEffect(
                AudioEffect.EFFECT_TYPE_NULL,
                DolbyConstants.EFFECT_UUID_MISOUND,
                priority,
                audioSession
            ).also { fx ->
                fx.setControlStatusListener { _, controlGranted ->
                    if (!controlGranted) {
                        Log.w(TAG, "MiSoundAudioEffect lost control from AudioFlinger")
                        synchronized(this@MiSoundAudioEffect) {
                            lastEnabled = null
                        }
                    }
                }
                fx.setEnableStatusListener { _, enabled ->
                    synchronized(this@MiSoundAudioEffect) {
                        if (!enabled && lastEnabled == true) {
                            Log.w(TAG, "MiSoundAudioEffect disabled externally")
                            lastEnabled = false
                        }
                    }
                }
                audioEffect = fx
                invalidateParamCache()
            }
        } catch (t: Throwable) {
            lastFailedCreateMs = now
            Log.e(TAG, "Failed to instantiate MiSoundAudioEffect", t)
            audioEffect = null
            null
        }
    }

    private fun dropHandleOnly() {
        audioEffect?.let { fx ->
            runCatching { fx.release() }
        }
        audioEffect = null
        invalidateParamCache()
    }

    private fun invalidateParamCache() {
        lastEnabled = null
        lastScenario = null
        lastMovieMode = null
        lastMusicMode = null
        lastSurround3d = null
        lastSurroundLevel = null
        lastVocalLevel = null
        lastEqDb = null
        lastFailedCreateMs = 0L
    }

    private fun setIntParam(fx: AudioEffect, paramId: Int, value: Int): Boolean {
        val status = fx.setParameter(int32ToLeBytes(paramId), int32ToLeBytes(value))
        if (status != AudioEffect.SUCCESS) {
            Log.w(TAG, "setIntParam($paramId, $value) failed status=$status")
            return false
        }
        return true
    }

    private fun setEqBandParam(fx: AudioEffect, band: Int, gainDb: Float): Boolean {
        if (band !in 0 until DolbyConstants.MISOUND_EQ_BAND_COUNT) return false
        val formatted = String.format(Locale.US, "%.2f", gainDb)
        val paramBytes = ByteArray(8)
        int32ToLeBytes(DolbyConstants.MISOUND_PARAM_EQ_LEVEL).copyInto(paramBytes, 0)
        int32ToLeBytes(band).copyInto(paramBytes, 4)
        val valueBytes = if (useFloatEqPayload) {
            float32ToLeBytes(gainDb)
        } else {
            formatted.toByteArray(Charsets.US_ASCII)
        }
        val status = fx.setParameter(paramBytes, valueBytes)
        if (status != AudioEffect.SUCCESS) {
            Log.w(TAG, "setEqBandParam(band=$band, db=$formatted) failed status=$status")
            return false
        }
        return true
    }

    private fun float32ToLeBytes(value: Float): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()

    private fun int32ToLeBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte()
    )

    private fun setPropSafe(key: String, value: String) {
        if (key.length > 31) return
        try {
            SystemProperties.set(key, value)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "MiSoundAudioEffect"
        private const val CREATE_RETRY_BACKOFF_MS = 2_000L
    }
}