/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

@file:Suppress("DEPRECATION")

package org.lunaris.dolby.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import android.os.SystemClock
import org.lunaris.dolby.DolbyConstants

/**
 * Manages Qualcomm's native audio_effects.xml proxy effects on global session 0:
 * - BassBoost proxy (14804144-a5ee-4d24-aa88-0002a5d5c51b -> libqcbassboost.so + offload_bundle)
 * - Virtualizer proxy (d3467faa-acc7-4d34-acaf-0002a5d5c51b -> libqcvirt.so + offload_bundle)
 *
 * Paired with Dolby DAP (libhwdap.so) in DolbyRepository for real QCOM DSP sub-bass weight and
 * hardware-offload-compatible spatial widening, with automatic deduplication (zero track-skip pop)
 * and graceful fallback to pure Dolby GEQ if the proxy is unavailable.
 */
internal class QcomEffectsController {

    data class ApplyResult(
        val bassActive: Boolean,
        val appliedBassStrength: Int,
        val virtActive: Boolean,
        val appliedVirtStrength: Int,
        /** False when the virtualizer was asked to turn OFF but could not be confirmed off. */
        val virtStateOk: Boolean = true,
    )

    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    private var lastBassEnabled: Boolean? = null
    private var lastBassStrength: Int? = null
    private var lastVirtEnabled: Boolean? = null
    private var lastVirtStrength: Int? = null

    private var virtOffConfirmed = true

    private var nextBassRetryMs = 0L
    private var nextVirtRetryMs = 0L

    @Synchronized
    fun invalidateCache() {
        lastBassEnabled = null
        lastBassStrength = null
        lastVirtEnabled = null
        lastVirtStrength = null
    }

    @Synchronized
    fun apply(bassStrength: Int, virtStrength: Int, force: Boolean = false): ApplyResult {
        val targetBass = bassStrength.coerceIn(0, 1000)
        val targetVirt = virtStrength.coerceIn(0, 1000)

        val appliedBass = applyBassBoost(targetBass, force)
        val appliedVirt = applyVirtualizer(targetVirt, force)

        return ApplyResult(
            bassActive = appliedBass > 0,
            appliedBassStrength = appliedBass,
            virtActive = appliedVirt > 0,
            appliedVirtStrength = appliedVirt,
            virtStateOk = virtOffConfirmed,
        )
    }

    @Synchronized
    fun disableAll(releaseHandles: Boolean = false) {
        try {
            bassBoost?.let { bb ->
                if (lastBassEnabled != false || releaseHandles) {
                    try {
                        if (bb.hasControl()) {
                            bb.setStrength(0)
                            if (bb.enabled) bb.enabled = false
                        }
                    } catch (_: Throwable) {
                    }
                }
                if (releaseHandles) {
                    try {
                        bb.release()
                    } catch (_: Throwable) {
                    }
                    bassBoost = null
                }
            }
        } catch (_: Throwable) {
            if (releaseHandles) bassBoost = null
        }

        try {
            virtualizer?.let { virt ->
                if (lastVirtEnabled != false || releaseHandles) {
                    try {
                        if (virt.hasControl()) {
                            virt.setStrength(0)
                            if (virt.enabled) virt.enabled = false
                        }
                    } catch (_: Throwable) {
                    }
                }
                if (releaseHandles) {
                    try {
                        virt.release()
                    } catch (_: Throwable) {
                    }
                    virtualizer = null
                }
            }
        } catch (_: Throwable) {
            if (releaseHandles) virtualizer = null
        }

        if (releaseHandles) {
            invalidateCache()
        } else {
            lastBassEnabled = false
            lastBassStrength = 0
            lastVirtEnabled = false
            lastVirtStrength = 0
        }
    }

    private fun applyBassBoost(strength: Int, force: Boolean): Int {
        if (strength <= 0) {
            bassBoost?.let { bb ->
                if (force || lastBassEnabled != false) {
                    try {
                        if (bb.hasControl()) {
                            bb.setStrength(0)
                            if (bb.enabled) bb.enabled = false
                        } else {
                            dropBassBoost()
                        }
                    } catch (_: Throwable) {
                        dropBassBoost()
                    }
                }
            }
            lastBassEnabled = false
            lastBassStrength = 0
            return 0
        }

        val currentEnabled = try {
            bassBoost?.enabled == true
        } catch (_: Throwable) {
            false
        }

        if (!force && lastBassEnabled == true && lastBassStrength == strength && currentEnabled && isBassReady()) {
            return strength
        }

        val bb = obtainBassBoost() ?: return 0
        return try {
            if (!bb.hasControl()) {
                dropBassBoost()
                return 0
            }
            if (!bb.enabled || lastBassStrength != strength) {
                bb.setStrength(strength.toShort())
            }
            if (!bb.enabled) {
                bb.enabled = true
            }
            lastBassEnabled = true
            lastBassStrength = strength
            strength
        } catch (t: Throwable) {
            DolbyConstants.dlog(TAG, "QCOM BassBoost apply failed: ${t.message}")
            dropBassBoost()
            0
        }
    }

