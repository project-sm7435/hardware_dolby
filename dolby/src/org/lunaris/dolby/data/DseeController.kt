/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import android.content.Context
import android.os.SystemClock
import org.lunaris.dolby.audio.DseeAudioEffect

class DseeController private constructor() {

    private var effect: DseeAudioEffect? = null
    private val sessionEffects = LinkedHashMap<Int, DseeAudioEffect>()
    private var lastEnabled: Boolean = false
    private var lastMaster: Boolean = false
    private var nextTryAtMs = 0L

    @Synchronized
    fun isAvailable(): Boolean = obtain() != null

    @Synchronized
    fun apply(enabled: Boolean, master: Boolean, forceRebind: Boolean = false): Boolean {
        lastEnabled = enabled
        lastMaster = master
        val active = master && enabled
        if (!active) {
            if (effect != null) {
                try {
                    effect?.setDseeEnabled(false)
                } catch (_: Exception) {
                }
                drop()
            }
            releaseAllSessionEffects()
            return true
        }

        if (forceRebind && effect != null) {
            drop()
        }

        var globalOk = false
        repeat(2) {
            if (!globalOk) {
                val wasCreated = effect == null || effect?.isReady() != true
                val e = obtain()
                if (e != null) {
                    val ok = try {
                        val first = e.setDseeEnabled(true)
                        if (wasCreated && first) {
                            e.setDseeEnabled(false)
                            e.setDseeEnabled(true)
                        } else {
                            first
                        }
                    } catch (_: Exception) {
                        false
                    }
                    if (ok) {
                        globalOk = true
                    } else {
                        drop()
                    }
                }
            }
        }

        val iter = sessionEffects.entries.iterator()
        while (iter.hasNext()) {
            val (_, se) = iter.next()
            val ok = try {
                if (!se.isReady()) {
                    false
                } else {
                    if (forceRebind) {
                        se.setDseeEnabled(false)
                    }
                    se.setDseeEnabled(true)
                }
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                try {
                    se.release()
                } catch (_: Exception) {
                }
                iter.remove()
            }
        }

        return globalOk || sessionEffects.isNotEmpty()
    }

    @Synchronized
    fun syncActiveSessions(activeSessionIds: Set<Int>) {
        val active = lastMaster && lastEnabled

        val iter = sessionEffects.entries.iterator()
        while (iter.hasNext()) {
            val (sid, se) = iter.next()
            if (sid !in activeSessionIds || !active) {
                try {
                    se.setDseeEnabled(false)
                    se.release()
                } catch (_: Exception) {
                }
                iter.remove()
            }
        }

        if (!active) return

        for (sid in activeSessionIds) {
            if (sid <= 0) continue
            val existing = sessionEffects[sid]
            if (existing != null && existing.isReady()) continue
            if (existing != null) {
                try {
                    existing.release()
                } catch (_: Exception) {
                }
                sessionEffects.remove(sid)
            }
            try {
                val se = DseeAudioEffect(EFFECT_PRIORITY, sid)
                val ok = se.setDseeEnabled(true) && run {
                    se.setDseeEnabled(false)
                    se.setDseeEnabled(true)
                }
                if (ok) {
                    sessionEffects[sid] = se
                } else {
                    se.release()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun releaseAllSessionEffects() {
        for ((_, se) in sessionEffects) {
            try {
                se.setDseeEnabled(false)
                se.release()
            } catch (_: Exception) {
            }
        }
        sessionEffects.clear()
    }

    private fun obtain(): DseeAudioEffect? {
        effect?.let { existing ->
            if (existing.isReady()) return existing
            drop()
        }
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
        private const val RETRY_DELAY_MS = 2_000L

        @Volatile
        private var instance: DseeController? = null

        fun get(@Suppress("UNUSED_PARAMETER") context: Context): DseeController =
            instance ?: synchronized(this) {
                instance ?: DseeController().also { instance = it }
            }
    }
}
