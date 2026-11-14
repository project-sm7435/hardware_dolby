/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
        Log.i(TAG, "DSEE apply: enabled=$enabled master=$master active=$active")
        if (!active) {
            if (effect != null) {
                try {
                    effect?.setDseeEnabled(false)
                } catch (ex: Exception) {
                    Log.w(TAG, "DSEE disable failed: ${ex.message}")
                }
                drop()
                Log.i(TAG, "DSEE effect released")
            }
            return true
        }

        repeat(2) { attempt ->
            val e = obtain() ?: run {
                Log.w(TAG, "DSEE obtain() returned null on attempt $attempt")
                return false
            }
            val ok = try {
                e.setDseeEnabled(true)
            } catch (ex: Exception) {
                Log.e(TAG, "DSEE enable failed on attempt $attempt: ${ex.message}", ex)
                false
            }
            if (ok) {
                Log.i(TAG, "DSEE effect active on session 0 (id=${e.id})")
                return true
            }
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
                Log.i(TAG, "DSEE effect created (id=${it.id}, descriptor=${it.descriptor?.name})")
            }
        } catch (e: Exception) {
            nextTryAtMs = now + RETRY_DELAY_MS
            Log.e(TAG, "DSEE effect creation failed (check audio_effects.xml / SELinux): ${e.message}", e)
            null
        }
    }

    private fun drop() {
        try {
            effect?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing DSEE effect: ${e.message}")
        }
        effect = null
    }

    companion object {
        private const val TAG = "DseeController"
        private const val EFFECT_PRIORITY = 100
        private const val RETRY_DELAY_MS = 3_000L

        @Volatile
        private var instance: DseeController? = null

        fun get(@Suppress("UNUSED_PARAMETER") context: Context): DseeController =
            instance ?: synchronized(this) {
                instance ?: DseeController().also { instance = it }
            }
    }
}
