package cc.machado.audioblackbox.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.machado.audioblackbox.BuildConfig
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.settings.ClampNotice
import cc.machado.audioblackbox.ui.ScreenHeader
import cc.machado.audioblackbox.ui.theme.AudioBlackboxTheme
import cc.machado.audioblackbox.ui.theme.AvionicsGreen
import cc.machado.audioblackbox.ui.theme.CARD_INNER_PADDING
import cc.machado.audioblackbox.ui.theme.CARD_SHAPE
import cc.machado.audioblackbox.ui.theme.FlightOrange
import cc.machado.audioblackbox.ui.theme.FlightOrangeContainer
import cc.machado.audioblackbox.ui.theme.SCREEN_GUTTER
import cc.machado.audioblackbox.ui.theme.SECTION_SPACING
import cc.machado.audioblackbox.ui.theme.primaryCtaButtonColors

import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import cc.machado.audioblackbox.audio.QualityPreset

/**
 * Hosts [SettingsViewModel] and renders [SettingsScreen] against its live state -- same
 * Route/Screen seam as [cc.machado.audioblackbox.ui.dashboard.DashboardRoute], so every visual
 * state is a plain, previewable function of a [SettingsUiState] value.
 *
 * Does not host its own [androidx.compose.material3.Scaffold] (issue #73): like
 * [cc.machado.audioblackbox.ui.dashboard.DashboardRoute], this is one of two destinations switched
 * by the floating bottom bar in [cc.machado.audioblackbox.ui.MainActivity]. That single outer
 * `Scaffold`'s `innerPadding` (system-bar insets plus the floating bar's own real, measured height
 * via its `bottomBar` slot) is applied once, above this screen -- see
 * [cc.machado.audioblackbox.ui.dashboard.DashboardRoute]'s doc and
 * [cc.machado.audioblackbox.ui.FloatingBottomBar]'s class doc for why this screen does not take its
 * own `contentPadding` parameter for that.
 */
@Composable
fun SettingsRoute(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        uiState = uiState,
        onSelectQualityPreset = viewModel::selectQualityPreset,
        onDecrement = viewModel::decrementPending,
        onIncrement = viewModel::incrementPending,
        onApply = viewModel::commitPendingRetentionWindow,
        onConfirmRetentionWindowChange = viewModel::confirmRetentionWindowChange,
        onCancelRetentionWindowChange = viewModel::cancelRetentionWindowChange,
        onAcknowledgeClampNotice = viewModel::acknowledgeClampNotice,
        modifier = modifier,
    )
}

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onSelectQualityPreset: (QualityPreset) -> Unit,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onApply: () -> Unit,
    onConfirmRetentionWindowChange: () -> Unit,
    onCancelRetentionWindowChange: () -> Unit,
    onAcknowledgeClampNotice: () -> Unit,
    modifier: Modifier = Modifier,
    versionName: String = BuildConfig.VERSION_NAME,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SCREEN_GUTTER),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
    ) {
        SettingsHeader()
        QualityPresetSection(
            presets = uiState.qualityPresets,
            onSelectQualityPreset = onSelectQualityPreset,
        )
        RetentionStepperSection(uiState.retentionStepper, onDecrement, onIncrement, onApply)
        AudioSpecsSection(selectedPreset = uiState.selectedPreset)
        ConsumptionTelemetrySection(telemetry = uiState.telemetry)
        PrivacySection(versionName = versionName)
    }
    uiState.retentionStepper.pendingConfirmationMinutes?.let { pendingMinutes ->
        RetentionDiscardDialog(
            pendingMinutes = pendingMinutes,
            onConfirm = onConfirmRetentionWindowChange,
            onCancel = onCancelRetentionWindowChange,
        )
    }
    uiState.clampNotice?.let { notice ->
        ClampNoticeDialog(notice = notice, onAcknowledge = onAcknowledgeClampNotice)
    }
}

