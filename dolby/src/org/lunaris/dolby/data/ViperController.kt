/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.data

import android.content.Context
import android.media.audiofx.AudioEffect
import android.os.SystemClock
import org.lunaris.dolby.DolbyConstants
import org.lunaris.dolby.audio.ViperAudioEffect

class ViperController private constructor() {

    data class Settings(
        val bassOn: Boolean,
        val bassMode: Int,
        val bassFrequency: Int,
        val bassGain: Int,
        val clarityOn: Boolean,
        val clarityMode: Int,
        val clarityGain: Int,
        val dynamicOn: Boolean,
        val dynamicStrength: Int,
        val speakerCorrectionOn: Boolean = false,
        val speakerStrength: Int = 0,
        val surroundOn: Boolean = false,
        val surroundWidening: Int = 50,
        val surroundMidImage: Int = 100,
        val surroundDepth: Int = 35,
    ) {
        val anyOn: Boolean
            get() = bassOn || clarityOn || dynamicOn || speakerCorrectionOn || surroundOn
    }

    private var effect: ViperAudioEffect? = null
    private val sessionEffects = LinkedHashMap<Int, ViperAudioEffect>()
    private var lastSettings: Settings? = null
    private var lastMaster: Boolean = false
    @Volatile
    private var restrictedPlayback: Boolean = false
    private var nextTryAtMs = 0L
    private var everCreated = false

    @Synchronized
    fun setRestrictedPlayback(restricted: Boolean) {
        if (restrictedPlayback == restricted) return
        restrictedPlayback = restricted
        val settings = lastSettings
        if (restricted) {
            if (settings != null) {
                ViperEffectModel.applyToDspEngine(effect, settings, masterEnabled = false)
            }
            if (effect != null) {
                try {
                    effect?.setEnabled(false)
                } catch (_: Exception) {
                }
                drop()
            }
            releaseAllSessionEffects()
        } else if (settings != null) {
            apply(settings, lastMaster, forceRebind = false)
        }
    }

    /**
     * True when the ViPER effect exists on this ROM. Once it was created successfully a later
     * hiccup (the 2 s retry back-off) no longer reports it as missing, otherwise the app would
     * silently fall back to baking bass into the equalizer bands.
     */
    @Synchronized
    fun isAvailable(): Boolean {
        if (everCreated) return true
        if (obtain() == null) return false
        // Only a probe: do not keep an idle effect around when nothing needs it.
        if (!(lastMaster && !restrictedPlayback && lastSettings?.anyOn == true)) drop()
        return true
    }

