/*
 * Copyright (C) 2018,2020 The LineageOS Project
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.audio

import android.media.audiofx.AudioEffect
import android.os.SystemClock
import android.os.SystemProperties
import android.util.Log
import org.lunaris.dolby.DolbyConstants
import java.util.Locale
import kotlin.math.abs

class MiSoundAudioEffect(
    private val priority: Int = 0,
    private val audioSession: Int = 0,
) {

    private class NativeMiSoundEffect(priority: Int, audioSession: Int) :
        AudioEffect(
            EFFECT_TYPE_NULL,
            DolbyConstants.EFFECT_UUID_MISOUND,
            priority,
            audioSession,
        ) {

        override fun hasControl(): Boolean =
            try {
                super.hasControl()
            } catch (_: IllegalStateException) {
                false
            }

        override fun setEnabled(enabled: Boolean): Int {
            if (!enabled) {
                var failure: RuntimeException? = null
                try {
                    checkStatus(super.setEnabled(false))
                } catch (error: RuntimeException) {
                    failure = error
                }
                try {
                    checkStatus(setParameter(DolbyConstants.MISOUND_PARAM_ENABLE, 0))
                } catch (error: RuntimeException) {
                    if (failure == null) failure = error
                    else if (failure !== error) failure.addSuppressed(error)
                }
                failure?.let { throw it }
                return SUCCESS
            }
            checkStatus(setParameter(DolbyConstants.MISOUND_PARAM_ENABLE, 1))
            val status =
                try {
                    super.setEnabled(true)
                } catch (failure: RuntimeException) {
                    try {
                        checkStatus(setParameter(DolbyConstants.MISOUND_PARAM_ENABLE, 0))
                    } catch (rollback: RuntimeException) {
                        if (failure !== rollback) failure.addSuppressed(rollback)
                    }
                    throw failure
                }
            if (status != SUCCESS) {
                val failure = IllegalStateException("Framework MiSound enable failed: $status")
                try {
                    checkStatus(super.setEnabled(false))
                } catch (rollback: RuntimeException) {
                    failure.addSuppressed(rollback)
                }
                try {
                    checkStatus(setParameter(DolbyConstants.MISOUND_PARAM_ENABLE, 0))
                } catch (rollback: RuntimeException) {
                    failure.addSuppressed(rollback)
                }
                throw failure
            }
            return SUCCESS
        }

        private fun intParameter(parameter: Int): Int {
            val value = IntArray(1)
            val count = getParameter(parameter, value)
            checkStatus(count)
            check(count == 1) { "Incomplete MiSound scalar: $count" }
            return value[0]
        }

        override fun getEnabled(): Boolean =
            intParameter(DolbyConstants.MISOUND_PARAM_ENABLE) == 1 && super.getEnabled()

        fun hasExpectedEnabledState(enabled: Boolean): Boolean =
            try {
                intParameter(DolbyConstants.MISOUND_PARAM_ENABLE) == (if (enabled) 1 else 0) &&
                    super.getEnabled() == enabled
            } catch (_: Throwable) {
                super.getEnabled() == enabled
            }

        fun getMusic(): Int = intParameter(DolbyConstants.MISOUND_PARAM_MUSIC)

        fun setMusic(enable: Int) {
            require(enable in 0..1)
            checkStatus(setParameter(DolbyConstants.MISOUND_PARAM_MUSIC, enable))
        }

        fun setLevel(band: Int, level: Float) {
            require(band in 0 until DolbyConstants.MISOUND_EQ_BAND_COUNT && level.isFinite())
            checkStatus(
                setParameter(
                    intArrayOf(DolbyConstants.MISOUND_PARAM_EQ_LEVEL, band),
                    "$level\u0000".toByteArray(Charsets.US_ASCII)
                )
            )
        }

        fun setScenario(scene: Int) {
            require(scene in 0..4)
            checkStatus(setParameter(DolbyConstants.MISOUND_PARAM_SCENARIO, scene))
        }

        fun setOptionalParam(paramId: Int, value: Int) {
            val status = setParameter(paramId, value)
            if (status != SUCCESS) {
                DolbyConstants.dlog(TAG, "Optional param($paramId, $value) status=$status")
            }
        }
    }

    private var sound: NativeMiSoundEffect? = null
    private var appliedEnabled: Boolean? = null
    private var lastScenario: Int? = null
    private var lastMovieMode: Boolean? = null
    private var lastSurround3d: Boolean? = null
    private var lastSurroundLevel: Int? = null
    private var lastVocalLevel: Int? = null
    private var lastEqDb: FloatArray? = null
    private var lastFailedCreateMs: Long = 0L

    private var desiredEnabled: Boolean = false
    private var basePriority: Int = priority
    private var currentPriority: Int = priority

    @Volatile
    var onRecoveryNeeded: (() -> Unit)? = null

    val isEnabled: Boolean
        @Synchronized get() {
            val fx = sound ?: return false
            return try {
                fx.hasControl() && fx.hasExpectedEnabledState(true) && appliedEnabled != false
            } catch (_: Throwable) {
                false
            }
        }

    @Synchronized
    fun hasControl(): Boolean = sound?.hasControl() == true

    @Synchronized
    fun isHealthy(): Boolean {
        val fx = sound ?: return false
        return try {
            if (!fx.hasControl()) return false
            if (appliedEnabled != true) return false
            if (!fx.hasExpectedEnabledState(true)) return false
            fx.getMusic() == 1
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
                val fx = sound ?: run {
                    appliedEnabled = false
                    invalidateParamCache()
                    return true
                }
                var controlled = if (fx.hasControl()) fx else rebindHandle() ?: return false
                controlled.setOptionalParam(DolbyConstants.MISOUND_PARAM_3D_SURROUND_ENABLE, 0)
                controlled.setOptionalParam(DolbyConstants.MISOUND_PARAM_SURROUND_LEVEL, 0)
                controlled.setOptionalParam(DolbyConstants.MISOUND_PARAM_MOVIE_MODE_ENABLE, 0)
                applyEnabled(controlled, false)
                if (controlled.hasExpectedEnabledState(true)) {
                    controlled = rebindHandle() ?: return false
                    controlled.setOptionalParam(DolbyConstants.MISOUND_PARAM_3D_SURROUND_ENABLE, 0)
                    controlled.setOptionalParam(DolbyConstants.MISOUND_PARAM_SURROUND_LEVEL, 0)
                    controlled.setOptionalParam(DolbyConstants.MISOUND_PARAM_MOVIE_MODE_ENABLE, 0)
                    applyEnabled(controlled, false)
                }
                val isOff = !controlled.hasExpectedEnabledState(true)
                if (isOff) invalidateParamCache() else appliedEnabled = null
                return isOff
            }

            val fx = ensureEffect(ignoreBackoff = true) ?: return false
            applyEnabled(fx, true)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to setEnabled($enabled)", t)
            if (!enabled) {
                appliedEnabled = false
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
                check(fx.hasControl()) { "MiSound control unavailable" }

                val nativeScenario = DolbyConstants.uiScenarioToNative(clampedScenario)
                val isMovie = clampedScenario == 2
                val effectiveSurround = if (surround3dEnabled) surroundLevel.coerceIn(0, 100) else 0
                val clampedVocal = vocalLevel.coerceIn(0, 100)
                val scenarioChanged = lastScenario != nativeScenario
                val effectiveForce = force || scenarioChanged || appliedEnabled != true

                if (appliedEnabled != true || !fx.hasExpectedEnabledState(true)) {
                    applyEnabled(fx, true)
                }

                if (effectiveForce || lastScenario != nativeScenario) {
                    fx.setScenario(nativeScenario)
                    lastScenario = nativeScenario
                }

                if (effectiveForce || lastMovieMode != isMovie) {
                    fx.setOptionalParam(DolbyConstants.MISOUND_PARAM_MOVIE_MODE_ENABLE, if (isMovie) 1 else 0)
                    lastMovieMode = isMovie
                }
                if (effectiveForce || lastSurround3d != surround3dEnabled) {
                    fx.setOptionalParam(
                        DolbyConstants.MISOUND_PARAM_3D_SURROUND_ENABLE,
                        if (surround3dEnabled) 1 else 0
                    )
                    lastSurround3d = surround3dEnabled
                }
                if (effectiveForce || lastSurroundLevel != effectiveSurround) {
                    fx.setOptionalParam(DolbyConstants.MISOUND_PARAM_SURROUND_LEVEL, effectiveSurround)
                    lastSurroundLevel = effectiveSurround
                }
                if (effectiveForce || lastVocalLevel != clampedVocal) {
                    fx.setOptionalParam(DolbyConstants.MISOUND_PARAM_VOCAL_LEVEL, clampedVocal)
                    lastVocalLevel = clampedVocal
                }

                applyEqInternal(fx, eqGainsTenths, effectiveForce)

                if (effectiveForce || !fx.hasExpectedEnabledState(true) || fx.getMusic() != 1) {
                    applyEnabled(fx, true)
                }
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "applyAllParams failed (retry=$attemptedRetry): ${t.message}")
                if (attemptedRetry) return false
                attemptedRetry = true
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
            val active = if (fx.hasControl()) fx else rebindHandle() ?: return false
            applyEqInternal(active, eqGainsTenths, force = force || !fx.hasControl())
            true
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
        val previous = sound
        sound = null
        appliedEnabled = null
        invalidateParamCache()
        if (previous == null) return
        try {
            if (previous.hasControl()) {
                try {
                    previous.setEnabled(false)
                } finally {
                    previous.setMusic(0)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot bypass MiSound before release", t)
        } finally {
            runCatching { previous.release() }
        }
    }

    @Synchronized
    fun describeState(): String {
        val fx = sound
        return try {
            "handle=${fx != null} control=${fx?.hasControl()} enabled=${fx?.hasExpectedEnabledState(true)} " +
                "desired=$desiredEnabled applied=$appliedEnabled prio=$currentPriority"
        } catch (t: Throwable) {
            "handle=${fx != null} (state query failed: ${t.message})"
        }
    }

    private fun applyEnabled(effect: NativeMiSoundEffect, enable: Boolean) {
        if (enable) {
            effect.setMusic(1)
            check(effect.setEnabled(true) == AudioEffect.SUCCESS)
            check(effect.hasExpectedEnabledState(true) && effect.getMusic() == 1) {
                "MiSound enable state did not apply"
            }
        } else {
            effect.setOptionalParam(DolbyConstants.MISOUND_PARAM_MUSIC, 0)
            effect.setOptionalParam(DolbyConstants.MISOUND_PARAM_ENABLE, 0)
            val status = effect.setParameter(DolbyConstants.MISOUND_PARAM_ENABLE, 0)
            if (status != AudioEffect.SUCCESS) {
                Log.w(TAG, "MISOUND_PARAM_ENABLE(0) returned $status")
            }
            runCatching {
                val fxBase = effect as AudioEffect
                // Call framework setEnabled(false) without throwing if already disabled.
                if (effect.hasExpectedEnabledState(true)) {
                    fxBase.setEnabled(false)
                } else {
                    fxBase.setEnabled(false)
                }
            }
        }
        appliedEnabled = enable
    }

    private fun applyEqInternal(fx: NativeMiSoundEffect, eqGainsTenths: IntArray, force: Boolean) {
        val currentCache = lastEqDb ?: FloatArray(DolbyConstants.MISOUND_EQ_BAND_COUNT) { Float.NaN }
        try {
            for (band in 0 until DolbyConstants.MISOUND_EQ_BAND_COUNT) {
                val tenths = eqGainsTenths.getOrElse(band) { 0 }
                val db = (tenths / 10f).coerceIn(
                    DolbyConstants.MISOUND_EQ_MIN_DB,
                    DolbyConstants.MISOUND_EQ_MAX_DB
                )
                val prev = currentCache[band]
                if (force || prev.isNaN() || abs(prev - db) > 0.005f) {
                    fx.setLevel(band, db)
                    currentCache[band] = db
                }
            }
            lastEqDb = currentCache
        } catch (t: Throwable) {
            lastEqDb = null
            throw t
        }
    }

    private fun ensureEffect(ignoreBackoff: Boolean = false): NativeMiSoundEffect? {
        sound?.let { existing ->
            if (existing.hasControl()) return existing
            return rebindHandle()
        }
        val now = SystemClock.elapsedRealtime()
        if (!ignoreBackoff && now - lastFailedCreateMs < CREATE_RETRY_BACKOFF_MS) {
            return null
        }
        return createHandle()
    }

    private fun createHandle(): NativeMiSoundEffect? {
        val now = SystemClock.elapsedRealtime()
        var prio = basePriority
        var created = instantiate(prio)
        val first = created
        if (first != null && !first.hasControl()) {
            Log.w(TAG, "New MiSound handle has no control at priority $prio, escalating")
            runCatching { first.release() }
            prio = priority + ESCALATED_PRIORITY_DELTA
            basePriority = prio
            created = instantiate(prio)
        }
        val fx = created
        if (fx == null) {
            lastFailedCreateMs = now
            sound = null
            return null
        }
        adopt(fx, prio)
        return fx
    }

    private fun rebindHandle(): NativeMiSoundEffect? {
        val old = sound
        val nextPriority = currentPriority + 1
        if (old != null && nextPriority <= basePriority + MAX_PRIORITY_BUMP) {
            val fresh = instantiate(nextPriority)
            if (fresh != null) {
                if (fresh.hasControl()) {
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

    private fun instantiate(prio: Int): NativeMiSoundEffect? = try {
        NativeMiSoundEffect(prio, audioSession)
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to instantiate NativeMiSoundEffect (priority=$prio)", t)
        null
    }

    private fun adopt(fx: NativeMiSoundEffect, prio: Int) {
        fx.setControlStatusListener { _, controlGranted ->
            if (controlGranted) return@setControlStatusListener
            var recover = false
            synchronized(this@MiSoundAudioEffect) {
                if (sound === fx) {
                    Log.w(TAG, "MiSoundAudioEffect lost control from AudioFlinger")
                    appliedEnabled = null
                    recover = desiredEnabled
                }
            }
            if (recover) onRecoveryNeeded?.invoke()
        }
        fx.setEnableStatusListener { _, enabled ->
            var recover = false
            synchronized(this@MiSoundAudioEffect) {
                if (sound === fx && !enabled && desiredEnabled) {
                    Log.w(TAG, "MiSoundAudioEffect disabled externally")
                    appliedEnabled = false
                    recover = true
                }
            }
            if (recover) onRecoveryNeeded?.invoke()
        }
        sound = fx
        currentPriority = prio
        appliedEnabled = null
        invalidateParamCache()
    }

    private fun dropHandleOnly() {
        sound?.let { runCatching { it.release() } }
        sound = null
        appliedEnabled = null
        invalidateParamCache()
    }

    private fun invalidateParamCache() {
        lastScenario = null
        lastMovieMode = null
        lastSurround3d = null
        lastSurroundLevel = null
        lastVocalLevel = null
        lastEqDb = null
        lastFailedCreateMs = 0L
    }

    private fun setPropSafe(key: String, value: String) {
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