@Composable
private fun SettingsHeader() {
    ScreenHeader(
        icon = painterResource(R.drawable.ic_settings_gear),
        title = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.settings_subtitle),
    )
}

/** The retention-window stepper (issue #73) -- how many minutes of audio the ring buffer is
 * *configured* to hold. Superseded the dashboard's fixed-chip selector (#45/#57): a `-`/`+` pair
 * in [AudioConfig.RETENTION_WINDOW_STEP_MINUTES][cc.machado.audioblackbox.audio.AudioConfig.RETENTION_WINDOW_STEP_MINUTES]
 * increments adjusts a *pending* value locally (see [SettingsViewModel]'s class doc for why), and
 * an explicit Apply button is the only thing that ever persists or rebuilds anything. */
@Composable
private fun RetentionStepperSection(
    stepper: RetentionStepperUiState,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onApply: () -> Unit,
) {
    val locked = stepper.pendingConfirmationMinutes != null
    val valueLabel = stringResource(R.string.settings_retention_value, stepper.pendingMinutes, stepper.approxPendingRamMb)
    val decrementCd = stringResource(R.string.settings_retention_decrement_cd)
    val incrementCd = stringResource(R.string.settings_retention_increment_cd)
    val stateAnnouncement = if (stepper.isDirty) {
        stringResource(R.string.settings_retention_value_pending_cd, stepper.pendingMinutes, stepper.approxPendingRamMb)
    } else {
        stringResource(R.string.settings_retention_value_active_cd, stepper.pendingMinutes, stepper.approxPendingRamMb)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(CARD_INNER_PADDING), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ram_memory),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = FlightOrange,
                )
                Text(
                    text = stringResource(R.string.settings_retention_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.settings_retention_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = stateAnnouncement
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onDecrement,
                    enabled = stepper.canDecrement && !locked,
                    modifier = Modifier.semantics { contentDescription = decrementCd },
                ) {
                    Text(text = "−", style = MaterialTheme.typography.headlineSmall)
                }
                Text(text = valueLabel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(
                    onClick = onIncrement,
                    enabled = stepper.canIncrement && !locked,
                    modifier = Modifier.semantics { contentDescription = incrementCd },
                ) {
                    Text(text = "+", style = MaterialTheme.typography.headlineSmall)
                }
            }

            if (stepper.isDirty) {
                Text(
                    text = stringResource(R.string.settings_retention_pending_notice, stepper.committedMinutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = onApply,
                enabled = stepper.isDirty && !locked,
                colors = primaryCtaButtonColors(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.settings_retention_apply_button),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun QualityPresetSection(
    presets: List<QualityPresetOption>,
    onSelectQualityPreset: (QualityPreset) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(CARD_INNER_PADDING),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_audio_specs),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = FlightOrange,
                )
                Text(
                    text = stringResource(R.string.settings_quality_preset_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.settings_quality_preset_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            presets.forEach { option ->
                val isSelected = option.isSelected
                val (titleRes, specsRes, descRes) = when (option.preset) {
                    QualityPreset.VOICE -> Triple(
                        R.string.settings_preset_voice_title,
                        R.string.settings_preset_voice_specs,
                        R.string.settings_preset_voice_desc,
                    )
                    QualityPreset.BALANCED -> Triple(
                        R.string.settings_preset_balanced_title,
                        R.string.settings_preset_balanced_specs,
                        R.string.settings_preset_balanced_desc,
                    )
                    QualityPreset.HIGH_FIDELITY -> Triple(
                        R.string.settings_preset_high_fidelity_title,
                        R.string.settings_preset_high_fidelity_specs,
                        R.string.settings_preset_high_fidelity_desc,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) FlightOrangeContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) FlightOrange else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectQualityPreset(option.preset) },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSelectQualityPreset(option.preset) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = FlightOrange,
                                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(titleRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isSelected) FlightOrange.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, if (isSelected) FlightOrange.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Text(
                                        text = stringResource(specsRes),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) FlightOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                text = stringResource(descRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.settings_preset_max_window, option.maxRetentionMinutes),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (isSelected) FlightOrange else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioSpecsSection(selectedPreset: QualityPreset = QualityPreset.DEFAULT) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(CARD_INNER_PADDING),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_audio_specs),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = FlightOrange,
                )
                Text(
                    text = stringResource(R.string.settings_specs_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            SpecRow(label = stringResource(R.string.settings_specs_format_label), value = stringResource(R.string.settings_specs_format_value))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            SpecRow(label = stringResource(R.string.settings_specs_export_label), value = stringResource(R.string.settings_specs_export_value))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            SpecRow(
                label = stringResource(R.string.settings_specs_sample_rate_label),
                value = "${selectedPreset.sampleRateHz} Hz",
                isMonospace = true,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            SpecRow(
                label = stringResource(R.string.settings_specs_channels_label),
                value = if (selectedPreset.channelCount == 1) {
                    stringResource(R.string.settings_specs_channels_value)
                } else {
                    stringResource(R.string.settings_specs_channels_stereo_value)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            SpecRow(label = stringResource(R.string.settings_specs_persistence_label), value = stringResource(R.string.settings_specs_persistence_value))
        }
    }
}

@Composable
private fun SpecRow(label: String, value: String, isMonospace: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(end = 12.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
private fun ConsumptionTelemetrySection(telemetry: PowerTelemetryUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(CARD_INNER_PADDING),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_battery_gauge),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = FlightOrange,
                )
                Text(
                    text = stringResource(R.string.settings_consumption_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.settings_consumption_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val batteryChargingText = if (telemetry.isCharging) {
                stringResource(R.string.settings_consumption_battery_charging)
            } else {
                stringResource(R.string.settings_consumption_battery_discharging)
            }
            val batteryLevelValue = stringResource(
                R.string.settings_consumption_battery_level_value,
                telemetry.batteryPercent,
                batteryChargingText,
            )
            val batteryOptValue = if (telemetry.isIgnoringBatteryOptimizations) {
                stringResource(R.string.settings_consumption_battery_opt_unrestricted)
            } else {
                stringResource(R.string.settings_consumption_battery_opt_optimized)
            }
            val ramBufferValue = stringResource(
                R.string.settings_consumption_ram_buffer_value,
                telemetry.bufferMemoryMb,
            )
            val ramHeapValue = stringResource(
                R.string.settings_consumption_ram_heap_value,
                telemetry.usedHeapMb,
                telemetry.maxHeapMb,
            )

            SpecRow(
                label = stringResource(R.string.settings_consumption_battery_drain_label),
                value = stringResource(R.string.settings_consumption_battery_drain_value),
                isMonospace = true,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            SpecRow(
                label = stringResource(R.string.settings_consumption_battery_level_label),
                value = batteryLevelValue,
                isMonospace = true,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            SpecRow(
                label = stringResource(R.string.settings_consumption_battery_opt_label),
                value = batteryOptValue,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            SpecRow(
                label = stringResource(R.string.settings_consumption_ram_buffer_label),
                value = ramBufferValue,
                isMonospace = true,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            SpecRow(
                label = stringResource(R.string.settings_consumption_ram_heap_label),
                value = ramHeapValue,
                isMonospace = true,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            SpecRow(
                label = stringResource(R.string.settings_consumption_io_label),
                value = stringResource(R.string.settings_consumption_io_value),
                isMonospace = true,
            )
        }
    }
}

@Composable
private fun PrivacySection(
    versionName: String = BuildConfig.VERSION_NAME,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CARD_SHAPE,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(CARD_INNER_PADDING),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_privacy_shield),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = AvionicsGreen,
                )
                Text(
                    text = stringResource(R.string.settings_privacy_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.settings_privacy_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_about_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_version_label, versionName),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** Blocks a retention-window change from ever applying silently (issue #45's core safety
 * requirement, carried into #73's stepper): shown whenever
 * [RetentionStepperUiState.pendingConfirmationMinutes] is non-null, i.e. the user tapped Apply
 * while the engine was still Recording/Paused with real audio buffered. Fires exactly once per
 * commit -- not per stepper tap, see [SettingsViewModel]'s class doc. Dismissing (tapping outside,
 * or the back gesture) is treated the same as [onCancel] -- there is no safe default other than
 * "nothing changes". */
@Composable
private fun RetentionDiscardDialog(pendingMinutes: Int, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = stringResource(R.string.settings_retention_confirm_title)) },
        text = { Text(text = stringResource(R.string.settings_retention_confirm_body, pendingMinutes)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.settings_retention_confirm_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = stringResource(R.string.settings_retention_confirm_cancel))
            }
        },
    )
}

/** Shown when the user's stored retention window was clamped down on load (issue #84). */
@Composable
private fun ClampNoticeDialog(notice: ClampNotice, onAcknowledge: () -> Unit) {
    AlertDialog(
        onDismissRequest = onAcknowledge,
        title = { Text(text = stringResource(R.string.settings_retention_clamp_notice_title)) },
        text = {
            Text(
                text = stringResource(
                    R.string.settings_retention_clamp_notice_body,
                    notice.previousMinutes,
                    notice.newMinutes,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge) {
                Text(text = stringResource(R.string.settings_retention_clamp_notice_dismiss))
            }
        },
    )
}

// ---- Previews ----

private fun previewStepperState(
    committedMinutes: Int = 30,
    pendingMinutes: Int = 30,
    canDecrement: Boolean = true,
    canIncrement: Boolean = true,
    pendingConfirmation: Int? = null,
    selectedPreset: QualityPreset = QualityPreset.VOICE,
): SettingsUiState = SettingsUiState(
    retentionStepper = RetentionStepperUiState(
        committedMinutes = committedMinutes,
        pendingMinutes = pendingMinutes,
        approxPendingRamMb = pendingMinutes * 2,
        isDirty = committedMinutes != pendingMinutes,
        canDecrement = canDecrement,
        canIncrement = canIncrement,
        pendingConfirmationMinutes = pendingConfirmation,
    ),
    qualityPresets = listOf(
        QualityPresetOption(QualityPreset.VOICE, maxRetentionMinutes = 45, isSelected = selectedPreset == QualityPreset.VOICE),
        QualityPresetOption(QualityPreset.BALANCED, maxRetentionMinutes = 30, isSelected = selectedPreset == QualityPreset.BALANCED),
        QualityPresetOption(QualityPreset.HIGH_FIDELITY, maxRetentionMinutes = 15, isSelected = selectedPreset == QualityPreset.HIGH_FIDELITY),
    ),
    selectedPreset = selectedPreset,
)

@Preview(showBackground = true, name = "Clean (30 min)")
@Composable
private fun SettingsScreenCleanPreview() {
    AudioBlackboxTheme {
        SettingsScreen(previewStepperState(), {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "Dirty (30 -> 45 min)")
@Composable
private fun SettingsScreenDirtyPreview() {
    AudioBlackboxTheme {
        SettingsScreen(previewStepperState(pendingMinutes = 45), {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "At minimum (5 min)")
@Composable
private fun SettingsScreenAtMinPreview() {
    AudioBlackboxTheme {
        SettingsScreen(previewStepperState(committedMinutes = 5, pendingMinutes = 5, canDecrement = false), {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "At maximum (45 min)")
@Composable
private fun SettingsScreenAtMaxPreview() {
    AudioBlackboxTheme {
        SettingsScreen(previewStepperState(committedMinutes = 45, pendingMinutes = 45, canIncrement = false), {}, {}, {}, {}, {}, {}, {})
    }
}
