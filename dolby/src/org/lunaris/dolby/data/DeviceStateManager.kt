/*
 * Copyright (C) 2026 tranQuila-Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo

import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.domain.models.BandGain
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
        val prefs = getDevicePrefs(deviceKey)
        val editor = prefs.edit()

        editor.putInt(KEY_VERSION, SNAPSHOT_VERSION)

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

        val gains = repository.getEqualizerGains(profile, BandMode.TWENTY_BAND)
        editor.putInt(KEY_EQ_BAND_COUNT, gains.size)
        editor.putString(KEY_EQ_GAINS, gains.joinToString(",") { it.gain.toString() })

        editor.commit()
        DolbyConstants.dlog(TAG,
            "Snapshot saved for device=$deviceKey profile=$profile bands=${gains.size} v=$SNAPSHOT_VERSION")
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
            val enabled = prefs.getBoolean(KEY_DOLBY_ENABLED, true)
            val profile = prefs.getInt(KEY_PROFILE, 0)

            prefs.getString(KEY_BAND_MODE, null)?.let { modeVal ->
                val mode = when (modeVal) {
                    "15" -> BandMode.FIFTEEN_BAND
                    "20" -> BandMode.TWENTY_BAND
                    else -> BandMode.TEN_BAND
                }
                repository.setBandMode(mode)
            }

            repository.setBassCurve(profile, prefs.getInt(KEY_BASS_CURVE, 0))
            repository.setBassCutoff(profile, prefs.getInt(KEY_BASS_CUTOFF, 60))
            repository.setBassLevel(profile, prefs.getInt(KEY_BASS_LEVEL, 0))
            repository.setClarityMode(profile, prefs.getInt(KEY_CLARITY_MODE, 0))
            repository.setTrebleLevel(profile, prefs.getInt(KEY_TREBLE_LEVEL, 0))
            repository.setDynamicLevel(profile, prefs.getInt(KEY_DYNAMIC_LEVEL, 0))
            repository.setSpeakerCorrection(profile, prefs.getInt(KEY_SPEAKER_CORRECTION, 0))
            repository.setViperSurroundWidening(profile, prefs.getInt(KEY_VIPER_SURROUND_WIDENING, 50))
            repository.setViperSurroundDepth(profile, prefs.getInt(KEY_VIPER_SURROUND_DEPTH, 35))
            repository.setViperSurroundEnabled(profile, prefs.getBoolean(KEY_VIPER_SURROUND, false))

            val midLevel = prefs.getInt(KEY_MID_LEVEL, 0)
            val storedBandCount = prefs.getInt(KEY_EQ_BAND_COUNT, -1)
            val gainsStr = prefs.getString(KEY_EQ_GAINS, null)
            if (gainsStr != null && storedBandCount > 0) {
                val gains = gainsStr.split(",").mapNotNull { it.toIntOrNull() }
                if (gains.size == storedBandCount) {
                    val bandGains = gains.mapIndexed { i, g ->
                        BandGain(
                            frequency = DolbyRepository.BAND_FREQUENCIES_20.getOrElse(i) { i },
                            gain = g
                        )
                    }
                    repository.setEqualizerGains(profile, bandGains, BandMode.TWENTY_BAND)
                    repository.syncMidLevelState(profile, midLevel)
                } else {
                    repository.setMidLevel(profile, midLevel)
                }
            } else {
                repository.setMidLevel(profile, midLevel)
            }

            repository.setIeqPreset(profile, prefs.getInt(KEY_IEQ, 0))
            repository.setHeadphoneVirtualizerEnabled(profile, prefs.getBoolean(KEY_HP_VIRT, false))
            repository.setSpeakerVirtualizerEnabled(profile, prefs.getBoolean(KEY_SPK_VIRT, false))
            repository.setDialogueEnhancerEnabled(profile, prefs.getBoolean(KEY_DIALOGUE, false))
            repository.setDialogueEnhancerAmount(profile, prefs.getInt(KEY_DIALOGUE_AMT, 6))

            if (repository.volumeLevelerSupported) {
                repository.setVolumeLevelerEnabled(profile, prefs.getBoolean(KEY_VOLUME, false))
            }
            if (repository.stereoWideningSupported) {
                repository.setStereoWideningAmount(profile, prefs.getInt(KEY_STEREO, 32))
            }

            if (restoreEnabled) {
                repository.setDolbyEnabled(enabled)
            }
            repository.setCurrentProfile(profile)
            repository.notifyStateUpdated()

            DolbyConstants.dlog(TAG,
                "Snapshot restored for device=$deviceKey profile=$profile v=$storedVersion")
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
        getDevicePrefs(deviceKey).edit().clear().commit()
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

        // 2: added dynamic_level and band_mode. Older snapshots lack them and are discarded.
        const val SNAPSHOT_VERSION = 2

        private const val KEY_VERSION = "snapshot_version"
        private const val KEY_DOLBY_ENABLED = "enabled"
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
