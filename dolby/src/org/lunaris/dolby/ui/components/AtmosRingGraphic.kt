/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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

    val ring = if (enabled) ringColor else ringColor.copy(alpha = 0.45f)
    val accent = if (enabled) accentColor else accentColor.copy(alpha = 0.45f)
    val rippleBase = if (enabled) accentColor else ringColor

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val ringRadius = min(size.width, size.height) * 0.29f
        val ringStroke = ringRadius * 0.143f

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
            center = center,
            style = Stroke(width = ringStroke),
        )

        // Dot + glow (rests at the top right, orbits while enabled)
        val angle = (-75f + if (enabled) orbit * 360f else 0f) * (PI.toFloat() / 180f)
        val dot = Offset(
            center.x + ringRadius * cos(angle),
            center.y + ringRadius * sin(angle),
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
