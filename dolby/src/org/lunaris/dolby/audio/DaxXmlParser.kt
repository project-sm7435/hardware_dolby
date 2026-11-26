/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.audio

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File

data class DaxProfileTuning(
    val daxProfileId: Int,
    val geqBandGains: IntArray,
    val bassEnhancerEnable: Boolean,
    val headphoneVirtualizer: Boolean,
    val speakerVirtualizer: Boolean,
    val stereoWideningAmount: Int,
    val dialogueEnhancerEnable: Boolean,
    val dialogueEnhancerAmount: Int,
)

/**
 * Parses /vendor/etc/dolby/dax-default.xml for Dolby Atmos profile metadata/tuning.
 * (Xiaomi Sound uses MiSoundXmlParser + R.raw.misound_profiles exclusively.)
 */
object DaxXmlParser {
    private const val TAG = "DaxXmlParser"
    private val XML_PATHS = listOf(
        "/vendor/etc/dolby/dax-default.xml",
        "/odm/etc/dolby/dax-default.xml",
        "/system/vendor/etc/dolby/dax-default.xml"
    )

    @Volatile
    private var cachedProfiles: Map<Int, DaxProfileTuning>? = null

    /**
     * Maps Xiaomi Sound UI scenario index (0=Smart, 1=Music, 2=Movie)
     * to dax-default.xml profile ID (0=Dynamic/Smart, 2=Music, 1=Movie).
     */
    fun scenarioToDaxProfileId(scenario: Int): Int = when (scenario) {
        0 -> 0 // Smart -> Dynamic
        1 -> 2 // Music -> Music
        2 -> 1 // Movie -> Movie
        else -> 0
    }

    fun getTuningForScenario(scenario: Int): DaxProfileTuning {
        val targetId = scenarioToDaxProfileId(scenario)
        val profiles = cachedProfiles ?: loadProfiles().also { cachedProfiles = it }
        return profiles[targetId] ?: defaultFallback(targetId)
    }

