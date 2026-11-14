/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.audio

import android.media.audiofx.AudioEffect
import org.lunaris.dolby.DolbyConstants
import java.util.UUID

/**
 * Handle to vendor Sony DSEE AudioEffect (libdsee.so) registered in audio_effects.xml
 * with UUID 0ca9dd34-7a41-11ee-b962-0242ac120002.
 */
class DseeAudioEffect(priority: Int, audioSession: Int) : AudioEffect(
    EFFECT_TYPE_NULL,
    UUID.fromString(DolbyConstants.DSEE_EFFECT_UUID),
    priority,
    audioSession
) {

    fun setDseeEnabled(enable: Boolean): Boolean {
        val status = setEnabled(enable)
        if (status != SUCCESS) {
            DolbyConstants.dlog(TAG, "DSEE setEnabled($enable) failed, status=$status")
        }
        return status == SUCCESS
    }

    companion object {
        private const val TAG = "DseeAudioEffect"
    }
}
