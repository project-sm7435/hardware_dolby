/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.audio

import android.media.audiofx.AudioEffect
import org.lunaris.dolby.DolbyConstants
import java.util.UUID

/**
 * Handle to the ViPER FX effect (libv4a_re.so) that the ROM installs next to Dolby. It only sends
 * commands; the audio processing itself runs inside the effect library.
 *
 * A command is the parameter id (4 bytes) plus four int values (16 bytes), the layout that
 * ViPER::DispatchCommand(param, val1, val2, val3, val4, ...) reads.
 */
class ViperAudioEffect(priority: Int, audioSession: Int) : AudioEffect(
    EFFECT_TYPE_NULL, UUID.fromString(DolbyConstants.VIPER_EFFECT_UUID), priority, audioSession
) {

    /** Sends one command. Returns false when the effect refused it (for example it lost control). */
    fun send(param: Int, v1: Int = 0, v2: Int = 0, v3: Int = 0, v4: Int = 0): Boolean {
        val paramBytes = ByteArray(4)
        val valueBytes = ByteArray(16)
        putInt(paramBytes, 0, param)
        putInt(valueBytes, 0, v1)
        putInt(valueBytes, 4, v2)
        putInt(valueBytes, 8, v3)
        putInt(valueBytes, 12, v4)
        val status = setParameter(paramBytes, valueBytes)
        if (status != SUCCESS) {
            DolbyConstants.dlog(TAG, "ViPER command 0x${param.toString(16)} failed, status=$status")
        }
        return status == SUCCESS
    }

    private fun putInt(dst: ByteArray, index: Int, value: Int) {
        dst[index] = (value and 0xff).toByte()
        dst[index + 1] = ((value ushr 8) and 0xff).toByte()
        dst[index + 2] = ((value ushr 16) and 0xff).toByte()
        dst[index + 3] = ((value ushr 24) and 0xff).toByte()
    }

    companion object {
        private const val TAG = "ViperAudioEffect"
    }
}