    private fun applyVirtualizer(strength: Int, force: Boolean): Int {
        if (strength <= 0) {
            var offOk = true
            val virt = virtualizer
            if (virt != null) {
                if (force || lastVirtEnabled != false) {
                    offOk = try {
                        if (virt.hasControl()) {
                            virt.setStrength(0)
                            val st = virt.setEnabled(false)
                            st == AudioEffect.SUCCESS || !virt.enabled
                        } else {
                            // Someone else owns it: our handle is useless, take it over to switch it off.
                            dropVirtualizer()
                            forceOffWithEscalatedHandle()
                        }
                    } catch (_: Throwable) {
                        dropVirtualizer()
                        forceOffWithEscalatedHandle()
                    }
                }
            }
            virtOffConfirmed = offOk
            // Only cache "off" once it is confirmed; otherwise the next call must retry.
            lastVirtEnabled = if (offOk) false else null
            lastVirtStrength = if (offOk) 0 else null
            return 0
        }
        virtOffConfirmed = true

        val currentEnabled = try {
            virtualizer?.enabled == true
        } catch (_: Throwable) {
            false
        }

        if (!force && lastVirtEnabled == true && lastVirtStrength == strength && currentEnabled && isVirtReady()) {
            return strength
        }

        val virt = obtainVirtualizer() ?: return 0
        return try {
            if (!virt.hasControl()) {
                dropVirtualizer()
                return 0
            }
            if (!virt.enabled || lastVirtStrength != strength) {
                virt.setStrength(strength.toShort())
            }
            if (!virt.enabled) {
                val st = virt.setEnabled(true)
                if (st != AudioEffect.SUCCESS) {
                    throw IllegalStateException("Virtualizer setEnabled(true) status=$st")
                }
            }
            lastVirtEnabled = true
            lastVirtStrength = strength
            strength
        } catch (t: Throwable) {
            DolbyConstants.dlog(TAG, "QCOM Virtualizer apply failed: ${t.message}")
            dropVirtualizer()
            0
        }
    }

    private fun isBassReady(): Boolean = try {
        bassBoost?.hasControl() == true
    } catch (_: Throwable) {
        false
    }

    private fun isVirtReady(): Boolean = try {
        virtualizer?.hasControl() == true
    } catch (_: Throwable) {
        false
    }

    private fun obtainBassBoost(): BassBoost? {
        bassBoost?.let { existing ->
            if (isBassReady()) return existing
            dropBassBoost()
        }
        val now = SystemClock.elapsedRealtime()
        if (now < nextBassRetryMs) return null
        return try {
            val created = BassBoost(EFFECT_PRIORITY, 0)
            if (!created.strengthSupported) {
                created.release()
                nextBassRetryMs = now + RETRY_BACKOFF_MS
                null
            } else {
                bassBoost = created
                lastBassEnabled = null
                lastBassStrength = null
                created
            }
        } catch (t: Throwable) {
            DolbyConstants.dlog(TAG, "Failed to create QCOM BassBoost: ${t.message}")
            nextBassRetryMs = now + RETRY_BACKOFF_MS
            null
        }
    }

    private fun obtainVirtualizer(): Virtualizer? {
        virtualizer?.let { existing ->
            if (isVirtReady()) return existing
            dropVirtualizer()
        }
        val now = SystemClock.elapsedRealtime()
        if (now < nextVirtRetryMs) return null
        return try {
            var created = Virtualizer(EFFECT_PRIORITY, 0)
            if (!created.hasControl()) {
                DolbyConstants.dlog(TAG, "QCOM Virtualizer has no control, escalating priority")
                runCatching { created.release() }
                created = Virtualizer(EFFECT_PRIORITY + ESCALATED_PRIORITY_DELTA, 0)
            }
            if (!created.strengthSupported) {
                created.release()
                nextVirtRetryMs = now + RETRY_BACKOFF_MS
                null
            } else {
                virtualizer = created
                lastVirtEnabled = null
                lastVirtStrength = null
                created
            }
        } catch (t: Throwable) {
            DolbyConstants.dlog(TAG, "Failed to create QCOM Virtualizer: ${t.message}")
            nextVirtRetryMs = now + RETRY_BACKOFF_MS
            null
        }
    }

    /** Short-lived higher-priority handle used only to switch a foreign-owned virtualizer off. */
    private fun forceOffWithEscalatedHandle(): Boolean {
        val tmp = try {
            Virtualizer(EFFECT_PRIORITY + ESCALATED_PRIORITY_DELTA, 0)
        } catch (t: Throwable) {
            // The effect cannot even be instantiated, so nothing of ours can be running.
            DolbyConstants.dlog(TAG, "QCOM Virtualizer not instantiable for force-off: ${t.message}")
            return true
        }
        return try {
            try {
                if (!tmp.hasControl()) {
                    false
                } else {
                    tmp.setStrength(0)
                    tmp.setEnabled(false) == AudioEffect.SUCCESS || !tmp.enabled
                }
            } finally {
                runCatching { tmp.release() }
            }
        } catch (t: Throwable) {
            DolbyConstants.dlog(TAG, "QCOM Virtualizer force-off failed: ${t.message}")
            false
        }
    }

    private fun dropBassBoost() {
        try {
            bassBoost?.release()
        } catch (_: Throwable) {
        }
        bassBoost = null
        lastBassEnabled = null
        lastBassStrength = null
    }

    private fun dropVirtualizer() {
        try {
            virtualizer?.release()
        } catch (_: Throwable) {
        }
        virtualizer = null
        lastVirtEnabled = null
        lastVirtStrength = null
    }

    companion object {
        private const val TAG = "QcomEffects"
        private const val EFFECT_PRIORITY = 100
        private const val RETRY_BACKOFF_MS = 2_000L
        private const val ESCALATED_PRIORITY_DELTA = 1_000
    }
}