package cc.machado.audioblackbox.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
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
import cc.machado.audioblackbox.ui.theme.AvionicsCard
import cc.machado.audioblackbox.ui.theme.AvionicsCardHeaderBar
import cc.machado.audioblackbox.ui.theme.AvionicsGreen
import cc.machado.audioblackbox.ui.theme.AvionicsTag
import cc.machado.audioblackbox.ui.theme.CARD_INNER_PADDING
import cc.machado.audioblackbox.ui.theme.CARD_SHAPE
import cc.machado.audioblackbox.ui.theme.CautionAmber
import cc.machado.audioblackbox.ui.theme.CockpitBorder
import cc.machado.audioblackbox.ui.theme.CockpitBorderStrong
import cc.machado.audioblackbox.ui.theme.CockpitPanel
import cc.machado.audioblackbox.ui.theme.CockpitPanelRaised
import cc.machado.audioblackbox.ui.theme.CockpitSlate
import cc.machado.audioblackbox.ui.theme.FlightOrange
import cc.machado.audioblackbox.ui.theme.FlightOrangeContainer
import cc.machado.audioblackbox.ui.theme.RADIUS_RIVET
import cc.machado.audioblackbox.ui.theme.RADIUS_SM
import cc.machado.audioblackbox.ui.theme.SCREEN_GUTTER
import cc.machado.audioblackbox.ui.theme.SECTION_SPACING
import cc.machado.audioblackbox.ui.theme.TextDim
import cc.machado.audioblackbox.ui.theme.TextMuted
import cc.machado.audioblackbox.ui.theme.WarningRed
import androidx.compose.foundation.clickable
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
        onAcknowledgeClampNotice = viewModel::acknowledgeClampNotice,
        onDismissResizeError = viewModel::dismissResizeError,
        modifier = modifier,
    )
}

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onSelectQualityPreset: (QualityPreset) -> Unit,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onAcknowledgeClampNotice: () -> Unit,
    onDismissResizeError: () -> Unit = {},
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
        RetentionStepperSection(uiState.retentionStepper, onDecrement, onIncrement)
        AudioSpecsSection(selectedPreset = uiState.selectedPreset)
        ConsumptionTelemetrySection(telemetry = uiState.telemetry)
        PrivacySection(versionName = versionName)
    }
    uiState.clampNotice?.let { notice ->
        ClampNoticeDialog(notice = notice, onAcknowledge = onAcknowledgeClampNotice)
    }
    uiState.resizeError?.let { errorInfo ->
        ResizeErrorDialog(errorInfo = errorInfo, onAcknowledge = onDismissResizeError)
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
 * increments adjusts a *pending* value locally, which [SettingsViewModel] persists and applies on
 * its own, debounced 500 ms after the last tap (issue #299) -- there is no Apply button any more. */
@Composable
private fun RetentionStepperSection(
    stepper: RetentionStepperUiState,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    val valueLabel = stringResource(R.string.settings_retention_value, stepper.pendingMinutes, stepper.approxPendingRamMb)
    val decrementCd = stringResource(R.string.settings_retention_decrement_cd)
    val incrementCd = stringResource(R.string.settings_retention_increment_cd)
    val stateAnnouncement = if (stepper.isDirty) {
        stringResource(R.string.settings_retention_value_pending_cd, stepper.pendingMinutes, stepper.approxPendingRamMb)
    } else {
        stringResource(R.string.settings_retention_value_active_cd, stepper.pendingMinutes, stepper.approxPendingRamMb)
    }

    AvionicsCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AvionicsCardHeaderBar(
                label = stringResource(R.string.settings_card_retention_label),
                tag = {
                    AvionicsTag(text = "~${stepper.approxPendingRamMb} MB")
                },
            )
            Text(
                text = stringResource(R.string.settings_retention_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Surface(
                shape = RoundedCornerShape(RADIUS_SM),
                color = CockpitSlate,
                border = BorderStroke(1.dp, CockpitBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .semantics(mergeDescendants = true) {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = stateAnnouncement
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val canDec = stepper.canDecrement
                    Surface(
                        shape = RoundedCornerShape(RADIUS_RIVET),
                        color = if (canDec) CockpitPanelRaised else CockpitPanel,
                        border = BorderStroke(1.dp, if (canDec) FlightOrange.copy(alpha = 0.5f) else CockpitBorder),
                        modifier = Modifier
                            .size(42.dp)
                            .clickable(
                                enabled = canDec,
                                onClick = onDecrement,
                            )
                            .semantics { contentDescription = decrementCd },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "−",
                                style = MaterialTheme.typography.titleLarge,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (canDec) FlightOrange else TextDim,
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "${stepper.pendingMinutes} min",
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Text(
                            text = stringResource(R.string.settings_retention_buffer_type),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }

                    val canInc = stepper.canIncrement
                    Surface(
                        shape = RoundedCornerShape(RADIUS_RIVET),
                        color = if (canInc) CockpitPanelRaised else CockpitPanel,
                        border = BorderStroke(1.dp, if (canInc) FlightOrange.copy(alpha = 0.5f) else CockpitBorder),
                        modifier = Modifier
                            .size(42.dp)
                            .clickable(
                                enabled = canInc,
                                onClick = onIncrement,
                            )
                            .semantics { contentDescription = incrementCd },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "+",
                                style = MaterialTheme.typography.titleLarge,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (canInc) FlightOrange else TextDim,
                            )
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun QualityPresetSection(
    presets: List<QualityPresetOption>,
    onSelectQualityPreset: (QualityPreset) -> Unit,
) {
    val selectedOption = presets.firstOrNull { it.isSelected }
    val selectedTag = when (selectedOption?.preset) {
        QualityPreset.VOICE -> "16 kHz · MONO"
        QualityPreset.BALANCED -> "32 kHz · MONO"
        QualityPreset.HIGH_FIDELITY -> "44.1 kHz · STEREO"
        null -> ""
    }

    AvionicsCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AvionicsCardHeaderBar(
                label = stringResource(R.string.settings_card_preset_label),
                tag = {
                    if (selectedTag.isNotEmpty()) {
                        AvionicsTag(text = selectedTag)
                    }
                },
            )
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
                    shape = RoundedCornerShape(RADIUS_SM),
                    color = if (isSelected) FlightOrangeContainer.copy(alpha = 0.15f) else CockpitSlate,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) FlightOrange else CockpitBorder,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectQualityPreset(option.preset) },
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(titleRes),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                            )
                            if (option.preset == QualityPreset.VOICE) {
                                AvionicsTag(
                                    text = stringResource(R.string.settings_tag_default),
                                    color = FlightOrange,
                                    containerColor = FlightOrangeContainer,
                                )
                            }
                        }
                        Text(
                            text = stringResource(descRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                        Text(
                            text = "${stringResource(specsRes)} · ${stringResource(R.string.settings_preset_max_window, option.maxRetentionMinutes)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) FlightOrange else TextDim,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioSpecsSection(selectedPreset: QualityPreset = QualityPreset.DEFAULT) {
    AvionicsCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AvionicsCardHeaderBar(
                label = stringResource(R.string.settings_card_specs_label),
            )

            SpecRow(label = stringResource(R.string.settings_specs_format_label), value = stringResource(R.string.settings_specs_format_value), isMonospace = true)
            HorizontalDivider(color = CockpitBorder)
            SpecRow(label = stringResource(R.string.settings_specs_export_label), value = stringResource(R.string.settings_specs_export_value), isMonospace = true)
            HorizontalDivider(color = CockpitBorder)
            SpecRow(
                label = stringResource(R.string.settings_specs_sample_rate_label),
                value = "${selectedPreset.sampleRateHz} Hz",
                isMonospace = true,
            )
            HorizontalDivider(color = CockpitBorder)
            SpecRow(
                label = stringResource(R.string.settings_specs_channels_label),
                value = if (selectedPreset.channelCount == 1) {
                    stringResource(R.string.settings_specs_channels_value)
                } else {
                    stringResource(R.string.settings_specs_channels_stereo_value)
                },
                isMonospace = true,
            )
            HorizontalDivider(color = CockpitBorder)
            SpecRow(label = stringResource(R.string.settings_specs_persistence_label), value = stringResource(R.string.settings_specs_persistence_value), isMonospace = true)
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
    AvionicsCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AvionicsCardHeaderBar(
                label = stringResource(R.string.settings_card_telemetry_label),
                tag = {
                    AvionicsTag(
                        text = stringResource(R.string.settings_tag_live),
                        color = AvionicsGreen,
                        containerColor = AvionicsGreen.copy(alpha = 0.15f),
                        borderColor = AvionicsGreen.copy(alpha = 0.4f),
                    )
                },
            )
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
            HorizontalDivider(color = CockpitBorder)
            SpecRow(
                label = stringResource(R.string.settings_consumption_battery_level_label),
                value = batteryLevelValue,
                isMonospace = true,
            )
            HorizontalDivider(color = CockpitBorder)
            SpecRow(
                label = stringResource(R.string.settings_consumption_battery_opt_label),
                value = batteryOptValue,
            )
            HorizontalDivider(color = CockpitBorder)
            SpecRow(
                label = stringResource(R.string.settings_consumption_ram_buffer_label),
                value = ramBufferValue,
                isMonospace = true,
            )
            HorizontalDivider(color = CockpitBorder)
            SpecRow(
                label = stringResource(R.string.settings_consumption_ram_heap_label),
                value = ramHeapValue,
                isMonospace = true,
            )
            HorizontalDivider(color = CockpitBorder)
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
    buildDate: String = BuildConfig.BUILD_DATE,
) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val productUrl = stringResource(R.string.settings_website_url)

    AvionicsCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AvionicsCardHeaderBar(
                label = stringResource(R.string.settings_card_privacy_label),
            )
            Text(
                text = stringResource(R.string.settings_privacy_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = CockpitBorder)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri(productUrl) },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_website_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "alexandre.machado.cc/audio-blackbox",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = FlightOrange,
                )
            }
            HorizontalDivider(color = CockpitBorder)
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
                    text = stringResource(R.string.settings_version_with_date_label, versionName, buildDate),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** Shown when the user's stored retention window was clamped down on load (issue #84). */
@Composable
private fun ClampNoticeDialog(notice: ClampNotice, onAcknowledge: () -> Unit) {
    AlertDialog(
        onDismissRequest = onAcknowledge,
        shape = CARD_SHAPE,
        containerColor = CockpitPanel,
        tonalElevation = 6.dp,
        title = {
            Text(
                text = stringResource(R.string.settings_retention_clamp_notice_title),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = CautionAmber,
            )
        },
        text = {
            Text(
                text = stringResource(
                    R.string.settings_retention_clamp_notice_body,
                    notice.previousMinutes,
                    notice.newMinutes,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge) {
                Text(
                    text = stringResource(R.string.settings_retention_clamp_notice_dismiss),
                    fontWeight = FontWeight.Bold,
                    color = FlightOrange,
                )
            }
        },
    )
}

/**
 * Shown when a live settings change was refused because the ring buffer resize it required could
 * not fit given the device's current heap state (issue #272). [errorInfo] carries the real,
 * specific numbers computed by [SettingsViewModel.describeRefusal]; the wording itself comes from
 * `strings.xml` (`R.string.settings_resize_error_body`/`_no_mb`), never a generic failure toast,
 * per AGENTS.md §5 "never fake a signal in the UI". The previously-active setting is still in
 * force; this dialog only informs, it does not offer a retry, since the underlying condition
 * (transient heap pressure) may not have changed.
 */
@Composable
private fun ResizeErrorDialog(errorInfo: ResizeErrorInfo, onAcknowledge: () -> Unit) {
    val message = errorInfo.requestedMb?.let { requestedMb ->
        stringResource(R.string.settings_resize_error_body, errorInfo.requestedMinutes, requestedMb)
    } ?: stringResource(R.string.settings_resize_error_body_no_mb, errorInfo.requestedMinutes)
    AlertDialog(
        onDismissRequest = onAcknowledge,
        shape = CARD_SHAPE,
        containerColor = CockpitPanel,
        tonalElevation = 6.dp,
        title = {
            Text(
                text = stringResource(R.string.settings_resize_error_title),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = WarningRed,
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge) {
                Text(
                    text = stringResource(R.string.settings_resize_error_dismiss),
                    fontWeight = FontWeight.Bold,
                    color = FlightOrange,
                )
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
    selectedPreset: QualityPreset = QualityPreset.VOICE,
): SettingsUiState = SettingsUiState(
    retentionStepper = RetentionStepperUiState(
        committedMinutes = committedMinutes,
        pendingMinutes = pendingMinutes,
        approxPendingRamMb = pendingMinutes * 2,
        isDirty = committedMinutes != pendingMinutes,
        canDecrement = canDecrement,
        canIncrement = canIncrement,
    ),
    // Illustrative per-preset ceilings for a hypothetical device (issue #298: there is no
    // AudioConfig constant these could reference any more -- DeviceMemoryBudget computes a real
    // one per device/preset at runtime instead). Only the relative order (VOICE >= BALANCED >=
    // HIGH_FIDELITY, matching each preset's byte rate) matters for what this preview demonstrates.
    qualityPresets = listOf(
        QualityPresetOption(QualityPreset.VOICE, maxRetentionMinutes = 90, isSelected = selectedPreset == QualityPreset.VOICE),
        QualityPresetOption(QualityPreset.BALANCED, maxRetentionMinutes = 45, isSelected = selectedPreset == QualityPreset.BALANCED),
        QualityPresetOption(QualityPreset.HIGH_FIDELITY, maxRetentionMinutes = 20, isSelected = selectedPreset == QualityPreset.HIGH_FIDELITY),
    ),
    selectedPreset = selectedPreset,
)

@Preview(showBackground = true, name = "Clean (30 min)")
@Composable
private fun SettingsScreenCleanPreview() {
    AudioBlackboxTheme {
        SettingsScreen(previewStepperState(), {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "Dirty (30 -> 45 min)")
@Composable
private fun SettingsScreenDirtyPreview() {
    AudioBlackboxTheme {
        SettingsScreen(previewStepperState(pendingMinutes = 45), {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "At minimum (5 min)")
@Composable
private fun SettingsScreenAtMinPreview() {
    AudioBlackboxTheme {
        SettingsScreen(previewStepperState(committedMinutes = 5, pendingMinutes = 5, canDecrement = false), {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "At maximum (45 min)")
@Composable
private fun SettingsScreenAtMaxPreview() {
    AudioBlackboxTheme {
        SettingsScreen(previewStepperState(committedMinutes = 45, pendingMinutes = 45, canIncrement = false), {}, {}, {}, {})
    }
}
