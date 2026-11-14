/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lunaris.dolby.audio.ViperAudioEffect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Bridge layer connecting Dolby Advanced Settings UI -> native C++ implementations in
 * dolby/viper/effects/ + dolby/viper/utils/ -> DSP Engine (libdolby_viper_jni.so & vendor libv4a_re.so) -> Audio Output.
 */
internal object ViperEffectModel {

    private const val TAG = "ViperEffectModel"
    private const val FS = 48000.0
    private const val MAX_GAIN = 150 // GEQ fallback limit, 15.0 dB

    /** Bass slider 100% maps to this ViPER "bass factor". */
    const val MAX_BASS_FACTOR = 3.0

    /** Treble slider 100% maps to this ViPER clarity gain. */
    const val MAX_CLARITY_GAIN = 1.5

    /** ViPERBass default crossover ("speaker") frequency. */
    const val BASS_CUTOFF_HZ = 60.0

    const val BASS_NATURAL = 0
    const val BASS_PURE = 1
    const val BASS_SUBWOOFER = 2
    const val BASS_DYNAMIC = 3

    const val CLARITY_NATURAL = 0
    const val CLARITY_OZONE = 1
    const val CLARITY_XHIFI = 2

    // Command IDs shared with ViperDspEngine (dolby/viper/viper_dsp_engine.cpp) and vendor libv4a_re.so
    const val PARAM_DYNAMIC_ENABLE   = 0x10021
    const val PARAM_DYNAMIC_X_COEFFS = 0x10022
    const val PARAM_DYNAMIC_Y_COEFFS = 0x10023
    const val PARAM_DYNAMIC_SIDEGAIN = 0x10024
    const val PARAM_DYNAMIC_STRENGTH = 0x10025
    const val PARAM_BASS_ENABLE      = 0x10026
    const val PARAM_BASS_MODE        = 0x10027
    const val PARAM_BASS_FREQUENCY   = 0x10028
    const val PARAM_BASS_GAIN        = 0x10029
    const val PARAM_CLARITY_ENABLE   = 0x1002A
    const val PARAM_CLARITY_MODE     = 0x1002B
    const val PARAM_CLARITY_GAIN     = 0x1002C
    const val PARAM_SPEAKER_ENABLE   = 0x10043 // PARAM_SPEAKER_OPTIMIZATION in ViPER.cpp
    const val PARAM_SPEAKER_STRENGTH = 0x1002E

    enum class EffectType(val displayName: String, val cppImplementation: String) {
        VIPER_BASS("ViPER Bass", "dolby/viper/effects/ViPERBass.cpp"),
        VIPER_CLARITY("ViPER Clarity", "dolby/viper/effects/ViPERClarity.cpp"),
        DYNAMIC_SYSTEM("Dynamic System", "dolby/viper/effects/DynamicSystem.cpp + utils/DynamicBass.cpp"),
        SPEAKER_CORRECTION("Speaker Correction", "dolby/viper/effects/SpeakerCorrection.cpp")
    }

    data class DspPipelineState(
        val bassEnabled: Boolean = false,
        val bassMode: Int = BASS_NATURAL,
        val bassFrequencyHz: Int = 60,
        val bassGainPercent: Int = 0,
        val clarityEnabled: Boolean = false,
        val clarityMode: Int = CLARITY_NATURAL,
        val clarityGainPercent: Int = 0,
        val dynamicEnabled: Boolean = false,
        val dynamicStrengthPercent: Int = 0,
        val speakerCorrectionEnabled: Boolean = false,
        val speakerStrengthPercent: Int = 0,
        val nativeLibraryLoaded: Boolean = false
    )

    private var isNativeLoaded = false

    init {
        isNativeLoaded = try {
            System.loadLibrary("dolby_viper_jni")
            nativeInit(FS.toInt())
            Log.i(TAG, "Loaded native libdolby_viper_jni.so (Effects + Utils DSP Engine)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Native libdolby_viper_jni.so not loaded (${t.message}); using vendor AudioEffect HAL")
            false
        }
    }

    private val _pipelineState = MutableStateFlow(DspPipelineState(nativeLibraryLoaded = isNativeLoaded))
    val pipelineState: StateFlow<DspPipelineState> = _pipelineState.asStateFlow()

