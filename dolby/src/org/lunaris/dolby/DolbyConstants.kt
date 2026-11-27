/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby

import android.util.Log
import java.util.UUID

enum class SoundEngineMode(val id: Int) {
    DOLBY_ATMOS(0),
    XIAOMI_SOUND(1);

    companion object {
        fun fromId(id: Int): SoundEngineMode =
            entries.firstOrNull { it.id == id } ?: DOLBY_ATMOS
    }
}

/** Result of the flowchart's exclusive switch: only one engine, or none, may be running. */
enum class ActiveEngine { DOLBY, MISOUND, NONE }

object DolbyConstants {

    const val TAG = "Dolby"

    val EFFECT_UUID_DAP: UUID = UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa")
    val EFFECT_UUID_MISOUND: UUID = UUID.fromString("5b8e36a5-144a-4c38-b1d7-0002a5d5c51b")

    const val PREF_SOUND_ENGINE_MODE = "sound_engine_mode"
    const val PREF_DOLBY_MASTER_ENABLE = "dolby_master_enable"
    const val PREF_MISOUND_MASTER_ENABLE = "misound_master_enable"
    const val PREF_MISOUND_SCENARIO = "misound_scenario"
    const val PREF_MISOUND_3D_SURROUND = "misound_3d_surround"
    const val PREF_MISOUND_SURROUND_LEVEL = "misound_surround_level"
    const val PREF_MISOUND_VOCAL_LEVEL = "misound_vocal_level"
    const val PREF_MISOUND_EQ_GAINS = "misound_eq_gains"
    const val PREF_MISOUND_SCENARIO_PREFIX = "misound_scenario_"

    const val PROP_MISOUND_DISABLE = "persist.sys.dolby.misound.disable"
    const val PROP_MISOUND_SCENARIO = "persist.sys.dolby.misound.scenario"
    const val PROP_MISOUND_AUTO_SCENARIO = "persist.sys.dolby.misound.auto_scenario"
    const val PROP_MISOUND_MUSIC_STATE = "persist.sys.dolby.misound.music_state"
    const val PROP_MISOUND_3D_SURROUND = "persist.sys.dolby.misound.surround3d"
    const val PROP_MISOUND_SURROUND_LEVEL = "persist.sys.dolby.misound.surround_level"
    const val PROP_MISOUND_VOCAL_LEVEL = "persist.sys.dolby.misound.vocal_level"
    const val PROP_MISOUND_EQ = "persist.sys.dolby.misound.eq"
    const val PROP_MISOUND_HEADSET_TYPE = "persist.sys.dolby.misound.headset_type"

    // Verified libmisoundfx.so MiSound_command(cmdCode=5 EFFECT_CMD_SET_PARAM) paramIds
    const val MISOUND_PARAM_HEADSET_TYPE = 1
    const val MISOUND_PARAM_EQ_LEVEL = 2
    const val MISOUND_PARAM_MUSIC = 4
    const val MISOUND_PARAM_MOVIE = 5 // Dead no-op in libmisoundfx.so; kept for reference only
    const val MISOUND_PARAM_SCENARIO = 15
    const val MISOUND_PARAM_SURROUND_LEVEL = 16
    const val MISOUND_PARAM_VOCAL_LEVEL = 17
    const val MISOUND_PARAM_MOVIE_MODE_ENABLE = 18
    const val MISOUND_PARAM_3D_SURROUND_ENABLE = 20 // Internally maps to QdspParameterId=22
    const val MISOUND_PARAM_SKIP_NEXT_OFF = 22 // One-shot skip-next-off guard; never send on disable
    const val MISOUND_PARAM_ENABLE = 25 // Actual QdspMisoundGefDriver::setEnabled

    // Native libmisoundfx.so scenario IDs (UI indices 0=Smart, 1=Music, 2=Movie)
    const val MISOUND_SCENARIO_MUSIC = 1
    const val MISOUND_SCENARIO_MOVIE = 2
    const val MISOUND_SCENARIO_VOCAL = 3
    const val MISOUND_SCENARIO_SMART = 4

