/*
 * Copyright (C) 2026 tranQuila-Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo

import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.SoundEngineMode
import org.lunaris.dolby.domain.models.BandMode

class DeviceStateManager(private val context: Context) {

    fun deviceKey(device: AudioDeviceInfo): String {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> {
                val addr = device.address?.takeIf { it.isNotBlank() } ?: "unknown"
                "bt_${addr.replace(":", "_")}"
            }
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> {
                val name = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "unknown"
                "usb_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_")
            }
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
            else -> "device_type_${device.type}"
        }
    }

    fun deviceDisplayName(device: AudioDeviceInfo): String {
        val productName = device.productName?.toString()?.takeIf { it.isNotBlank() }
        return when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> productName ?: "Wired Headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE -> productName ?: "USB Audio"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> productName ?: "Bluetooth Device"
            else -> productName ?: "Audio Device"
        }
    }

    fun saveSnapshot(deviceKey: String, repository: DolbyRepository) {
        // Saved profile, read from prefs: getCurrentProfile() also writes to the effect.
        val profile = repository.getSavedProfile()
        val engineMode = repository.getSoundEngineMode()
        val prefs = getDevicePrefs(deviceKey)
        val editor = prefs.edit()

        editor.putInt(KEY_VERSION, SNAPSHOT_VERSION)

        editor.putInt(KEY_SOUND_ENGINE_MODE, engineMode.id)
        editor.putBoolean(KEY_DOLBY_MASTER_ENABLED, repository.getSavedEngineEnabled(SoundEngineMode.DOLBY_ATMOS))
        editor.putBoolean(KEY_MISOUND_MASTER_ENABLED, repository.getSavedEngineEnabled(SoundEngineMode.XIAOMI_SOUND))
        editor.putBoolean(KEY_DOLBY_ENABLED, repository.getSavedDolbyEnabled())
        editor.putInt(KEY_PROFILE, profile)
        editor.putString(KEY_BAND_MODE, repository.getBandMode().value)

        editor.putInt(KEY_IEQ, repository.getIeqPreset(profile))

        editor.putBoolean(KEY_HP_VIRT, repository.getHeadphoneVirtualizerEnabled(profile))
        editor.putBoolean(KEY_SPK_VIRT, repository.getSpeakerVirtualizerEnabled(profile))

        editor.putBoolean(KEY_DIALOGUE, repository.getDialogueEnhancerEnabled(profile))
        editor.putInt(KEY_DIALOGUE_AMT, repository.getDialogueEnhancerAmount(profile))

        val bassLevel = repository.getBassLevel(profile)
        editor.putBoolean(KEY_BASS_ENABLED, bassLevel > 0 || repository.getBassEnhancerEnabled(profile))
        editor.putInt(KEY_BASS_LEVEL, bassLevel)
        editor.putInt(KEY_BASS_CURVE, repository.getBassCurve(profile))
        editor.putInt(KEY_CLARITY_MODE, repository.getClarityMode(profile))
        editor.putInt(KEY_BASS_CUTOFF, repository.getBassCutoff(profile))
        editor.putInt(KEY_SPEAKER_CORRECTION, repository.getSpeakerCorrection(profile))
        editor.putInt(KEY_DYNAMIC_LEVEL, repository.getDynamicLevel(profile))
        editor.putBoolean(KEY_VIPER_SURROUND, repository.getViperSurroundEnabled(profile))
        editor.putInt(KEY_VIPER_SURROUND_WIDENING, repository.getViperSurroundWidening(profile))
        editor.putInt(KEY_VIPER_SURROUND_DEPTH, repository.getViperSurroundDepth(profile))

        val trebleLevel = repository.getTrebleLevel(profile)
        editor.putBoolean(KEY_TREBLE_ENABLED, trebleLevel > 0)
        editor.putInt(KEY_TREBLE_LEVEL, trebleLevel)

        val midLevel = repository.getMidLevel(profile)
        editor.putBoolean(KEY_MID_ENABLED, midLevel > 0)
        editor.putInt(KEY_MID_LEVEL, midLevel)

        if (repository.volumeLevelerSupported) {
            editor.putBoolean(KEY_VOLUME, repository.getVolumeLevelerEnabled(profile))
        }
        if (repository.stereoWideningSupported) {
            editor.putInt(KEY_STEREO, repository.getStereoWideningAmount(profile))
        }

        val gains = repository.getDolbyEqualizerGainsForSnapshot(profile)
        editor.putInt(KEY_EQ_BAND_COUNT, gains.size)
        editor.putString(KEY_EQ_GAINS, gains.joinToString(",") { it.gain.toString() })

        editor.putInt(KEY_MISOUND_SCENARIO, repository.getMiSoundScenario())
        for (s in 0..2) {
            editor.putBoolean("${KEY_MISOUND_3D_PREFIX}$s", repository.getMiSound3dSurround(s))
            editor.putInt("${KEY_MISOUND_SURROUND_LEVEL_PREFIX}$s", repository.getMiSoundSurroundLevel(s))
            editor.putInt("${KEY_MISOUND_VOCAL_LEVEL_PREFIX}$s", repository.getMiSoundVocalLevel(s))
            editor.putString(
                "${KEY_MISOUND_EQ_PREFIX}$s",
                repository.readMiSoundScenarioGeq(s).joinToString(",")
            )
        }

        editor.apply()
        DolbyConstants.dlog(TAG,
            "Snapshot saved for device=$deviceKey mode=$engineMode profile=$profile v=$SNAPSHOT_VERSION")
    }

    fun restoreSnapshot(
        deviceKey: String,
        repository: DolbyRepository,
        restoreEnabled: Boolean = true
    ): Boolean {
        val prefs = getDevicePrefs(deviceKey)

        if (!prefs.contains(KEY_VERSION)) {
            DolbyConstants.dlog(TAG, "No snapshot for device=$deviceKey")
            return false
        }

        val storedVersion = prefs.getInt(KEY_VERSION, -1)
        if (storedVersion != SNAPSHOT_VERSION) {
            DolbyConstants.dlog(TAG,
                "Snapshot version mismatch for $deviceKey: stored=$storedVersion current=$SNAPSHOT_VERSION — discarding")
            clearSnapshot(deviceKey)
            return false
        }

        return try {
            val restoredMode = SoundEngineMode.fromId(
                prefs.getInt(KEY_SOUND_ENGINE_MODE, SoundEngineMode.DOLBY_ATMOS.id)
            )
            val enabled = prefs.getBoolean(KEY_DOLBY_ENABLED, true)
            val profile = prefs.getInt(KEY_PROFILE, 0)

            // Restore MiSound scenarios cleanly without pushing Dolby parameters
            val miScenario = prefs.getInt(KEY_MISOUND_SCENARIO, 0).coerceIn(0, 2)
            val s3d = (0..2).map { prefs.getBoolean("${KEY_MISOUND_3D_PREFIX}$it", it != 1) }
            val sLevel = (0..2).map { prefs.getInt("${KEY_MISOUND_SURROUND_LEVEL_PREFIX}$it", 45) }
            val vLevel = (0..2).map { prefs.getInt("${KEY_MISOUND_VOCAL_LEVEL_PREFIX}$it", 20) }
            val eqList = (0..2).map { prefs.getString("${KEY_MISOUND_EQ_PREFIX}$it", null) }
            repository.restoreMiSoundSnapshot(miScenario, s3d, sLevel, vLevel, eqList)

            // Restore Dolby prefs without forcing Dolby active when restoredMode == XIAOMI_SOUND
            prefs.getString(KEY_BAND_MODE, null)?.let { modeVal ->
                val mode = when (modeVal) {
                    "15" -> BandMode.FIFTEEN_BAND
                    "20" -> BandMode.TWENTY_BAND
                    else -> BandMode.TEN_BAND
                }
                repository.setBandMode(mode)
            }

            repository.restoreDolbyProfilePrefsForSnapshot(
                profile = profile,
                ieqPreset = prefs.getInt(KEY_IEQ, 0),
                hpVirt = prefs.getBoolean(KEY_HP_VIRT, false),
                spkVirt = prefs.getBoolean(KEY_SPK_VIRT, false),
                dialogueEnabled = prefs.getBoolean(KEY_DIALOGUE, false),
                dialogueAmount = prefs.getInt(KEY_DIALOGUE_AMT, 6),
                volumeLeveler = if (repository.volumeLevelerSupported) prefs.getBoolean(KEY_VOLUME, false) else null,
                stereoWidening = if (repository.stereoWideningSupported) prefs.getInt(KEY_STEREO, 32) else null,
                eqGains20 = prefs.getString(KEY_EQ_GAINS, null),
                bassCurve = prefs.getInt(KEY_BASS_CURVE, 0),
                bassCutoff = prefs.getInt(KEY_BASS_CUTOFF, 60),
                bassLevel = prefs.getInt(KEY_BASS_LEVEL, 0),
                clarityMode = prefs.getInt(KEY_CLARITY_MODE, 0),
                trebleLevel = prefs.getInt(KEY_TREBLE_LEVEL, 0),
                dynamicLevel = prefs.getInt(KEY_DYNAMIC_LEVEL, 0),
                speakerCorrection = prefs.getInt(KEY_SPEAKER_CORRECTION, 0),
                midLevel = prefs.getInt(KEY_MID_LEVEL, 0),
                viperSurroundWidening = prefs.getInt(KEY_VIPER_SURROUND_WIDENING, 50),
                viperSurroundDepth = prefs.getInt(KEY_VIPER_SURROUND_DEPTH, 35),
                viperSurroundEnabled = prefs.getBoolean(KEY_VIPER_SURROUND, false)
            )

            repository.restoreEngineMasterStates(
                dolbyMaster = prefs.getBoolean(KEY_DOLBY_MASTER_ENABLED, enabled),
                misoundMaster = prefs.getBoolean(KEY_MISOUND_MASTER_ENABLED, enabled)
            )
            repository.setCurrentProfile(profile)
            repository.setSoundEngineMode(restoredMode)
            if (restoreEnabled) {
                repository.setDolbyEnabled(enabled)
            }
            repository.notifySnapshotRestored()

            DolbyConstants.dlog(TAG,
                "Snapshot restored for device=$deviceKey mode=$restoredMode profile=$profile v=$storedVersion")
            true
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG,
                "Failed to restore snapshot for $deviceKey: ${e.message} — discarding")
            clearSnapshot(deviceKey)
            false
        }
    }

    fun hasSnapshot(deviceKey: String): Boolean {
        val prefs = getDevicePrefs(deviceKey)
        return prefs.contains(KEY_VERSION) &&
                prefs.getInt(KEY_VERSION, -1) == SNAPSHOT_VERSION
    }

    fun clearSnapshot(deviceKey: String) {
        getDevicePrefs(deviceKey).edit().clear().apply()
        DolbyConstants.dlog(TAG, "Snapshot cleared for device=$deviceKey")
    }

    fun getAllDeviceKeys(): List<String> {
        val dir = context.filesDir.parentFile?.let {
            java.io.File(it, "shared_prefs")
        } ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.name.startsWith("device_state_") }
            ?.map { it.name.removePrefix("device_state_").removeSuffix(".xml") }
            ?: emptyList()
    }

    private fun getDevicePrefs(deviceKey: String): SharedPreferences =
        context.getSharedPreferences("device_state_$deviceKey", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "DeviceStateManager"

        // 3: added sound_engine_mode, per-engine master enables, and MiSound per-scenario state.
        const val SNAPSHOT_VERSION = 3

        private const val KEY_VERSION = "snapshot_version"
        private const val KEY_SOUND_ENGINE_MODE = "sound_engine_mode"
        private const val KEY_DOLBY_MASTER_ENABLED = "dolby_master_enabled"
        private const val KEY_MISOUND_MASTER_ENABLED = "misound_master_enabled"
        private const val KEY_DOLBY_ENABLED = "enabled"
        private const val KEY_MISOUND_SCENARIO = "misound_scenario"
        private const val KEY_MISOUND_3D_PREFIX = "misound_3d_"
        private const val KEY_MISOUND_SURROUND_LEVEL_PREFIX = "misound_surround_lvl_"
        private const val KEY_MISOUND_VOCAL_LEVEL_PREFIX = "misound_vocal_lvl_"
        private const val KEY_MISOUND_EQ_PREFIX = "misound_eq_"
        private const val KEY_PROFILE = "profile"
        private const val KEY_BAND_MODE = "band_mode"
        private const val KEY_IEQ = "ieq"
        private const val KEY_HP_VIRT = "hp_virt"
        private const val KEY_SPK_VIRT = "spk_virt"
        private const val KEY_DIALOGUE = "dialogue"
        private const val KEY_DIALOGUE_AMT  = "dialogue_amt"
        private const val KEY_BASS_ENABLED = "bass_enabled"
        private const val KEY_BASS_LEVEL = "bass_level"
        private const val KEY_BASS_CURVE = "bass_curve"
        private const val KEY_CLARITY_MODE = "clarity_mode"
        private const val KEY_BASS_CUTOFF = "bass_cutoff"
        private const val KEY_SPEAKER_CORRECTION = "speaker_correction"
        private const val KEY_DYNAMIC_LEVEL = "dynamic_level"
        private const val KEY_VIPER_SURROUND = "viper_surround"
        private const val KEY_VIPER_SURROUND_WIDENING = "viper_surround_widening"
        private const val KEY_VIPER_SURROUND_DEPTH = "viper_surround_depth"
        private const val KEY_TREBLE_ENABLED = "treble_enabled"
        private const val KEY_TREBLE_LEVEL = "treble_level"
        private const val KEY_MID_ENABLED = "mid_enabled"
        private const val KEY_MID_LEVEL = "mid_level"
        private const val KEY_VOLUME = "volume"
        private const val KEY_STEREO = "stereo"
        private const val KEY_EQ_BAND_COUNT = "eq_band_count"
        private const val KEY_EQ_GAINS = "eq_gains"
    }
}