    /**
     * Identifies selected effects from Dolby Advanced Settings, resolves their C++ implementations
     * from dolby/viper/effects/ + dolby/viper/utils/, and dispatches parameters to both the
     * system AudioEffect DSP engine (vendor libv4a_re.so) and the in-process JNI DSP engine
     * (libdolby_viper_jni.so).
     */
    fun applyToDspEngine(
        effect: ViperAudioEffect?,
        settings: ViperController.Settings,
        masterEnabled: Boolean
    ): Boolean {
        val bassActive = masterEnabled && settings.bassOn
        val clarityActive = masterEnabled && settings.clarityOn
        val dynamicActive = masterEnabled && settings.dynamicOn
        val speakerActive = masterEnabled && settings.speakerCorrectionOn

        val bassMode = settings.bassMode.coerceIn(0, 2)
        val bassFreq = settings.bassFrequency.coerceIn(15, 150)
        // Scale 0..100 slider -> 0..300 (0.00f..3.00f bassFactor in ViPERBass::SetBassFactor)
        val bassFactorScaled = ((settings.bassGain / 100.0) * MAX_BASS_FACTOR * 100.0).roundToInt()

        val clarityMode = settings.clarityMode.coerceIn(0, 2)
        // Scale 0..100 slider -> 0..150 (0.00f..1.50f clarityGain in ViPERClarity::SetClarity)
        val clarityGainScaled = ((settings.clarityGain / 100.0) * MAX_CLARITY_GAIN * 100.0).roundToInt()

        val dynamicStrength = settings.dynamicStrength.coerceIn(0, 100)
        // Map 0..100% slider to ViPER DynamicBass gain range: 100..2100 (1.0f..21.0f -> qPeak 0..1600)
        val dynamicBassGainScaled = 100 + (dynamicStrength * 20)
        val speakerStrength = settings.speakerStrength.coerceIn(0, 100)

        _pipelineState.value = DspPipelineState(
            bassEnabled = bassActive,
            bassMode = bassMode,
            bassFrequencyHz = bassFreq,
            bassGainPercent = settings.bassGain,
            clarityEnabled = clarityActive,
            clarityMode = clarityMode,
            clarityGainPercent = settings.clarityGain,
            dynamicEnabled = dynamicActive,
            dynamicStrengthPercent = dynamicStrength,
            speakerCorrectionEnabled = speakerActive,
            speakerStrengthPercent = speakerStrength,
            nativeLibraryLoaded = isNativeLoaded
        )

        data class DspCmd(val id: Int, val v1: Int, val v2: Int = 0)

        // Full parameter set matching ViPER::DispatchCommand in vendor libv4a_re.so & viper_dsp_engine.cpp
        val commands = listOf(
            DspCmd(PARAM_BASS_MODE, bassMode),
            DspCmd(PARAM_BASS_FREQUENCY, bassFreq),
            DspCmd(PARAM_BASS_GAIN, bassFactorScaled),
            DspCmd(PARAM_BASS_ENABLE, if (bassActive) 1 else 0),
            DspCmd(PARAM_CLARITY_MODE, clarityMode),
            DspCmd(PARAM_CLARITY_GAIN, clarityGainScaled),
            DspCmd(PARAM_CLARITY_ENABLE, if (clarityActive) 1 else 0),
            // Dynamic System (Extreme Headphone v2 profile: X=140..6200 Hz, Y=40..12000 Hz, SideGain=100,100)
            DspCmd(PARAM_DYNAMIC_X_COEFFS, 140, 6200),
            DspCmd(PARAM_DYNAMIC_Y_COEFFS, 40, 12000),
            DspCmd(PARAM_DYNAMIC_SIDEGAIN, 100, 100),
            DspCmd(PARAM_DYNAMIC_STRENGTH, dynamicBassGainScaled),
            DspCmd(PARAM_DYNAMIC_ENABLE, if (dynamicActive) 1 else 0),
            DspCmd(PARAM_SPEAKER_STRENGTH, speakerStrength),
            DspCmd(PARAM_SPEAKER_ENABLE, if (speakerActive) 1 else 0)
        )

        // 1. Dispatch directly to native C++ Effects + Utils via JNI (libdolby_viper_jni.so)
        if (isNativeLoaded) {
            for (cmd in commands) {
                nativeDispatchCommand(cmd.id, cmd.v1, cmd.v2, 0, 0)
            }
        }

        // 2. Dispatch to vendor AudioEffect instance (libv4a_re.so) attached to AudioFlinger output
        if (effect == null) return false
        var allOk = true
        for (cmd in commands) {
            val sent = effect.send(cmd.id, cmd.v1, cmd.v2)
            if (!sent && cmd.id != PARAM_SPEAKER_STRENGTH) {
                allOk = false
            }
        }
        return allOk
    }

