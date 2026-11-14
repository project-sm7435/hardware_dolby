/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.audio

import android.media.audiofx.AudioEffect
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        // Sony DSEE HAL implementations expect both EFFECT_CMD_ENABLE and parameter 0 = 1/0
        val paramStatus = try {
            val param = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(0).array()
            val value = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(if (enable) 1 else 0).array()
            setParameter(param, value)
        } catch (t: Throwable) {
            Log.w(TAG, "DSEE setParameter(0, ${if (enable) 1 else 0}) threw: ${t.message}")
            ERROR
        }
        val status = setEnabled(enable)
        Log.i(TAG, "DSEE setDseeEnabled($enable): paramStatus=$paramStatus enableStatus=$status enabled=${enabled}")
        return status == SUCCESS
    }

    companion object {
        private const val TAG = "DseeAudioEffect"
    }
}
