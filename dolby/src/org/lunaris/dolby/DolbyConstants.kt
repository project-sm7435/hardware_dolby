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

object DolbyConstants {

    const val TAG = "Dolby"

    val EFFECT_UUID_DAP: UUID = UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa")
    val EFFECT_UUID_MISOUND: UUID = UUID.fromString("5b8e36a5-144a-4c38-b1d7-0002a5d5c51b")

    const val PREF_SOUND_ENGINE_MODE = "sound_engine_mode"
    const val PREF_MISOUND_SCENARIO = "misound_scenario"
    const val PREF_MISOUND_3D_SURROUND = "misound_3d_surround"
    const val PREF_MISOUND_SURROUND_LEVEL = "misound_surround_level"
    const val PREF_MISOUND_VOCAL_LEVEL = "misound_vocal_level"

    const val PROP_MISOUND_DISABLE = "persist.sys.dolby.misound.disable"
    const val PROP_MISOUND_SCENARIO = "persist.sys.dolby.misound.scenario"
    const val PROP_MISOUND_AUTO_SCENARIO = "persist.sys.dolby.misound.auto_scenario"
    const val PROP_MISOUND_MUSIC_STATE = "persist.sys.dolby.misound.music_state"
    const val PROP_MISOUND_3D_SURROUND = "persist.sys.dolby.misound.surround3d"
    const val PROP_MISOUND_SURROUND_LEVEL = "persist.sys.dolby.misound.surround_level"
    const val PROP_MISOUND_VOCAL_LEVEL = "persist.sys.dolby.misound.vocal_level"

    // libmisoundfx.so QdspParameterId values for AudioEffect.setParameter(int, int)
    const val MISOUND_PARAM_ENABLE = 0
    const val MISOUND_PARAM_SCENARIO = 1
    const val MISOUND_PARAM_HEADSET_TYPE = 2
    const val MISOUND_PARAM_3D_SURROUND_ENABLE = 7
    const val MISOUND_PARAM_SURROUND_LEVEL = 8
    const val MISOUND_PARAM_VOCAL_LEVEL = 9

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
