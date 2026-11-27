/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

internal object ViperEffectModel {

    private const val FS = 48000.0
    const val MAX_GAIN = 150 // Dolby GEQ limit: +-15.0 dB (0.1 dB steps)

    const val MAX_BASS_FACTOR = 3.0

    const val MAX_CLARITY_GAIN = 1.5

    const val BASS_CUTOFF_HZ = 60.0

    const val BASS_NATURAL = 0
    const val BASS_PURE = 1
    const val BASS_SUBWOOFER = 2
    const val BASS_DYNAMIC = 3

    const val CLARITY_NATURAL = 0
    const val CLARITY_OZONE = 1
    const val CLARITY_XHIFI = 2

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

    private fun logBell(f: Double, centerHz: Double, widthOct: Double): Double {
        val dist = log2(f / centerHz) / widthOct
        return exp(-0.5 * dist * dist)
    }

    private fun lowShelfWeight(f: Double, cutoffHz: Double, slope: Double = 2.2): Double {
        val ratio = (f / cutoffHz).pow(slope)
        return 1.0 / (1.0 + ratio)
    }

    private fun lowPassMagnitude(f: Double, cutoffHz: Double, q: Double = 0.707): Double {
        return lowPass(cutoffHz, q).at(f).abs()
    }

    private fun polesFilterLowMag(f: Double, cutoffHz: Double): Double {
        val a = (cutoffHz * PI / FS).coerceIn(0.0001, 0.99)
        val z1 = zInv(f, 1)
        val onePole = Cx(a, 0.0) / (Cx(1.0, 0.0) - z1 * (1.0 - a))
        val m = onePole.abs()
        return m * m * m * m
    }

    private fun passFilterDb(f: Double): Double {
        val hp = iirHp(10.0).at(f).abs()
        val lpOrder3 = 1.0 / sqrt(1.0 + (f / 18000.0).pow(6.0))
        return toDb(hp * lpOrder3).coerceIn(-6.0, 0.0)
    }