    @Synchronized
    fun apply(settings: Settings, master: Boolean, forceRebind: Boolean = false): Boolean {
        lastSettings = settings
        lastMaster = master
        val effectiveMaster = master && !restrictedPlayback

        // Full teardown only when Dolby Master is OFF or restricted (Dolby Vision / Movie / Multichannel) playback.
        if (!effectiveMaster) {
            ViperEffectModel.applyToDspEngine(effect, settings, masterEnabled = false)
            if (effect != null) {
                try {
                    effect?.setEnabled(false)
                } catch (ex: Exception) {
                    DolbyConstants.dlog(TAG, "ViPER disable failed: ${ex.message}")
                }
                drop()
            }
            releaseAllSessionEffects()
            return true
        }

        // DSP bypass when effectiveMaster is ON but all ViPER toggles are OFF (e.g. Dynamic profile):
        // disable without dropping/releasing handles so AudioFlinger keeps them pinned across profile switches.
        if (!settings.anyOn) {
            ViperEffectModel.applyToDspEngine(effect, settings, masterEnabled = false)
            if (effect != null && effect?.isReady() == true) {
                try {
                    effect?.setEnabled(false)
                } catch (ex: Exception) {
                    DolbyConstants.dlog(TAG, "ViPER bypass disable failed: ${ex.message}")
                }
            }
            val iter = sessionEffects.entries.iterator()
            while (iter.hasNext()) {
                val (_, se) = iter.next()
                try {
                    if (se.isReady()) {
                        ViperEffectModel.applyToDspEngine(se, settings, masterEnabled = false)
                        se.setEnabled(false)
                    } else {
                        se.release()
                        iter.remove()
                    }
                } catch (_: Exception) {
                    try {
                        se.release()
                    } catch (_: Exception) {
                    }
                    iter.remove()
                }
            }
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
                        pushToEffect(e, settings, master = true, prime = wasCreated)
                    } catch (ex: Exception) {
                        DolbyConstants.dlog(TAG, "ViPER push failed: ${ex.message}")
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
                        se.setEnabled(false)
                        se.setEnabled(true)
                    }
                    pushToEffect(se, settings, master = true, prime = false)
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
        val settings = lastSettings
        val effectiveMaster = lastMaster && !restrictedPlayback

        if (!effectiveMaster || settings == null) {
            releaseAllSessionEffects()
            return
        }

        val active = settings.anyOn

        val iter = sessionEffects.entries.iterator()
        while (iter.hasNext()) {
            val (sid, se) = iter.next()
            if (sid !in activeSessionIds) {
                try {
                    se.setEnabled(false)
                    se.release()
                } catch (_: Exception) {
                }
                iter.remove()
            }
        }

        for (sid in activeSessionIds) {
            if (sid <= 0) continue
            val existing = sessionEffects[sid]
            if (existing != null && existing.isReady()) {
                if (!active) {
                    try {
                        ViperEffectModel.applyToDspEngine(existing, settings, masterEnabled = false)
                        existing.setEnabled(false)
                    } catch (_: Exception) {
                    }
                    continue
                }
                val reEnabledOk = try {
                    pushToEffect(existing, settings, master = true, prime = false)
                } catch (_: Exception) {
                    false
                }
                if (reEnabledOk) continue
            }
            if (existing != null) {
                try {
                    existing.release()
                } catch (_: Exception) {
                }
                sessionEffects.remove(sid)
            }
            if (!active) continue
            var se: ViperAudioEffect? = null
            try {
                se = ViperAudioEffect(EFFECT_PRIORITY, sid)
                if (pushToEffect(se, settings, master = true, prime = true)) {
                    sessionEffects[sid] = se
                } else {
                    se.release()
                }
            } catch (_: Exception) {
                try {
                    se?.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun pushToEffect(
        e: ViperAudioEffect,
        settings: Settings,
        master: Boolean,
        prime: Boolean
    ): Boolean {
        if (prime) {
            e.setEnabled(true)
            ViperEffectModel.applyToDspEngine(e, settings, master)
            e.setEnabled(false)
            e.setEnabled(true)
        }
        val pushed = ViperEffectModel.applyToDspEngine(e, settings, master)
        val enabledOk = e.enabled || e.setEnabled(true) == AudioEffect.SUCCESS
        return pushed && enabledOk
    }

    private fun releaseAllSessionEffects() {
        for ((_, se) in sessionEffects) {
            try {
                se.setEnabled(false)
                se.release()
            } catch (_: Exception) {
            }
        }
        sessionEffects.clear()
    }

    private fun obtain(): ViperAudioEffect? {
        effect?.let { existing ->
            if (existing.isReady()) return existing
            drop()
        }
        val now = SystemClock.elapsedRealtime()
        if (now < nextTryAtMs) return null
        return try {
            ViperAudioEffect(EFFECT_PRIORITY, 0).also {
                effect = it
                everCreated = true
            }
        } catch (e: Exception) {
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
        private const val TAG = "ViperController"
        private const val EFFECT_PRIORITY = 100
        private const val RETRY_DELAY_MS = 2_000L

        @Volatile
        private var instance: ViperController? = null

        fun get(@Suppress("UNUSED_PARAMETER") context: Context): ViperController =
            instance ?: synchronized(this) {
                instance ?: ViperController().also { instance = it }
            }
    }
}
