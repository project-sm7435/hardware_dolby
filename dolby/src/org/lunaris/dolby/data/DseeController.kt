/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import android.content.Context
import android.os.SystemClock
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.audio.DseeAudioEffect

/**
 * Controls the vendor Sony DSEE (Digital Sound Enhancement Engine) AudioEffect (libdsee.so).
 * Instantiated on demand when enabled and cleanly released when switched off.
 */
class DseeController private constructor() {

    private var effect: DseeAudioEffect? = null
    private var nextTryAtMs = 0L

    @Synchronized
    fun isAvailable(): Boolean = obtain() != null

    @Synchronized
    fun apply(enabled: Boolean, master: Boolean): Boolean {
        val active = master && enabled
        if (!active) {
            if (effect != null) {
                try {
                    effect?.setDseeEnabled(false)
                } catch (ex: Exception) {
                    DolbyConstants.dlog(TAG, "DSEE disable failed: ${ex.message}")
                }
                drop()
                DolbyConstants.dlog(TAG, "DSEE effect released")
            }
            return true
        }

        repeat(2) {
            val e = obtain() ?: return false
            val ok = try {
                e.setDseeEnabled(true)
            } catch (ex: Exception) {
                DolbyConstants.dlog(TAG, "DSEE enable failed: ${ex.message}")
                false
            }
            if (ok) return true
            drop()
        }
        return false
    }

    private fun obtain(): DseeAudioEffect? {
        effect?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (now < nextTryAtMs) return null
        return try {
            DseeAudioEffect(EFFECT_PRIORITY, 0).also {
                effect = it
                DolbyConstants.dlog(TAG, "DSEE effect created")
            }
        } catch (e: Exception) {
            nextTryAtMs = now + RETRY_DELAY_MS
            DolbyConstants.dlog(TAG, "DSEE effect not available: ${e.message}")
            null
        }
    }

    private fun drop() {
        try {
            effect?.release()
        } catch (e: Exception) {
            DolbyConstants.dlog(TAG, "Error releasing DSEE effect: ${e.message}")
        }
        effect = null
    }

    companion object {
        private const val TAG = "DseeController"
        private const val EFFECT_PRIORITY = 100
        private const val RETRY_DELAY_MS = 10_000L

        @Volatile
        private var instance: DseeController? = null

        fun get(@Suppress("UNUSED_PARAMETER") context: Context): DseeController =
            instance ?: synchronized(this) {
                instance ?: DseeController().also { instance = it }
            }
    }
}
