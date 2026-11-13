/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.ln

internal object ViperEffectModel {

    private const val FS = 48000.0
    private const val MAX_GAIN = 150 // GEQ limit, 15.0 dB

    /** Bass slider 100% maps to this ViPER "bass factor" (tunable). */
    const val MAX_BASS_FACTOR = 3.0

    /** Treble slider 100% maps to this ViPER clarity gain (tunable). */
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

    /** DynamicBass: bass gain at 100% (resonance Q = 0.5 + 0.12 * (gain - 1)). */
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
            val z1 = Cx(cos(w), -sin(w))          // z^-1
            val z2 = z1 * z1                       // z^-2
            val num = Cx(b0, 0.0) + z1 * b1 + z2 * b2
            val den = Cx(a0, 0.0) + z1 * a1 + z2 * a2
            return num / den
        }
    }

    // RBJ low-pass, Q form (Biquad::SetLowPassParameter / MultiBiquad LOW_PASS, bandwidth=false)
    private fun lowPass(fc: Double, q: Double): Biquad {
        val w = 2.0 * PI * fc / FS
        val c = cos(w)
        val alpha = sin(w) / (2.0 * q)
        return Biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    // RBJ peaking EQ, bandwidth-in-octaves form (MultiBiquad PEAK, bandwidth=true)
    private fun peak(gainDb: Double, fc: Double, bwOct: Double): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w = 2.0 * PI * fc / FS
        val s = sin(w)
        val c = cos(w)
        val alpha = s * sinh(ln(2.0) / 2.0 * bwOct * w / s)
        return Biquad(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }

    // RBJ high shelf, S = 1 (HighShelf class)
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

    // RBJ high-pass, Q form (MultiBiquad HIGH_PASS, bandwidth = false)
    private fun highPass(fc: Double, q: Double): Biquad {
        val w = 2.0 * PI * fc / FS
        val c = cos(w)
        val alpha = sin(w) / (2.0 * q)
        return Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    // Biquad::SetBandPassParameter: b0 = sin(w)/2, b2 = -sin(w)/2 (peak gain equals Q)
    private fun bandPass(fc: Double, q: Double): Biquad {
        val w = 2.0 * PI * fc / FS
        val s = sin(w)
        val alpha = s / (2.0 * q)
        return Biquad(s / 2, 0.0, -s / 2, 1 + alpha, -2 * cos(w), 1 - alpha)
    }

    // First-order section of IIR_1st: y = b0*x + s ; s' = b1*x + a1*y  ->  H = (b0 + b1 z^-1) / (1 - a1 z^-1)
    private class Iir1(val b0: Double, val b1: Double, val a1: Double) {
        fun at(f: Double): Cx {
            val z1 = zInv(f, 1)
            return (Cx(b0, 0.0) + z1 * b1) / (Cx(1.0, 0.0) - z1 * a1)
        }
    }

    // IIR_1st::setLPF_BW
    private fun iirLp(fc: Double): Iir1 {
        val t = tan(PI * fc / FS)
        return Iir1(t / (1 + t), t / (1 + t), (1 - t) / (1 + t))
    }

    // IIR_1st::setHPF_BW
    private fun iirHp(fc: Double): Iir1 {
        val t = tan(PI * fc / FS)
        val b0 = 1.0 / (1 + t)
        return Iir1(b0, -b0, (1 - t) / (1 + t))
    }

    // z^-n evaluated at f
    private fun zInv(f: Double, n: Int): Cx {
        val w = 2.0 * PI * f / FS * n
        return Cx(cos(w), -sin(w))
    }

    private fun cube(c: Cx): Cx = c * c * c

    private fun toDb(m: Double): Double = if (m <= 1e-9) -60.0 else 20.0 * log10(m)

    private fun toGeq(db: DoubleArray): IntArray {
        val ref = db.last() // keep the top band at 0 dB so overall volume is untouched
        return IntArray(db.size) { ((db[it] - ref) * 10.0).roundToInt().coerceIn(-MAX_GAIN, MAX_GAIN) }
    }

    /**
     * GEQ gains (0.1 dB) for the ViPER bass mode [curve] at slider [level] (0..100).
     */
    fun bassGains(
        curve: Int,
        level: Int,
        freqs: List<Int>,
        cutoffHz: Double = BASS_CUTOFF_HZ,
    ): IntArray {
        if (level <= 0) return IntArray(freqs.size)
        val factor = level / 100.0 * MAX_BASS_FACTOR

        val db = when (curve) {
            // out = in + factor * LPF(in)   (LPF: 2nd order, Q = 0.53, fc = speaker)
            BASS_NATURAL -> {
                val lp = lowPass(cutoffHz, 0.53)
                DoubleArray(freqs.size) {
                    val h = lp.at(freqs[it].toDouble())
                    toDb((Cx(1.0, 0.0) + h * factor).abs())
                }
            }
            // Bass path is time-aligned (delayed) with the dry path -> magnitudes add coherently
            BASS_PURE -> {
                val lp = lowPass(cutoffHz, 0.53)
                DoubleArray(freqs.size) {
                    toDb(1.0 + factor * lp.at(freqs[it].toDouble()).abs())
                }
            }
            // DynamicBass: out = in + mono(L+R) * LPF55(Q) -> for centred content 1 + 2 * LPF
            BASS_DYNAMIC -> {
                val mix = level / 100.0
                val q = 0.5 + 0.12 * (DYNAMIC_BASS_MAX_GAIN - 1.0) * mix
                val lp = lowPass(55.0, q)
                DoubleArray(freqs.size) {
                    toDb((Cx(1.0, 0.0) + lp.at(freqs[it].toDouble()) * (2.0 * mix)).abs())
                }
            }
            // Subwoofer: 0.5*x + 0.6 * LP380( peak44(x) -> peak80 - x )
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

    /**
     * ViPER SpeakerCorrection: out = 0.5 * x_f * (1 + BP420), x_f = HP80(LP13500(x)).
     * Cuts sub-bass, lifts ~420 Hz by up to ~12 dB and rolls off the top. [level] (0..100)
     * scales the correction in dB; 100 is the full ViPER curve.
     */
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

    /**
     * GEQ gains (0.1 dB) for ViPER Clarity at slider [level] (0..100).
     */
    fun clarityGains(mode: Int, level: Int, freqs: List<Int>): IntArray {
        if (level <= 0) return IntArray(freqs.size)
        val gain = level / 100.0 * MAX_CLARITY_GAIN

        val db = when (mode) {
            // High shelf @ 8250 Hz, +20*log10(1 + gain) dB
            CLARITY_OZONE -> {
                val shelf = highShelf(20.0 * log10(1.0 + gain), 8250.0)
                DoubleArray(freqs.size) { toDb(shelf.at(freqs[it].toDouble()).abs()) }
            }
            // HiFi (XHiFi): out = 1.2*g*HP1200^3 + g*BP + LP120 delayed fs/200,  g = 1 + clarity
            // (each "order 3" filter is 3 cascaded 1st-order sections; BP = HP120^3 * LP1200^3,
            // delayed fs/400). Shown relative to g = 1 so the slider at 0 is flat.
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
            // NoiseSharpening: x + g*(x - x[-1]) -> IIR_1st setLPF_BW(fs/2 - 1000)
            else -> {
                val smooth = iirLp(FS / 2.0 - 1000.0)
                DoubleArray(freqs.size) {
                    val f = freqs[it].toDouble()
                    val sharpen = Cx(1.0, 0.0) + (Cx(1.0, 0.0) - zInv(f, 1)) * gain
                    toDb((sharpen * smooth.at(f)).abs())
                }
            }
        }
        // Natural/Ozone are already 0 dB at DC; no top-band normalisation here
        return IntArray(db.size) { (db[it] * 10.0).roundToInt().coerceIn(-MAX_GAIN, MAX_GAIN) }
    }
}