    const val MISOUND_EQ_BAND_COUNT = 10
    const val MISOUND_EQ_MIN_DB = -12.0f
    const val MISOUND_EQ_MAX_DB = 12.0f
    const val MISOUND_EQ_MIN_TENTHS = -120
    const val MISOUND_EQ_MAX_TENTHS = 120

    fun uiScenarioToNative(uiScenario: Int): Int = when (uiScenario.coerceIn(0, 2)) {
        1 -> MISOUND_SCENARIO_MUSIC
        2 -> MISOUND_SCENARIO_MOVIE
        else -> MISOUND_SCENARIO_SMART
    }

    const val PREF_ENABLE = "dolby_enable"
    const val PREF_PROFILE = "dolby_profile"
    const val PREF_PRESET = "dolby_preset"
    const val PREF_IEQ = "dolby_ieq"
    const val PREF_HP_VIRTUALIZER = "dolby_virtualizer"
    const val PREF_SPK_VIRTUALIZER = "dolby_spk_virtualizer"
    const val PREF_STEREO_WIDENING = "dolby_stereo_widening"
    const val PREF_DIALOGUE = "dolby_dialogue_enabled"
    const val PREF_DIALOGUE_AMOUNT = "dolby_dialogue_amount"
    const val PREF_BASS = "dolby_bass"
    const val PREF_BASS_LEVEL = "dolby_bass_level"
    const val PREF_BASS_CURVE = "dolby_bass_curve"
    const val PREF_CLARITY_MODE = "dolby_clarity_mode"
    const val PREF_BASS_CUTOFF = "dolby_bass_cutoff"
    const val PREF_SPEAKER_CORRECTION = "dolby_speaker_correction"
    // GEQ delta (0.1 dB per band) the speaker correction really added, after the +-15 dB clamp
    const val PREF_SPEAKER_APPLIED = "dolby_speaker_correction_applied"
    // GEQ delta (0.1 dB per band) bass / treble really added, after the +-15 dB clamp
    const val PREF_BASS_APPLIED = "dolby_bass_applied"
    // Dynamic system is its own effect in ViPER, so it has its own level (0 = off) next to ViPER bass
    const val PREF_DYNAMIC_LEVEL = "dolby_dynamic_level"
    const val PREF_DYNAMIC_APPLIED = "dolby_dynamic_applied"
    const val PREF_TREBLE_APPLIED = "dolby_treble_applied"
    const val PREF_VIPER_SURROUND = "dolby_viper_surround"
    const val PREF_VIPER_SURROUND_WIDENING = "dolby_viper_surround_widening"
    const val PREF_VIPER_SURROUND_DEPTH = "dolby_viper_surround_depth"
    const val PREF_MID = "dolby_mid"
    const val PREF_MID_LEVEL = "dolby_mid_level"
    const val PREF_TREBLE = "dolby_treble"
    const val PREF_TREBLE_LEVEL = "dolby_treble_level"
    const val PREF_VOLUME = "dolby_volume"
    const val PREF_PRESETS_MIGRATED = "presets_migrated"
    const val PREF_BAND_MODE = "dolby_band_mode"
    const val PREF_DEVICE_STATE_MEMORY = "device_state_memory_enabled"

    const val PREF_FILE_PRESETS = "presets"

    enum class DsParam(val id: Int, val length: Int = 1) {
        HEADPHONE_VIRTUALIZER(101),
        SPEAKER_VIRTUALIZER(102),
        VOLUME_LEVELER_ENABLE(103),
        IEQ_PRESET(104),
        DIALOGUE_ENHANCER_ENABLE(105),
        DIALOGUE_ENHANCER_AMOUNT(108),
        GEQ_BAND_GAINS(110, 20),
        BASS_ENHANCER_ENABLE(111),
        STEREO_WIDENING_AMOUNT(113);

        override fun toString(): String = "${name}(${id})"
    }

    fun dlog(tag: String, msg: String) {
        if (Log.isLoggable(TAG, Log.DEBUG) || Log.isLoggable(tag, Log.DEBUG)) {
            Log.d("$TAG-$tag", msg)
        }
    }
}
