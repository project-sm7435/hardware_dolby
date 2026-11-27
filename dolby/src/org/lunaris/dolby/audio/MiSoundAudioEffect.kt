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

    // What the app wants, independent of the (invalidatable) param cache. Used to tell an
    // AudioFlinger-initiated disable apart from one we requested ourselves.
    private var desiredEnabled: Boolean = false
    // True once our own disable (incl. 3D off) went through; cleared on any enable or new handle.
    private var disabledByUs: Boolean = false
    private var basePriority: Int = priority
    private var currentPriority: Int = priority

    @Volatile
    var onRecoveryNeeded: (() -> Unit)? = null

    val isEnabled: Boolean
        @Synchronized get() {
            return try {
                val fx = audioEffect ?: return false
                if (!fx.hasControl() || !fx.enabled || lastEnabled == false) return false
                val nativeEnabled = getIntParam(fx, DolbyConstants.MISOUND_PARAM_ENABLE)
                val musicEnabled = getIntParam(fx, DolbyConstants.MISOUND_PARAM_MUSIC)
                (nativeEnabled == null || nativeEnabled == 1) && (musicEnabled == null || musicEnabled == 1)
            } catch (_: Throwable) {
                false
            }
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
            // Match sm8250-common MiSound verification: native enable (param 25) and music gate
            // (param 4) must both still be 1; fall back to a lightweight write if getParameter fails.
            val nativeEnabled = getIntParam(fx, DolbyConstants.MISOUND_PARAM_ENABLE)
            val musicEnabled = getIntParam(fx, DolbyConstants.MISOUND_PARAM_MUSIC)
            if (nativeEnabled != null && nativeEnabled != 1) return false
            if (musicEnabled != null && musicEnabled != 1) return false
            if (nativeEnabled == 1) true else setIntParam(fx, DolbyConstants.MISOUND_PARAM_ENABLE, 1)
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
        // 3D is exclusive to Xiaomi Sound: while it is off, the props must say "no 3D" even
        // though the saved 3D setting stays ON in the app.
        val surroundActive = enabled && surround3dEnabled
        val effectiveSurround = if (surroundActive) surroundLevel.coerceIn(0, 100) else 0
        val clampedVocal = vocalLevel.coerceIn(0, 100)

        setPropSafe(DolbyConstants.PROP_MISOUND_DISABLE, if (enabled) "false" else "true")
        setPropSafe(
            DolbyConstants.PROP_MISOUND_AUTO_SCENARIO,
            if (nativeScenario == DolbyConstants.MISOUND_SCENARIO_SMART) "true" else "false"
        )
        setPropSafe(DolbyConstants.PROP_MISOUND_SCENARIO, nativeScenario.toString())
        setPropSafe(DolbyConstants.PROP_MISOUND_MUSIC_STATE, if (enabled) "1" else "0")
        setPropSafe(DolbyConstants.PROP_MISOUND_3D_SURROUND, if (surroundActive) "1" else "0")
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

    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean {
        desiredEnabled = enabled
        return try {
            setPropSafe(DolbyConstants.PROP_MISOUND_DISABLE, if (enabled) "false" else "true")
            if (!enabled) {
                setPropSafe(DolbyConstants.PROP_MISOUND_MUSIC_STATE, "0")
                setPropSafe(DolbyConstants.PROP_MISOUND_3D_SURROUND, "0")
                setPropSafe(DolbyConstants.PROP_MISOUND_SURROUND_LEVEL, "0")
                if (disabledByUs && audioEffect?.let { safeEnabled(it) } != true) return true
                var fx = audioEffect
                var disabledOk = true
                if (fx != null) {
                    runCatching {
                        // Without control the calls below are silently ignored and MiSound keeps
                        // running; win control back first instead of reporting a fake success.
                        if (!fx!!.hasControl()) {
                            fx = rebindHandle()
                        }
                        val h = fx
                        if (h == null || !h.hasControl()) {
                            disabledOk = false
                        } else {
                            // Disable framework effect first, then native enable/music params
                            // (matching sm8250-common MiSoundEffect.setEnabled(false)).
                            if (h.enabled) {
                                disabledOk = h.setEnabled(false) == AudioEffect.SUCCESS
                            }
                            // 3D surround lives in the DSP (QdspParameterId 22) and can outlast the
                            // effect being disabled, so it is cleared best-effort here.
                            setOptionalIntParam(h, DolbyConstants.MISOUND_PARAM_3D_SURROUND_ENABLE, 0)
                            setOptionalIntParam(h, DolbyConstants.MISOUND_PARAM_SURROUND_LEVEL, 0)
                            setOptionalIntParam(h, DolbyConstants.MISOUND_PARAM_MOVIE_MODE_ENABLE, 0)
                            val paramOffOk = setIntParam(h, DolbyConstants.MISOUND_PARAM_ENABLE, 0)
                            val musicOffOk = setIntParam(h, DolbyConstants.MISOUND_PARAM_MUSIC, 0)
                            disabledOk = disabledOk && paramOffOk && musicOffOk
                        }
                    }.onFailure {
                        disabledOk = false
                    }
                }
                disabledByUs = disabledOk
                lastEnabled = if (disabledOk) false else null
                lastScenario = null
                lastMusicMode = null
                lastMovieMode = null
                lastSurround3d = null
                lastSurroundLevel = null
                lastVocalLevel = null
                lastEqDb = null
                return disabledOk
            }

            disabledByUs = false
            val fx = ensureEffect(ignoreBackoff = true) ?: return false
            if (!enableNativeEffect(fx)) {
                Log.w(TAG, "setEnabled(true) failed, rebinding")
                val retryFx = rebindHandle() ?: return false
                val ok = enableNativeEffect(retryFx)
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
        desiredEnabled = true
        disabledByUs = false
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
                val effectiveSurround = if (surround3dEnabled) surroundLevel.coerceIn(0, 100) else 0
                val clampedVocal = vocalLevel.coerceIn(0, 100)
                val scenarioChanged = lastScenario != nativeScenario
                val effectiveForce = force || scenarioChanged

                var allOk = true
                var justEnabled = false

                if (effectiveForce || lastEnabled != true || !fx.enabled) {
                    if (enableNativeEffect(fx)) {
                        lastEnabled = true
                        lastMusicMode = true
                        justEnabled = true
                    } else {
                        allOk = false
                    }
                } else if (lastMusicMode != true) {
                    // On sm8250-common libmisoundfx.so, param 4 (music) is the active processing gate
                    // and must stay 1 whenever MiSound is enabled (even in Movie scenario).
                    if (setIntParam(fx, DolbyConstants.MISOUND_PARAM_MUSIC, 1)) {
                        lastMusicMode = true
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

                // Optional HyperOS/newer libmisoundfx.so parameters: apply best-effort so
                // devices with older/sm8250 libmisoundfx.so (without 16/17/18/20) still succeed.
                if (effectiveForce || lastMovieMode != isMovie) {
                    setOptionalIntParam(fx, DolbyConstants.MISOUND_PARAM_MOVIE_MODE_ENABLE, if (isMovie) 1 else 0)
                    lastMovieMode = isMovie
                }
                if (effectiveForce || lastSurround3d != surround3dEnabled) {
                    setOptionalIntParam(
                        fx,
                        DolbyConstants.MISOUND_PARAM_3D_SURROUND_ENABLE,
                        if (surround3dEnabled) 1 else 0
                    )
                    lastSurround3d = surround3dEnabled
                }
                if (effectiveForce || lastSurroundLevel != effectiveSurround) {
                    setOptionalIntParam(fx, DolbyConstants.MISOUND_PARAM_SURROUND_LEVEL, effectiveSurround)
                    lastSurroundLevel = effectiveSurround
                }
                if (effectiveForce || lastVocalLevel != clampedVocal) {
                    setOptionalIntParam(fx, DolbyConstants.MISOUND_PARAM_VOCAL_LEVEL, clampedVocal)
                    lastVocalLevel = clampedVocal
                }

                val eqOk = applyEqInternal(fx, eqGainsTenths, effectiveForce)
                val finalEnableOk = if (justEnabled) true else setIntParam(fx, DolbyConstants.MISOUND_PARAM_ENABLE, 1)
                val finalMusicOk = if (justEnabled) true else setIntParam(fx, DolbyConstants.MISOUND_PARAM_MUSIC, 1)
                if (!allOk || !eqOk || !finalEnableOk || !finalMusicOk) {
                    throw IllegalStateException(
                        "MiSound param push failed (allOk=$allOk eqOk=$eqOk enableOk=$finalEnableOk musicOk=$finalMusicOk)"
                    )
                }
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "applyAllParams failed (retry=$attemptedRetry): ${t.message}")
                if (attemptedRetry) return false
                attemptedRetry = true
                // Swap handles (new one first) so the session-0 effect module is never torn down.
                fx = rebindHandle() ?: return false
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
                val retryFx = rebindHandle() ?: return false
                eqOk = applyEqInternal(retryFx, eqGainsTenths, force = true)
                enableOk = setIntParam(retryFx, DolbyConstants.MISOUND_PARAM_ENABLE, 1) &&
                    setIntParam(retryFx, DolbyConstants.MISOUND_PARAM_MUSIC, 1)
            } else {
                eqOk = applyEqInternal(fx, eqGainsTenths, force = force)
                enableOk = setIntParam(fx, DolbyConstants.MISOUND_PARAM_ENABLE, 1) &&
                    setIntParam(fx, DolbyConstants.MISOUND_PARAM_MUSIC, 1)
            }
            eqOk && enableOk
        } catch (t: Throwable) {
            Log.w(TAG, "setEqGains failed: ${t.message}")
            false
        }
    }

    @Synchronized
    fun release() {
        desiredEnabled = false
        setPropSafe(DolbyConstants.PROP_MISOUND_DISABLE, "true")
        setPropSafe(DolbyConstants.PROP_MISOUND_MUSIC_STATE, "0")
        audioEffect?.let { fx ->
            runCatching {
                if (fx.hasControl()) {
                    if (fx.enabled) {
                        fx.setEnabled(false)
                    }
                    setOptionalIntParam(fx, DolbyConstants.MISOUND_PARAM_3D_SURROUND_ENABLE, 0)
                    setOptionalIntParam(fx, DolbyConstants.MISOUND_PARAM_MOVIE_MODE_ENABLE, 0)
                    setIntParam(fx, DolbyConstants.MISOUND_PARAM_ENABLE, 0)
                    setIntParam(fx, DolbyConstants.MISOUND_PARAM_MUSIC, 0)
                }
            }
            runCatching { fx.release() }
        }
        audioEffect = null
        invalidateParamCache()
    }

    private fun enableNativeEffect(fx: AudioEffect): Boolean {
        if (!setIntParam(fx, DolbyConstants.MISOUND_PARAM_ENABLE, 1)) return false
        val routeOk = try {
            if (!fx.enabled) fx.setEnabled(true) == AudioEffect.SUCCESS else true
        } catch (t: Throwable) {
            Log.w(TAG, "Framework MiSound setEnabled(true) threw: ${t.message}")
            false
        }
        if (!routeOk) {
            setIntParam(fx, DolbyConstants.MISOUND_PARAM_ENABLE, 0)
            return false
        }
        return setIntParam(fx, DolbyConstants.MISOUND_PARAM_MUSIC, 1)
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
        lastEqDb = if (ok) currentCache else null
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
            // Lost control (another client took it, or the native object died). Try to win it back
            // with a higher-priority handle while the old one still keeps the effect module alive.
            return rebindHandle()
        }

        val now = SystemClock.elapsedRealtime()
        if (!ignoreBackoff && now - lastFailedCreateMs < CREATE_RETRY_BACKOFF_MS) {
            return null
        }
        return createHandle()
    }

    private fun createHandle(): AudioEffect? {
        val now = SystemClock.elapsedRealtime()
        var prio = basePriority
        var created: AudioEffect? = instantiate(prio)
        val first = created
        if (first != null && !safeHasControl(first)) {
            Log.w(TAG, "New MiSound handle has no control at priority $prio, escalating")
            runCatching { first.release() }
            prio = priority + ESCALATED_PRIORITY_DELTA
            basePriority = prio
            created = instantiate(prio)
        }
        val fx = created
        if (fx == null) {
            lastFailedCreateMs = now
            audioEffect = null
            return null
        }
        adopt(fx, prio)
        return fx
    }

    private fun rebindHandle(): AudioEffect? {
        val old = audioEffect
        val nextPriority = currentPriority + 1
        if (old != null && nextPriority <= basePriority + MAX_PRIORITY_BUMP) {
            val fresh = instantiate(nextPriority)
            if (fresh != null) {
                if (safeHasControl(fresh)) {
                    adopt(fresh, nextPriority)
                    runCatching { old.release() }
                    Log.i(TAG, "MiSound handle rebound seamlessly (priority=$nextPriority)")
                    return fresh
                }
                runCatching { fresh.release() }
            }
        }
        dropHandleOnly()
        currentPriority = basePriority
        return createHandle()
    }

    private fun instantiate(prio: Int): AudioEffect? = try {
        AudioEffect(
            AudioEffect.EFFECT_TYPE_NULL,
            DolbyConstants.EFFECT_UUID_MISOUND,
            prio,
            audioSession
        )
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to instantiate MiSoundAudioEffect (priority=$prio)", t)
        null
    }

    private fun safeHasControl(fx: AudioEffect): Boolean = try {
        fx.hasControl()
    } catch (_: Throwable) {
        false
    }

    private fun adopt(fx: AudioEffect, prio: Int) {
        fx.setControlStatusListener { _, controlGranted ->
            if (controlGranted) return@setControlStatusListener
            var recover = false
            synchronized(this@MiSoundAudioEffect) {
                // Ignore events from handles we already replaced.
                if (audioEffect === fx) {
                    Log.w(TAG, "MiSoundAudioEffect lost control from AudioFlinger")
                    lastEnabled = null
                    recover = desiredEnabled
                }
            }
            if (recover) onRecoveryNeeded?.invoke()
        }
        fx.setEnableStatusListener { _, enabled ->
            var recover = false
            synchronized(this@MiSoundAudioEffect) {
                if (audioEffect === fx && !enabled && desiredEnabled) {
                    Log.w(TAG, "MiSoundAudioEffect disabled externally")
                    lastEnabled = false
                    recover = true
                }
            }
            if (recover) onRecoveryNeeded?.invoke()
        }
        audioEffect = fx
        currentPriority = prio
        invalidateParamCache()
    }

    @Synchronized
    fun describeState(): String {
        val fx = audioEffect
        return try {
            "handle=${fx != null} control=${fx?.hasControl()} enabled=${fx?.enabled} " +
                "desired=$desiredEnabled lastEnabled=$lastEnabled prio=$currentPriority"
        } catch (t: Throwable) {
            "handle=${fx != null} (state query failed: ${t.message})"
        }
    }

    private fun dropHandleOnly() {
        audioEffect?.let { fx ->
            runCatching { fx.release() }
        }
        audioEffect = null
        invalidateParamCache()
    }

    private fun safeEnabled(fx: AudioEffect): Boolean = try {
        fx.enabled
    } catch (_: Throwable) {
        false
    }

    private fun invalidateParamCache() {
        disabledByUs = false
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

    private fun getIntParam(fx: AudioEffect, paramId: Int): Int? = try {
        val value = IntArray(1)
        val count = fx.getParameter(paramId, value)
        if (count == 1) value[0] else null
    } catch (_: Throwable) {
        null
    }

    private fun setIntParam(fx: AudioEffect, paramId: Int, value: Int): Boolean {
        val status = fx.setParameter(int32ToLeBytes(paramId), int32ToLeBytes(value))
        if (status != AudioEffect.SUCCESS) {
            Log.w(TAG, "setIntParam($paramId, $value) failed status=$status")
            return false
        }
        return true
    }

    private fun setOptionalIntParam(fx: AudioEffect, paramId: Int, value: Int): Boolean {
        val status = fx.setParameter(int32ToLeBytes(paramId), int32ToLeBytes(value))
        if (status != AudioEffect.SUCCESS) {
            DolbyConstants.dlog(TAG, "Optional param($paramId, $value) unsupported status=$status")
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
            // The shipped libmisoundfx.so setter uses strtof, which requires a NUL-terminated C string.
            "$formatted\u0000".toByteArray(Charsets.US_ASCII)
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
        // No key length guard: Android O+ allows long property names, and these keys are 33-39 chars.
        try {
            if (SystemProperties.get(key) == value) return
            SystemProperties.set(key, value)
        } catch (t: Throwable) {
            Log.w(TAG, "setprop $key=$value failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "MiSoundAudioEffect"
        private const val CREATE_RETRY_BACKOFF_MS = 2_000L
        private const val ESCALATED_PRIORITY_DELTA = 1_000
        private const val MAX_PRIORITY_BUMP = 50
    }
}