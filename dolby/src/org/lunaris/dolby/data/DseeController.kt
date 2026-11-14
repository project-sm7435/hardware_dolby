/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import android.content.Context
import android.os.SystemClock
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
    fun apply(enabled: Boolean, master: Boolean, forceRebind: Boolean = false): Boolean {
        val active = master && enabled
        if (!active) {
            if (effect != null) {
                try {
                    effect?.setDseeEnabled(false)
                } catch (_: Exception) {
                }
                drop()
            }
            return true
        }

        if (forceRebind && effect != null) {
            drop()
        }
        repeat(2) {
            val e = obtain() ?: return false
            val ok = try {
                e.setDseeEnabled(true)
            } catch (_: Exception) {
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
            DseeAudioEffect(EFFECT_PRIORITY, 0).also { effect = it }
        } catch (_: Exception) {
            nextTryAtMs = now + RETRY_DELAY_MS
            null
        }
    }

    private fun drop() {
        try {
            effect?.release()
        } catch (_: Exception) {
        }
        effect = null
    }

    companion object {
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
