/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.lunaris.dolby.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import org.lunaris.dolby.R
import org.lunaris.dolby.domain.models.DolbyUiState
import org.lunaris.dolby.ui.components.*
import org.lunaris.dolby.ui.viewmodel.DolbyViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModernAdvancedSettingsScreen(
    viewModel: DolbyViewModel,
    navController: NavController,
    bottomPadding: Dp = 0.dp,
) {
    val uiState by viewModel.uiState.collectAsState()

    val listState = rememberLazyListState()
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    Scaffold(
        topBar = {
            DolbyTopAppBar(
                title = stringResource(R.string.dolby_category_adv_settings),
                showTitle = showTitle || uiState !is DolbyUiState.Success,
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
        when (val state = uiState) {
            is DolbyUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            is DolbyUiState.Success -> {
                ModernAdvancedSettingsContent(
                    state = state,
                    listState = listState,
                    viewModel = viewModel,
                    navController = navController,
                    bottomPadding = bottomPadding,
                    modifier = Modifier.padding(paddingValues)
                )
            }
            is DolbyUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun ModernAdvancedSettingsContent(
    state: DolbyUiState.Success,
    listState: LazyListState,
    viewModel: DolbyViewModel,
    navController: NavController,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    val latestProfileSettings by rememberUpdatedState(state.profileSettings)
    // Which ViperFX rows are open. Kept when rotating the screen; independent of the on/off switches.
    var bassExpanded by rememberSaveable { mutableStateOf(false) }
    var dynamicExpanded by rememberSaveable { mutableStateOf(false) }
    var clarityExpanded by rememberSaveable { mutableStateOf(false) }
    var midExpanded by rememberSaveable { mutableStateOf(false) }
    var speakerExpanded by rememberSaveable { mutableStateOf(false) }
    var dseeExpanded by rememberSaveable { mutableStateOf(false) }
    var levelerExpanded by rememberSaveable { mutableStateOf(false) }
    var virtualizerExpanded by rememberSaveable { mutableStateOf(false) }
    var dialogueExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = 20.dp, top = 12.dp, end = 20.dp, bottom = 12.dp + bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            DolbyPageHeading(
                title = stringResource(R.string.dolby_category_adv_settings),
                subtitle = stringResource(R.string.dolby_advanced_summary),
            )
        }
        if (state.settings.enabled) {
            item {
                ModernSettingsCard(
                    title = stringResource(R.string.dolby_category_settings),
                    icon = Icons.Default.Tune
                ) {
                    // ViperFX style rows: tapping a title opens or closes that effect's settings (on or off),
                    // and the switch on the row turns the effect on or off. ViPER bass and Dynamic system
                    // are separate effects, so both can be on at the same time.
                    val bassOn = state.profileSettings.bassLevel > 0 && state.profileSettings.bassCurve != 3
                    val dynamicOn = state.profileSettings.dynamicLevel > 0
                    val bassMode = state.profileSettings.bassCurve.coerceIn(0, 2)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ViperExpandableRow(
                            title = stringResource(R.string.dolby_viper_bass),
                            checked = bassOn,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    val target = if (latestProfileSettings.bassLevel > 0 && latestProfileSettings.bassCurve != 3) {
                                        latestProfileSettings.bassLevel
                                    } else {
                                        50
                                    }
                                    viewModel.enableViperBass(target)
                                } else {
                                    viewModel.setBassLevel(0)
                                }
                            },
                            expanded = bassExpanded,
                            onExpandedChange = { bassExpanded = it },
                            icon = Icons.Default.Speaker
                        )

                        AnimatedVisibility(visible = bassExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val bassModeSummaryRes = when (bassMode) {
                                    0 -> R.string.dolby_viper_bass_summary_natural
                                    1 -> R.string.dolby_viper_bass_summary_pure
                                    2 -> R.string.dolby_viper_bass_summary_subwoofer
                                    else -> R.string.dolby_viper_bass_summary
                                }
                                ViperInfoRow(text = stringResource(bassModeSummaryRes))
                                ViperChoiceRow(
                                    title = stringResource(R.string.dolby_viper_bass_mode),
                                    currentValue = bassMode,
                                    entries = R.array.dolby_viper_bass_mode_entries,
                                    values = R.array.dolby_viper_bass_mode_values,
                                    onValueChange = { viewModel.setBassCurve(it) },
                                    dialogIcon = Icons.Default.VolumeUp
                                )
                                ViperSliderRow(
                                    title = stringResource(R.string.dolby_viper_bass_frequency),
                                    value = state.profileSettings.bassCutoff,
                                    valueText = { "${it}Hz" },
                                    onValueChange = { viewModel.setBassCutoff(it) },
                                    valueRange = 15f..150f,
                                )
                                ViperSliderRow(
                                    title = stringResource(R.string.dolby_viper_bass_gain),
                                    value = if (bassOn) state.profileSettings.bassLevel else 0,
                                    valueText = { if (bassOn) "$it%" else "Off" },
                                    onValueChange = { viewModel.setBassLevel(it) },
                                    valueRange = 1f..100f,
                                    enabled = bassOn,
                                )
                            }
                        }

                        ViperExpandableRow(
                            title = stringResource(R.string.dolby_dynamic_system),
                            checked = dynamicOn,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    val target = if (latestProfileSettings.dynamicLevel > 0) latestProfileSettings.dynamicLevel else 45
                                    viewModel.setDynamicLevel(target)
                                } else {
                                    viewModel.setDynamicLevel(0)
                                }
                            },
                            expanded = dynamicExpanded,
                            onExpandedChange = { dynamicExpanded = it },
                            icon = Icons.Default.Tune
                        )

                        AnimatedVisibility(visible = dynamicExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ViperInfoRow(text = stringResource(R.string.dolby_dynamic_system_summary))
                                ViperSliderRow(
                                    title = stringResource(R.string.dolby_dynamic_bass_strength),
                                    value = state.profileSettings.dynamicLevel,
                                    valueText = { if (dynamicOn) "$it%" else "Off" },
                                    onValueChange = { viewModel.setDynamicLevel(it) },
                                    valueRange = 1f..100f,
                                    enabled = dynamicOn,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    val midOn = state.profileSettings.midLevel > 0
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ViperExpandableRow(
                            title = stringResource(R.string.dolby_mid_enhancer),
                            checked = midOn,
                            onCheckedChange = { enabled ->
                                if (enabled && state.profileSettings.midLevel == 0) {
                                    viewModel.setMidLevel(40)
                                } else if (!enabled) {
                                    viewModel.setMidLevel(0)
                                }
                            },
                            expanded = midExpanded,
                            onExpandedChange = { midExpanded = it },
                            icon = Icons.Default.VolumeUp
                        )

                        AnimatedVisibility(visible = midExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ViperInfoRow(text = stringResource(R.string.dolby_mid_enhancer_summary))
                                ViperSliderRow(
                                    title = stringResource(R.string.dolby_mid_level),
                                    value = state.profileSettings.midLevel,
                                    valueText = { if (midOn) "$it%" else "Off" },
                                    onValueChange = { viewModel.setMidLevel(it) },
                                    valueRange = 1f..100f,
                                    enabled = midOn,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val clarityOn = state.profileSettings.trebleLevel > 0
                        ViperExpandableRow(
                            title = stringResource(R.string.dolby_viper_clarity),
                            checked = clarityOn,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    val target = if (latestProfileSettings.trebleLevel > 0) latestProfileSettings.trebleLevel else 30
                                    viewModel.setTrebleLevel(target)
                                } else {
                                    viewModel.setTrebleLevel(0)
                                }
                            },
                            expanded = clarityExpanded,
                            onExpandedChange = { clarityExpanded = it },
                            icon = Icons.Default.Hearing
                        )

                        AnimatedVisibility(visible = clarityExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val clarityModeSummaryRes = when (state.profileSettings.clarityMode) {
                                    0 -> R.string.dolby_viper_clarity_summary_natural
                                    1 -> R.string.dolby_viper_clarity_summary_ozone
                                    2 -> R.string.dolby_viper_clarity_summary_xhifi
                                    else -> R.string.dolby_viper_clarity_summary
                                }
                                ViperInfoRow(text = stringResource(clarityModeSummaryRes))
                                ViperChoiceRow(
                                    title = stringResource(R.string.dolby_clarity_mode),
                                    currentValue = state.profileSettings.clarityMode,
                                    entries = R.array.dolby_clarity_mode_entries,
                                    values = R.array.dolby_clarity_mode_values,
                                    onValueChange = { viewModel.setClarityMode(it) },
                                    dialogIcon = Icons.Default.GraphicEq
                                )
                                ViperSliderRow(
                                    title = stringResource(R.string.dolby_viper_clarity_gain),
                                    value = state.profileSettings.trebleLevel,
                                    valueText = { if (clarityOn) "$it%" else "Off" },
                                    onValueChange = { viewModel.setTrebleLevel(it) },
                                    valueRange = 1f..100f,
                                    enabled = clarityOn,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    val speakerOn = state.profileSettings.speakerCorrection > 0
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ViperExpandableRow(
                            title = stringResource(R.string.dolby_speaker_correction),
                            checked = speakerOn,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    val target = if (latestProfileSettings.speakerCorrection > 0) latestProfileSettings.speakerCorrection else 50
                                    viewModel.setSpeakerCorrection(target)
                                } else {
                                    viewModel.setSpeakerCorrection(0)
                                }
                            },
                            expanded = speakerExpanded,
                            onExpandedChange = { speakerExpanded = it },
                            icon = Icons.Default.Speaker
                        )

                        AnimatedVisibility(visible = speakerExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ViperInfoRow(text = stringResource(R.string.dolby_speaker_correction_summary))
                                ViperSliderRow(
                                    title = stringResource(R.string.dolby_speaker_correction_strength),
                                    value = state.profileSettings.speakerCorrection,
                                    valueText = { if (speakerOn) "$it%" else "Off" },
                                    onValueChange = { viewModel.setSpeakerCorrection(it) },
                                    valueRange = 1f..100f,
                                    enabled = speakerOn,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ViperExpandableRow(
                            title = stringResource(R.string.dolby_dsee),
                            checked = state.profileSettings.dseeEnabled,
                            onCheckedChange = { viewModel.setDseeEnabled(it) },
                            expanded = dseeExpanded,
                            onExpandedChange = { dseeExpanded = it },
                            icon = Icons.Default.HighQuality
                        )

                        AnimatedVisibility(visible = dseeExpanded) {
                            ViperInfoRow(text = stringResource(R.string.dolby_dsee_summary))
                        }
                    }
                }
            }

            item {
                ModernSettingsCard(
                    title = "Volume Leveler",
                    icon = Icons.Default.VolumeDown
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ViperExpandableRow(
                            title = stringResource(R.string.dolby_volume_leveler),
                            checked = state.settings.volumeLevelerEnabled,
                            onCheckedChange = { viewModel.setVolumeLeveler(it) },
                            expanded = levelerExpanded,
                            onExpandedChange = { levelerExpanded = it },
                            icon = Icons.Default.BarChart
                        )

                        AnimatedVisibility(visible = levelerExpanded) {
                            ViperInfoRow(text = stringResource(R.string.dolby_volume_leveler_summary))
                        }
                    }
                }
            }
            
            if (state.settings.currentProfile != 0) {
                item {
                    ModernSettingsCard(
                        title = "3D sound",
                        icon = Icons.Default.Headphones
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.isOnSpeaker) {
                                ViperExpandableRow(
                                    title = stringResource(R.string.dolby_spk_virtualizer),
                                    checked = state.profileSettings.speakerVirtualizerEnabled,
                                    onCheckedChange = { viewModel.setSpeakerVirtualizer(it) },
                                    expanded = virtualizerExpanded,
                                    onExpandedChange = { virtualizerExpanded = it },
                                    icon = Icons.Default.Speaker
                                )

                                AnimatedVisibility(visible = virtualizerExpanded) {
                                    ViperInfoRow(text = stringResource(R.string.dolby_spk_virtualizer_summary))
                                }
                            } else {
                                val hpOn = state.profileSettings.headphoneVirtualizerEnabled
                                ViperExpandableRow(
                                    title = stringResource(R.string.dolby_hp_virtualizer),
                                    checked = hpOn,
                                    onCheckedChange = { viewModel.setHeadphoneVirtualizer(it) },
                                    expanded = virtualizerExpanded,
                                    onExpandedChange = { virtualizerExpanded = it },
                                    icon = Icons.Default.Headphones
                                )

                                AnimatedVisibility(visible = virtualizerExpanded) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ViperInfoRow(text = stringResource(R.string.dolby_hp_virtualizer_summary))
                                        ViperSliderRow(
                                            title = stringResource(R.string.dolby_hp_virtualizer_dolby_strength),
                                            value = state.profileSettings.stereoWideningAmount,
                                            valueText = { if (hpOn) "$it" else "Off" },
                                            onValueChange = { viewModel.setStereoWidening(it) },
                                            valueRange = 4f..64f,
                                            steps = 59,
                                            enabled = hpOn,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                item {
                    ModernSettingsCard(
                        title = "Dialogue Enhancement",
                        icon = Icons.Default.RecordVoiceOver
                    ) {
                        val dialogueOn = state.profileSettings.dialogueEnhancerEnabled
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ViperExpandableRow(
                                title = stringResource(R.string.dolby_dialogue_enhancer),
                                checked = dialogueOn,
                                onCheckedChange = { viewModel.setDialogueEnhancer(it) },
                                expanded = dialogueExpanded,
                                onExpandedChange = { dialogueExpanded = it },
                                icon = Icons.Default.RecordVoiceOver
                            )

                            AnimatedVisibility(visible = dialogueExpanded) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ViperInfoRow(text = stringResource(R.string.dolby_dialogue_enhancer_summary))
                                    ViperSliderRow(
                                        title = stringResource(R.string.dolby_dialogue_enhancer_dolby_strength),
                                        value = state.profileSettings.dialogueEnhancerAmount,
                                        valueText = { if (dialogueOn) "$it" else "Off" },
                                        onValueChange = { viewModel.setDialogueEnhancerAmount(it) },
                                        valueRange = 1f..12f,
                                        steps = 10,
                                        enabled = dialogueOn,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.dolby_adv_settings_footer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
        
    }
}