    private fun loadProfiles(): Map<Int, DaxProfileTuning> {
        val file = XML_PATHS.map(::File).firstOrNull { it.exists() && it.canRead() }
            ?: return emptyMap()

        val geqPresets = mutableMapOf<Int, IntArray>()
        val result = mutableMapOf<Int, DaxProfileTuning>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            file.inputStream().use { stream ->
                parser.setInput(stream, "UTF-8")
                var currentProfileId = -1
                var currentEndpoint: String? = null
                var currentPresetId = -1
                var currentPresetGains = mutableListOf<Int>()
                var geq = IntArray(20) { 0 }
                var bass = false
                var hpVirt = false
                var spkVirt = false
                var widening = 32
                var dlgEnable = false
                var dlgAmount = 6

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            when (parser.name) {
                                "preset" -> {
                                    if (parser.getAttributeValue(null, "type") == "geq") {
                                        currentPresetId = parser.getAttributeValue(null, "id")?.toIntOrNull() ?: -1
                                        currentPresetGains = mutableListOf()
                                    }
                                }
                                "band_geq" -> if (currentPresetId >= 0) {
                                    val gain = parser.getAttributeValue(null, "gain")?.toIntOrNull() ?: 0
                                    currentPresetGains.add(gain)
                                }
                                "profile" -> {
                                    currentProfileId = parser.getAttributeValue(null, "id")?.toIntOrNull() ?: -1
                                    currentEndpoint = null
                                    geq = IntArray(20) { 0 }
                                    bass = false
                                    hpVirt = false
                                    spkVirt = false
                                    widening = 32
                                    dlgEnable = false
                                    dlgAmount = 6
                                }
                                "endpoint_type" -> if (currentProfileId >= 0) {
                                    currentEndpoint = parser.getAttributeValue(null, "id")
                                }
                                "bass-enhancer-enable" -> if (currentProfileId >= 0 &&
                                    (currentEndpoint == "headphone" || currentEndpoint == "speaker")
                                ) {
                                    bass = bass || parser.getAttributeValue(null, "value") == "true"
                                }
                                "virtualizer-enable" -> if (currentProfileId >= 0) {
                                    val v = parser.getAttributeValue(null, "value") == "true"
                                    when (currentEndpoint) {
                                        "headphone" -> hpVirt = v
                                        "speaker" -> spkVirt = v
                                    }
                                }
                                "surround-boost" -> if (currentProfileId >= 0 && currentEndpoint == "headphone") {
                                    val boost = parser.getAttributeValue(null, "value")?.toIntOrNull()
                                    if (boost != null && boost > 0) widening = boost
                                }
                                "dialog-enhancer-enable" -> if (currentProfileId >= 0 &&
                                    (currentEndpoint == "headphone" || currentEndpoint == "speaker")
                                ) {
                                    dlgEnable = dlgEnable || parser.getAttributeValue(null, "value") == "true"
                                }
                                "dialog-enhancer-amount" -> if (currentProfileId >= 0 &&
                                    (currentEndpoint == "headphone" || currentEndpoint == "speaker")
                                ) {
                                    dlgAmount = parser.getAttributeValue(null, "value")?.toIntOrNull() ?: dlgAmount
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (parser.name == "preset" && currentPresetId >= 0) {
                                if (currentPresetGains.size >= 20) {
                                    geqPresets[currentPresetId] = currentPresetGains.take(20).toIntArray()
                                }
                                currentPresetId = -1
                            } else if (parser.name == "endpoint_type") {
                                currentEndpoint = null
                            } else if (parser.name == "profile" && currentProfileId >= 0) {
                                val fallback = defaultFallback(currentProfileId)
                                val xmlGeq = geqPresets[currentProfileId]
                                geq = if (xmlGeq != null && xmlGeq.any { it != 0 }) xmlGeq else fallback.geqBandGains
                                result[currentProfileId] = DaxProfileTuning(
                                    daxProfileId = currentProfileId,
                                    geqBandGains = geq,
                                    bassEnhancerEnable = bass || fallback.bassEnhancerEnable,
                                    headphoneVirtualizer = hpVirt,
                                    speakerVirtualizer = spkVirt,
                                    stereoWideningAmount = widening,
                                    dialogueEnhancerEnable = dlgEnable,
                                    dialogueEnhancerAmount = dlgAmount
                                )
                                currentProfileId = -1
                            }
                        }
                    }
                    event = parser.next()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed parsing dax-default.xml: ${e.message}")
        }
        return result
    }

    private fun defaultFallback(profileId: Int): DaxProfileTuning = when (profileId) {
        2 -> DaxProfileTuning( // Music
            daxProfileId = 2,
            geqBandGains = intArrayOf(35, 30, 20, 10, 0, -5, -5, 0, 5, 10, 15, 20, 25, 30, 35, 35, 30, 25, 20, 15),
            bassEnhancerEnable = true,
            headphoneVirtualizer = false,
            speakerVirtualizer = false,
            stereoWideningAmount = 32,
            dialogueEnhancerEnable = false,
            dialogueEnhancerAmount = 4
        )
        1 -> DaxProfileTuning( // Movie
            daxProfileId = 1,
            geqBandGains = intArrayOf(45, 40, 25, 10, 0, -10, -5, 5, 15, 25, 30, 25, 20, 20, 25, 30, 30, 25, 20, 15),
            bassEnhancerEnable = true,
            headphoneVirtualizer = true,
            speakerVirtualizer = true,
            stereoWideningAmount = 48,
            dialogueEnhancerEnable = true,
            dialogueEnhancerAmount = 8
        )
        else -> DaxProfileTuning( // Smart / Dynamic
            daxProfileId = 0,
            geqBandGains = intArrayOf(20, 15, 10, 5, 0, 0, 0, 5, 10, 10, 12, 12, 15, 15, 15, 15, 12, 10, 8, 5),
            bassEnhancerEnable = true,
            headphoneVirtualizer = true,
            speakerVirtualizer = true,
            stereoWideningAmount = 36,
            dialogueEnhancerEnable = true,
            dialogueEnhancerAmount = 6
        )
    }
}
