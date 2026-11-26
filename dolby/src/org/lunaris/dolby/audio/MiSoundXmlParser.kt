/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.audio

import android.content.Context
import android.util.Log
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.R
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import kotlin.math.roundToInt

data class MiSoundProfileTuning(
    val uiScenarioId: Int,
    val nativeScenarioId: Int,
    val musicMode: Boolean,
    val movieMode: Boolean,
    val surround3dEnabled: Boolean,
    val surroundLevel: Int,
    val vocalLevel: Int,
    /** 10-band EQ gains in tenths of a dB (-120..120 = -12.0..+12.0 dB). */
    val eqGainsTenths: IntArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MiSoundProfileTuning) return false
        return uiScenarioId == other.uiScenarioId &&
            nativeScenarioId == other.nativeScenarioId &&
            musicMode == other.musicMode &&
            movieMode == other.movieMode &&
            surround3dEnabled == other.surround3dEnabled &&
            surroundLevel == other.surroundLevel &&
            vocalLevel == other.vocalLevel &&
            eqGainsTenths.contentEquals(other.eqGainsTenths)
    }

    override fun hashCode(): Int {
        var result = uiScenarioId
        result = 31 * result + nativeScenarioId
        result = 31 * result + musicMode.hashCode()
        result = 31 * result + movieMode.hashCode()
        result = 31 * result + surround3dEnabled.hashCode()
        result = 31 * result + surroundLevel
        result = 31 * result + vocalLevel
        result = 31 * result + eqGainsTenths.contentHashCode()
        return result
    }
}

/**
 * Loads Xiaomi Sound scenario defaults (Smart=0, Music=1, Movie=2) from an optional vendor XML
 * override or bundled R.raw.misound_profiles. Completely decoupled from Dolby's DaxXmlParser.
 */
object MiSoundXmlParser {

    private const val TAG = "MiSoundXmlParser"

    private val VENDOR_XML_PATHS = listOf(
        "/vendor/etc/misound/misound_profiles.xml",
        "/vendor/etc/audio/misound_profiles.xml",
        "/odm/etc/misound/misound_profiles.xml"
    )

    @Volatile
    private var cachedProfiles: Map<Int, MiSoundProfileTuning>? = null

    fun getTuning(context: Context, uiScenario: Int): MiSoundProfileTuning {
        val id = uiScenario.coerceIn(0, 2)
        val profiles = cachedProfiles ?: synchronized(this) {
            cachedProfiles ?: loadProfiles(context.applicationContext).also { cachedProfiles = it }
        }
        return profiles[id] ?: defaultFallback(id)
    }

    private fun loadProfiles(context: Context): Map<Int, MiSoundProfileTuning> {
        val vendorFile = VENDOR_XML_PATHS.map(::File).firstOrNull { it.exists() && it.canRead() }
        if (vendorFile != null) {
            runCatching {
                vendorFile.inputStream().use { parseStream(it) }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }

        return runCatching {
            context.resources.openRawResource(R.raw.misound_profiles).use { parseStream(it) }
        }.onFailure {
            Log.w(TAG, "Failed parsing R.raw.misound_profiles: ${it.message}")
        }.getOrDefault(emptyMap())
    }

    private fun parseStream(stream: InputStream): Map<Int, MiSoundProfileTuning> {
        val result = mutableMapOf<Int, MiSoundProfileTuning>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(stream, "UTF-8")

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "scenario") {
                val id = parser.getAttributeValue(null, "id")?.toIntOrNull()
                if (id != null && id in 0..2) {
                    val fallback = defaultFallback(id)
                    val nativeId = parser.getAttributeValue(null, "native_scenario")
                        ?.toIntOrNull() ?: fallback.nativeScenarioId
                    val musicMode = parser.getAttributeValue(null, "music_mode")
                        ?.let { it == "1" || it.equals("true", ignoreCase = true) }
                        ?: fallback.musicMode
                    val movieMode = parser.getAttributeValue(null, "movie_mode")
                        ?.let { it == "1" || it.equals("true", ignoreCase = true) }
                        ?: fallback.movieMode
                    val surround3d = parser.getAttributeValue(null, "surround_3d")
                        ?.let { it == "1" || it.equals("true", ignoreCase = true) }
                        ?: fallback.surround3dEnabled
                    val surroundLevel = parser.getAttributeValue(null, "surround_level")
                        ?.toIntOrNull()?.coerceIn(0, 100) ?: fallback.surroundLevel
                    val vocalLevel = parser.getAttributeValue(null, "vocal_level")
                        ?.toIntOrNull()?.coerceIn(0, 100) ?: fallback.vocalLevel

                    val rawEq = parser.getAttributeValue(null, "eq_db").orEmpty()
                    val parsedDb = rawEq.split(",", " ")
                        .mapNotNull { token -> token.trim().takeIf { it.isNotEmpty() }?.toFloatOrNull() }
                    val eqTenths = if (parsedDb.size == DolbyConstants.MISOUND_EQ_BAND_COUNT) {
                        IntArray(DolbyConstants.MISOUND_EQ_BAND_COUNT) { idx ->
                            (parsedDb[idx] * 10f).roundToInt()
                                .coerceIn(DolbyConstants.MISOUND_EQ_MIN_TENTHS, DolbyConstants.MISOUND_EQ_MAX_TENTHS)
                        }
                    } else {
                        fallback.eqGainsTenths
                    }

                    result[id] = MiSoundProfileTuning(
                        uiScenarioId = id,
                        nativeScenarioId = nativeId,
                        musicMode = musicMode,
                        movieMode = movieMode,
                        surround3dEnabled = surround3d,
                        surroundLevel = surroundLevel,
                        vocalLevel = vocalLevel,
                        eqGainsTenths = eqTenths,
                    )
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun defaultFallback(uiScenario: Int): MiSoundProfileTuning = when (uiScenario.coerceIn(0, 2)) {
        1 -> MiSoundProfileTuning(
            uiScenarioId = 1,
            nativeScenarioId = DolbyConstants.MISOUND_SCENARIO_MUSIC,
            musicMode = true,
            movieMode = false,
            surround3dEnabled = false,
            surroundLevel = 35,
            vocalLevel = 0,
            eqGainsTenths = intArrayOf(35, 25, 10, 0, -5, 5, 15, 25, 30, 20),
        )
        2 -> MiSoundProfileTuning(
            uiScenarioId = 2,
            nativeScenarioId = DolbyConstants.MISOUND_SCENARIO_MOVIE,
            musicMode = false,
            movieMode = true,
            surround3dEnabled = true,
            surroundLevel = 65,
            vocalLevel = 35,
            eqGainsTenths = intArrayOf(45, 30, 10, -5, 5, 20, 25, 20, 25, 15),
        )
        else -> MiSoundProfileTuning(
            uiScenarioId = 0,
            nativeScenarioId = DolbyConstants.MISOUND_SCENARIO_SMART,
            musicMode = true,
            movieMode = false,
            surround3dEnabled = true,
            surroundLevel = 45,
            vocalLevel = 20,
            eqGainsTenths = intArrayOf(20, 15, 10, 5, 0, 0, 5, 10, 15, 10),
        )
    }
}
