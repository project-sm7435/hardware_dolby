/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.lunaris.dolby.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.lunaris.dolby.utils.HapticFeedbackHelper
import org.lunaris.dolby.utils.rememberHapticFeedback

/**
 * ViperFX style rows for the advanced settings: a plain switch row, a row that opens a radio
 * list in a dialog, and a slider row with the value shown under the title.
 */

/** Switch row with only an icon and a title (no summary line), like ViperFX. */
@Composable
fun ViperSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val haptic = rememberHapticFeedback()
    val scope = rememberCoroutineScope()

    val containerColor by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "viper_switch_container"
    )
    val contentColor = if (checked) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurface

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large),
        color = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = Role.Switch,
                    onValueChange = {
                        scope.launch { haptic.performHaptic(HapticFeedbackHelper.HapticIntensity.CLICK) }
                        onCheckedChange(it)
                    },
                )
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = contentColor
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = checked,
                onCheckedChange = null,
                thumbContent = {
                    Crossfade(
                        targetState = checked,
                        animationSpec = MaterialTheme.motionScheme.slowEffectsSpec(),
                        label = "viper_switch_icon"
                    ) { isChecked ->
                        Icon(
                            imageVector = if (isChecked) Icons.Rounded.Check else Icons.Rounded.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            )
        }
    }
}

/**
 * Row showing the title and the current choice. Tapping it opens a dialog with a radio list,
 * a header icon and a Cancel button.
 */
@Composable
fun ViperChoiceRow(
    title: String,
    currentValue: Int,
    entries: Int,
    values: Int,
    onValueChange: (Int) -> Unit,
    dialogIcon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val entryList = stringArrayResource(entries)
    val valueList = stringArrayResource(values)
    val currentIndex = valueList.indexOfFirst { it.toIntOrNull() == currentValue }
    val currentLabel = entryList.getOrElse(currentIndex.coerceAtLeast(0)) { "" }
    var open by remember { mutableStateOf(false) }
    val haptic = rememberHapticFeedback()
    val scope = rememberCoroutineScope()

    Surface(
        onClick = {
            scope.launch { haptic.performHaptic(HapticFeedbackHelper.HapticIntensity.TICK) }
            open = true
        },
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = currentLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            icon = { Icon(imageVector = dialogIcon, contentDescription = null) },
            title = {
                Text(
                    text = title,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(modifier = Modifier.selectableGroup()) {
                    entryList.forEachIndexed { index, entry ->
                        val value = valueList.getOrNull(index)?.toIntOrNull() ?: return@forEachIndexed
                        val selected = value == currentValue
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.large)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0f)
                                )
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        scope.launch {
                                            haptic.performHaptic(HapticFeedbackHelper.HapticIntensity.CLICK)
                                        }
                                        open = false
                                        if (!selected) onValueChange(value)
                                    }
                                )
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(
                                text = entry,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

/** Slider row: title, the current value right under it, then the slider. */
@Composable
fun ViperSliderRow(
    title: String,
    value: Int,
    valueText: (Int) -> String,
    onValueChange: (Int) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    val haptic = rememberHapticFeedback()
    val scope = rememberCoroutineScope()
    // While dragging, follow the finger instead of waiting for the saved value to come back.
    var dragValue by remember { mutableStateOf<Float?>(null) }
    var lastHaptic by remember { mutableIntStateOf(value) }
    val shown = dragValue?.toInt() ?: value

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = valueText(shown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = dragValue ?: value.toFloat(),
                onValueChange = { newValue ->
                    dragValue = newValue
                    val intValue = newValue.toInt()
                    if (intValue != lastHaptic) {
                        scope.launch { haptic.performHaptic(HapticFeedbackHelper.HapticIntensity.TEXTURE_TICK) }
                        lastHaptic = intValue
                        onValueChange(intValue)
                    }
                },
                onValueChangeFinished = {
                    dragValue?.let { onValueChange(it.toInt()) }
                    dragValue = null
                },
                valueRange = valueRange,
                steps = steps,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
