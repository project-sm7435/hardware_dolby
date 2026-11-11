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

    const val CLARITY_NATURAL = 0
    const val CLARITY_OZONE = 1

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

    private fun toDb(m: Double): Double = if (m <= 1e-9) -60.0 else 20.0 * log10(m)

    private fun toGeq(db: DoubleArray): IntArray {
        val ref = db.last() 
        return IntArray(db.size) { ((db[it] - ref) * 10.0).roundToInt().coerceIn(-MAX_GAIN, MAX_GAIN) }
    }

    /**
     * GEQ gains (0.1 dB) for the ViPER bass mode [curve] at slider [level] (0..100).
     */
    fun bassGains(curve: Int, level: Int, freqs: List<Int>): IntArray {
        if (level <= 0) return IntArray(freqs.size)
        val factor = level / 100.0 * MAX_BASS_FACTOR

        val db = when (curve) {
            // out = in + factor * LPF(in)   (LPF: 2nd order, Q = 0.53, fc = speaker)
            BASS_NATURAL -> {
                val lp = lowPass(BASS_CUTOFF_HZ, 0.53)
                DoubleArray(freqs.size) {
                    val h = lp.at(freqs[it].toDouble())
                    toDb((Cx(1.0, 0.0) + h * factor).abs())
                }
            }
            // Bass path is time-aligned (delayed) with the dry path -> magnitudes add coherently
            BASS_PURE -> {
                val lp = lowPass(BASS_CUTOFF_HZ, 0.53)
                DoubleArray(freqs.size) {
                    toDb(1.0 + factor * lp.at(freqs[it].toDouble()).abs())
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
            // NoiseSharpening: (1 + g(1 - z^-1)) * (K/(1+K) + z^-1/(1+K)), K = tan(pi(fs/2-1000)/fs)
            else -> {
                val k = tan(PI * (FS / 2.0 - 1000.0) / FS)
                DoubleArray(freqs.size) {
                    val w = 2.0 * PI * freqs[it] / FS
                    val z1 = Cx(cos(w), -sin(w))
                    val sharpen = Cx(1.0, 0.0) + (Cx(1.0, 0.0) - z1) * gain
                    val smooth = Cx(k / (1 + k), 0.0) + z1 * (1.0 / (1 + k))
                    toDb((sharpen * smooth).abs())
                }
            }
        }
        // Natural/Ozone are already 0 dB at DC; no top-band normalisation here
        return IntArray(db.size) { (db[it] * 10.0).roundToInt().coerceIn(-MAX_GAIN, MAX_GAIN) }
    }
}