    // --- Direct JNI bindings to dolby/viper/viper_dsp_engine.cpp ---
    private external fun nativeInit(sampleRate: Int)
    private external fun nativeDispatchCommand(param: Int, v1: Int, v2: Int, v3: Int, v4: Int)
    external fun nativeProcessBuffer(buffer: FloatArray, frames: Int)
    external fun nativeRelease()

    // =========================================================================
    // Fallback GEQ Curve Synthesis (used ONLY if vendor libv4a_re.so is unavailable)
    // =========================================================================

    private const val DYNAMIC_BASS_MAX_GAIN = 11.0

    private class Cx(val re: Double, val im: Double) {
        operator fun plus(o: Cx) = Cx(re + o.re, im + o.im)
        operator fun minus(o: Cx) = Cx(re - o.re, im - o.im)
        operator fun times(o: Cx) = Cx(re * o.re - im * o.im, re * o.im + im * o.re)
        operator fun times(k: Double) = Cx(re * k, im * k)
        operator fun div(o: Cx): Cx {
            val d = o.re * o.re + o.im * o.im
            return Cx((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
        }
        fun abs() = sqrt(re * re + im * im)
    }

    private class Biquad(
        val b0: Double, val b1: Double, val b2: Double,
        val a0: Double, val a1: Double, val a2: Double
    ) {
        fun at(f: Double): Cx {
            val w = 2.0 * PI * f / FS
            val z1 = Cx(cos(w), -sin(w))
            val z2 = z1 * z1
            val num = Cx(b0, 0.0) + z1 * b1 + z2 * b2
            val den = Cx(a0, 0.0) + z1 * a1 + z2 * a2
            return num / den
        }
    }

    private fun lowPass(fc: Double, q: Double): Biquad {
        val w = 2.0 * PI * fc / FS
        val c = cos(w)
        val alpha = sin(w) / (2.0 * q)
        return Biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    private fun peak(gainDb: Double, fc: Double, bwOct: Double): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * PI * fc / FS
        val s = sin(w)
        val c = cos(w)
        val alpha = s * sinh(ln(2.0) / 2.0 * bwOct * w / s)
        return Biquad(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }

    private fun highShelf(gainDb: Double, fc: Double): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * PI * fc / FS
        val c = cos(w)
        val t = sin(w) * sqrt(2.0 * a)
        return Biquad(
            a * ((a + 1) + (a - 1) * c + t),
            -2 * a * ((a - 1) + (a + 1) * c),
            a * ((a + 1) + (a - 1) * c - t),
            (a + 1) - (a - 1) * c + t,
            2 * ((a - 1) - (a + 1) * c),
            (a + 1) - (a - 1) * c - t
        )
    }

    private fun highPass(fc: Double, q: Double): Biquad {
        val w = 2.0 * PI * fc / FS
        val c = cos(w)
        val alpha = sin(w) / (2.0 * q)
        return Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    private fun bandPass(fc: Double, q: Double): Biquad {
        val w = 2.0 * PI * fc / FS
        val s = sin(w)
        val alpha = s / (2.0 * q)
        return Biquad(s / 2, 0.0, -s / 2, 1 + alpha, -2 * cos(w), 1 - alpha)
    }

    private class Iir1(val b0: Double, val b1: Double, val a1: Double) {
        fun at(f: Double): Cx {
            val z1 = zInv(f, 1)
            return (Cx(b0, 0.0) + z1 * b1) / (Cx(1.0, 0.0) - z1 * a1)
        }
    }

    private fun iirLp(fc: Double): Iir1 {
        val t = tan(PI * fc / FS)
        return Iir1(t / (1 + t), t / (1 + t), (1 - t) / (1 + t))
    }

    private fun iirHp(fc: Double): Iir1 {
        val t = tan(PI * fc / FS)
        val b0 = 1.0 / (1 + t)
        return Iir1(b0, -b0, (1 - t) / (1 + t))
    }

    private fun zInv(f: Double, n: Int): Cx {
        val w = 2.0 * PI * f / FS * n
        return Cx(cos(w), -sin(w))
    }

    private fun cube(c: Cx): Cx = c * c * c

    private fun toDb(m: Double): Double = if (m <= 1e-9) -60.0 else 20.0 * log10(m)

    private fun toGeq(db: DoubleArray): IntArray {
        val ref = db.last()
        return IntArray(db.size) { ((db[it] - ref) * 10.0).roundToInt().coerceIn(-MAX_GAIN, MAX_GAIN) }
    }

    fun bassGains(
        curve: Int,
        level: Int,
        freqs: List<Int>,
        cutoffHz: Double = BASS_CUTOFF_HZ,
    ): IntArray {
        if (level <= 0) return IntArray(freqs.size)
        val factor = level / 100.0 * MAX_BASS_FACTOR

        val db = when (curve) {
            BASS_NATURAL -> {
                val lp = lowPass(cutoffHz, 0.53)
                DoubleArray(freqs.size) {
                    val h = lp.at(freqs[it].toDouble())
                    toDb((Cx(1.0, 0.0) + h * factor).abs())
                }
            }
            BASS_PURE -> {
                val lp = lowPass(cutoffHz, 0.53)
                DoubleArray(freqs.size) {
                    toDb(1.0 + factor * lp.at(freqs[it].toDouble()).abs())
                }
            }
            BASS_DYNAMIC -> {
                val mix = level / 100.0
                val bassGain = 1.0 + (DYNAMIC_BASS_MAX_GAIN - 1.0) * mix
                val x = (((bassGain - 1.0) / 20.0) * 1600.0).coerceAtMost(1600.0)
                val q = 0.5 + x / 666.0
                val lp = lowPass(55.0, q)
                DoubleArray(freqs.size) {
                    toDb((Cx(1.0, 0.0) + lp.at(freqs[it].toDouble()) * (2.0 * mix)).abs())
                }
            }
            else -> {
                val g = factor * 2.5
                val peakLow = peak(20.0 * log10(g), 44.0, 0.75)
                val peakHigh = peak(20.0 * log10(g / 8.0), 80.0, 0.2)
                val lp = lowPass(380.0, 0.6)
                DoubleArray(freqs.size) {
                    val f = freqs[it].toDouble()
                    val chain = peakLow.at(f) * peakHigh.at(f)
                    val out = Cx(0.5, 0.0) + lp.at(f) * (chain - Cx(1.0, 0.0)) * 0.6
                    toDb(out.abs())
                }
            }
        }
        return toGeq(db)
    }

    fun speakerCorrectionGains(level: Int, freqs: List<Int>): IntArray {
        if (level <= 0) return IntArray(freqs.size)
        val scale = level / 100.0
        val hp = highPass(80.0, 1.0)
        val lp = lowPass(13500.0, 1.0)
        val bp = bandPass(420.0, 3.88)
        return IntArray(freqs.size) {
            val f = freqs[it].toDouble()
            val h = hp.at(f) * lp.at(f) * (Cx(1.0, 0.0) + bp.at(f))
            (toDb(h.abs()) * scale * 10.0).roundToInt().coerceIn(-MAX_GAIN, MAX_GAIN)
        }
    }

    fun clarityGains(mode: Int, level: Int, freqs: List<Int>): IntArray {
        if (level <= 0) return IntArray(freqs.size)
        val gain = level / 100.0 * MAX_CLARITY_GAIN

        val db = when (mode) {
            CLARITY_OZONE -> {
                val shelf = highShelf(20.0 * log10(1.0 + gain), 8250.0)
                DoubleArray(freqs.size) { toDb(shelf.at(freqs[it].toDouble()).abs()) }
            }
            CLARITY_XHIFI -> {
                val g = 1.0 + gain
                val lp = iirLp(120.0)
                val hpLow = iirHp(120.0)
                val hpHigh = iirHp(1200.0)
                val lpHigh = iirLp(1200.0)
                val delayBp = (FS / 400.0).toInt()
                val delayLp = (FS / 200.0).toInt()
                DoubleArray(freqs.size) {
                    val f = freqs[it].toDouble()
                    val low = lp.at(f) * zInv(f, delayLp)
                    val band = cube(hpLow.at(f)) * cube(lpHigh.at(f)) * zInv(f, delayBp)
                    val high = cube(hpHigh.at(f))
                    val withGain = low + band * g + high * (1.2 * g)
                    val flat = low + band + high * 1.2
                    toDb(withGain.abs() / flat.abs())
                }
            }
            else -> {
                val smooth = iirLp(FS / 2.0 - 1000.0)
                DoubleArray(freqs.size) {
                    val f = freqs[it].toDouble()
                    val sharpen = Cx(1.0, 0.0) + (Cx(1.0, 0.0) - zInv(f, 1)) * gain
                    toDb((sharpen * smooth.at(f)).abs())
                }
            }
        }
        return IntArray(db.size) { (db[it] * 10.0).roundToInt().coerceIn(-MAX_GAIN, MAX_GAIN) }
    }
}