    fun bassGains(
        curve: Int,
        level: Int,
        freqs: List<Int>,
        cutoffHz: Double = BASS_CUTOFF_HZ,
    ): IntArray {
        if (level <= 0) return IntArray(freqs.size)
        val mix = level.coerceIn(0, 100) / 100.0
        val clampedCurve = curve.coerceIn(0, 3)
        val t = (cutoffHz.coerceIn(15.0, 150.0) - 15.0) / 135.0
        val center = 45.0 * (260.0 / 45.0).pow(t)

        return IntArray(freqs.size) { i ->
            val f = freqs[i].toDouble()
            val gainDb = when (clampedCurve) {
                BASS_NATURAL -> {
                    val lpf = lowPassMagnitude(f, (center * 1.35).coerceAtMost(320.0), 0.53)
                    val warmth = logBell(f, (center * 1.1).coerceAtMost(240.0), 0.85)
                    val mudCut = -1.8 * mix * logBell(f, (center * 2.5).coerceAtLeast(320.0), 0.65)
                    mix * (7.5 * lpf + 3.5 * warmth) + mudCut
                }
                BASS_PURE -> {
                    val punch = logBell(f, (center * 1.25).coerceAtMost(260.0), 0.62)
                    val sub = lowPassMagnitude(f, (center * 0.95).coerceAtMost(200.0), 0.707)
                    val scoop = -4.5 * mix * logBell(f, (center * 2.1).coerceAtLeast(280.0), 0.6)
                    mix * (9.5 * punch + 5.0 * sub) + scoop
                }
                BASS_DYNAMIC -> {
                    val subBody = 8.5 * mix * lowShelfWeight(f, 165.0, slope = 2.0)
                    val chestPunch = 6.5 * mix * logBell(f, 135.0, 0.85)
                    val harmonics = 2.2 * mix * logBell(f, 240.0, 0.65)
                    val mudDip = -1.6 * mix * logBell(f, 520.0, 0.65)
                    val presence = 1.8 * mix * logBell(f, 4500.0, 1.0)
                    subBody + chestPunch + harmonics + mudDip + presence
                }
                else -> {
                    val subLpf = lowPassMagnitude(f, 50.0 + 135.0 * t, 0.707)
                    val subLpf2 = subLpf * subLpf
                    val focus = logBell(f, 45.0 + 85.0 * t, 0.6)
                    val upperCut = -4.8 * mix * logBell(f, 140.0 + 200.0 * t, 0.65)
                    mix * (12.0 * subLpf2 + 4.0 * focus) + upperCut
                }
            }
            (gainDb * 10.0).roundToInt().coerceIn(-MAX_GAIN, MAX_GAIN)
        }
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
        val mix = level.coerceIn(0, 100) / 100.0
        val gain = mix * MAX_CLARITY_GAIN
        val specHp = highPass(7600.0, 0.717)
        val specLp = lowPass(18000.0, 0.717)

        val db = when (mode) {
            CLARITY_OZONE -> {
                val shelf = highShelf(20.0 * log10(1.0 + gain), 8250.0)
                DoubleArray(freqs.size) {
                    val f = freqs[it].toDouble()
                    val air = 1.8 * mix * specHp.at(f).abs() * specLp.at(f).abs()
                    toDb(shelf.at(f).abs()) + air
                }
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
                    val air = 1.5 * mix * specHp.at(f).abs() * specLp.at(f).abs()
                    toDb(withGain.abs() / flat.abs()) + air
                }
            }
            else -> {
                val smooth = iirLp(FS / 2.0 - 1000.0)
                DoubleArray(freqs.size) {
                    val f = freqs[it].toDouble()
                    val sharpen = Cx(1.0, 0.0) + (Cx(1.0, 0.0) - zInv(f, 1)) * gain
                    val air = 1.5 * mix * specHp.at(f).abs() * specLp.at(f).abs()
                    toDb((sharpen * smooth.at(f)).abs()) + air
                }
            }
        }
        return IntArray(db.size) { (db[it] * 10.0).roundToInt().coerceIn(-MAX_GAIN, MAX_GAIN) }
    }

    fun surroundGains(widening: Int, depth: Int, freqs: List<Int>): IntArray {
        val w = widening.coerceIn(0, 100) / 100.0
        val d = depth.coerceIn(0, 100) / 100.0
        if (w <= 0.0 && d <= 0.0) return IntArray(freqs.size)
        return IntArray(freqs.size) { i ->
            val f = freqs[i].toDouble()
            val wideAir = 5.0 * w * (1.0 - lowShelfWeight(f, 4200.0, slope = 2.0))
            val wideCenter = -2.2 * w * logBell(f, 950.0, 0.75)

            val depthRoomLow = 5.2 * d * logBell(f, 110.0, 0.95)
            val depthStageRecess = -4.8 * d * logBell(f, 2400.0, 0.75)
            val depthHallAir = 5.0 * d * logBell(f, 10500.0, 0.85)

            val totalDb = wideAir + wideCenter + depthRoomLow + depthStageRecess + depthHallAir
            (totalDb * 10.0).roundToInt().coerceIn(-MAX_GAIN, MAX_GAIN)
        }
    }

    fun computeQcomBassStrength(bassLevel: Int, bassCurve: Int, dynamicLevel: Int): Int {
        val bLvl = bassLevel.coerceIn(0, 100)
        val dLvl = dynamicLevel.coerceIn(0, 100)
        val bassContrib = if (bLvl > 0) {
            val scale = when (bassCurve.coerceIn(0, 2)) {
                BASS_NATURAL -> 7.5
                BASS_PURE -> 8.8
                else -> 10.0
            }
            (bLvl * scale) / 1000.0
        } else {
            0.0
        }
        val dynContrib = if (dLvl > 0) (dLvl * 7.5) / 1000.0 else 0.0
        if (bassContrib <= 0.0 && dynContrib <= 0.0) return 0
        val blended = 1.0 - (1.0 - bassContrib.coerceIn(0.0, 1.0)) * (1.0 - dynContrib.coerceIn(0.0, 1.0))
        return (blended * 1000.0).roundToInt().coerceIn(0, 1000)
    }

    fun computeQcomVirtualizerStrength(
        surroundEnabled: Boolean,
        surroundWidening: Int,
        surroundDepth: Int,
    ): Int {
        if (!surroundEnabled) return 0
        val w = surroundWidening.coerceIn(0, 100)
        val d = surroundDepth.coerceIn(0, 100)
        return (150 + (w * 500 / 100) + (d * 350 / 100)).coerceIn(0, 1000)
    }

    fun computeEffectiveGeq(
        baseGeq: IntArray,
        freqs: List<Int>,
        bassLevel: Int,
        bassCurve: Int,
        bassCutoffHz: Int,
        dynamicLevel: Int,
        clarityLevel: Int,
        clarityMode: Int,
        speakerLevel: Int,
        surroundEnabled: Boolean,
        surroundWidening: Int,
        surroundDepth: Int,
        midLevel: Int = 0,
        @Suppress("UNUSED_PARAMETER") qcomBassStrength: Int = 0,
        qcomVirtActive: Boolean = false,
    ): IntArray {
        val result = IntArray(freqs.size) { i ->
            if (i < baseGeq.size) baseGeq[i] else 0
        }
        val bass = if (bassLevel > 0) bassGains(bassCurve, bassLevel, freqs, bassCutoffHz.toDouble()) else null
        val dynamic = if (dynamicLevel > 0) bassGains(BASS_DYNAMIC, dynamicLevel, freqs, BASS_CUTOFF_HZ) else null
        val clarity = if (clarityLevel > 0) clarityGains(clarityMode, clarityLevel, freqs) else null
        val speaker = if (speakerLevel > 0) speakerCorrectionGains(speakerLevel, freqs) else null
        val surround = if (surroundEnabled) surroundGains(surroundWidening, surroundDepth, freqs) else null
        val midGain = if (midLevel > 0) (midLevel.coerceIn(0, 100) * 1.3f).roundToInt() else 0
        val anyViperOn = bass != null || dynamic != null || clarity != null || speaker != null || surround != null

        val surroundScale = if (qcomVirtActive) 0.95 else 1.0

        for (i in result.indices) {
            val baseVal = result[i]
            var bassDelta = 0
            if (bass != null && i < bass.size) bassDelta += bass[i]
            if (dynamic != null && i < dynamic.size) bassDelta += dynamic[i]

            var sum = baseVal + bassDelta
            if (clarity != null && i < clarity.size) sum += clarity[i]
            if (speaker != null && i < speaker.size) sum += speaker[i]
            if (surround != null && i < surround.size) sum += (surround[i] * surroundScale).roundToInt()
            if (midGain > 0 && i in 5..13) sum += midGain
            if (anyViperOn) {
                sum += (passFilterDb(freqs[i].toDouble()) * 10.0).roundToInt()
            }
            result[i] = softClamp(sum)
        }
        return result
    }

    private fun softClamp(value: Int): Int {
        val absVal = kotlin.math.abs(value).toDouble()
        if (absVal <= 100.0) return value
        val sign = if (value > 0) 1.0 else -1.0
        val headroom = 40.0
        val compressed = 100.0 + headroom * tanh((absVal - 100.0) / headroom)
        return (sign * compressed).roundToInt().coerceIn(-MAX_GAIN, MAX_GAIN)
    }
}