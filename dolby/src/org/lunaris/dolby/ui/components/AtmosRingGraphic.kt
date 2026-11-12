/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun AtmosRingGraphic(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    ringColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val transition = rememberInfiniteTransition(label = "atmos_ring")
    var orbit by remember { mutableStateOf(0f) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            orbit = (orbit + (now - last) / LAP_NANOS) % 1f
            last = now
        }
    }
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathe",
    )

    // Drag offset of the ring from its resting (centre) position.
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    var springJob by remember { mutableStateOf<Job?>(null) }

    val ring = if (enabled) ringColor else ringColor.copy(alpha = 0.45f)
    val accent = if (enabled) accentColor else accentColor.copy(alpha = 0.45f)
    val rippleBase = if (enabled) accentColor else ringColor

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val radius = min(w, h) * RING_RADIUS_FRACTION
                // The ring has to stay inside the box.
                val maxX = (w / 2f - radius * (1f + RING_STROKE_FRACTION)).coerceAtLeast(0f)
                val maxY = (h / 2f - radius * (1f + RING_STROKE_FRACTION)).coerceAtLeast(0f)
                fun clamp(o: Offset) = Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))

                val down = awaitFirstDown(requireUnconsumed = false)
                val ringCenter = Offset(w / 2f, h / 2f) + dragOffset
                if ((down.position - ringCenter).getDistance() > radius * GRAB_RADIUS_FACTOR) {
                    return@awaitEachGesture
                }

                springJob?.cancel()
                try {
                    val slop = awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
                        change.consume()
                        dragOffset = clamp(dragOffset + overSlop)
                    }
                    if (slop != null) {
                        drag(slop.id) { change ->
                            dragOffset = clamp(dragOffset + change.positionChange())
                            change.consume()
                        }
                    }
                } finally {
                    // Let go (or gesture cancelled): ease back to the normal position.
                    springJob = scope.launch {
                        Animatable(dragOffset, Offset.VectorConverter).animateTo(
                            targetValue = Offset.Zero,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        ) { dragOffset = value }
                    }
                }
            }
        },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val ringRadius = min(size.width, size.height) * RING_RADIUS_FRACTION
        val ringStroke = ringRadius * RING_STROKE_FRACTION
        val ringCenter = center + dragOffset

        // Ripples
        val ripples = floatArrayOf(1.75f, 2.5f, 3.3f)
        val alphas = floatArrayOf(0.22f, 0.15f, 0.09f)
        ripples.forEachIndexed { i, factor ->
            val wave = if (enabled) 1f + 0.035f * sin((breathe + i * 0.33f) * PI.toFloat()) else 1f
            drawCircle(
                color = rippleBase.copy(alpha = alphas[i] * if (enabled) 1f else 0.6f),
                radius = ringRadius * factor * wave,
                center = center,
                style = Stroke(width = 1.2f * density),
            )
        }

        // Ring
        drawCircle(
            color = ring,
            radius = ringRadius,
            center = ringCenter,
            style = Stroke(width = ringStroke),
        )

        // Dot + glow (rests at the top right, orbits while enabled)
        val angle = (-75f + if (enabled) orbit * 360f else 0f) * (PI.toFloat() / 180f)
        val dot = Offset(
            ringCenter.x + ringRadius * cos(angle),
            ringCenter.y + ringRadius * sin(angle),
        )
        val dotRadius = ringRadius * 0.17f
        if (enabled) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.5f), Color.Transparent),
                    center = dot,
                    radius = dotRadius * 3.4f,
                ),
                radius = dotRadius * 3.4f,
                center = dot,
            )
        }
        drawCircle(color = accent, radius = dotRadius, center = dot)
    }
}

private const val LAP_NANOS = 9_050_000_000f
private const val RING_RADIUS_FRACTION = 0.29f
private const val RING_STROKE_FRACTION = 0.143f

/** A touch within this many ring radii of the ring's centre grabs it. */
private const val GRAB_RADIUS_FACTOR = 1.3f
