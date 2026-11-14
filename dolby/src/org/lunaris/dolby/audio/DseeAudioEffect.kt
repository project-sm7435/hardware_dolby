/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.audio

import android.media.audiofx.AudioEffect
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

    fun isReady(): Boolean = try {
        hasControl()
    } catch (_: Throwable) {
        false
    }

    fun setDseeEnabled(enable: Boolean): Boolean {
        if (enable && !enabled) {
            setEnabled(true)
        }
        try {
            val param = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(0).array()
            val value = ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(if (enable) 1 else 0).array()
            setParameter(param, value)
        } catch (_: Throwable) {
        }
        return setEnabled(enable) == SUCCESS
    }
}
