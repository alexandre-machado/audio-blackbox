package cc.machado.audioblackbox.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import cc.machado.audioblackbox.ui.theme.AudioBlackboxTheme

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
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsHeader()
        RetentionStepperSection(uiState.retentionStepper, onDecrement, onIncrement, onApply)
        AudioSpecsSection()
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings_gear),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.settings_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ram_memory),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
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
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.settings_retention_apply_button))
            }
        }
    }
}

@Composable
private fun AudioSpecsSection() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
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
                    tint = MaterialTheme.colorScheme.primary,
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
            SpecRow(label = stringResource(R.string.settings_specs_sample_rate_label), value = stringResource(R.string.settings_specs_sample_rate_value), isMonospace = true)
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            SpecRow(label = stringResource(R.string.settings_specs_channels_label), value = stringResource(R.string.settings_specs_channels_value))
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
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun PrivacySection(
    versionName: String = BuildConfig.VERSION_NAME,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
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
                    tint = MaterialTheme.colorScheme.primary,
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
)

@Preview(showBackground = true, name = "Clean (30 min)")
@Composable
private fun SettingsScreenCleanPreview() {
    AudioBlackboxTheme {
        SettingsScreen(previewStepperState(), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "Dirty (30 -> 45 min)")
@Composable
private fun SettingsScreenDirtyPreview() {
    AudioBlackboxTheme {
        SettingsScreen(previewStepperState(pendingMinutes = 45), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "At minimum (5 min)")
@Composable
private fun SettingsScreenAtMinPreview() {
    AudioBlackboxTheme {
        SettingsScreen(previewStepperState(committedMinutes = 5, pendingMinutes = 5, canDecrement = false), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "At maximum (45 min)")
@Composable
private fun SettingsScreenAtMaxPreview() {
    AudioBlackboxTheme {
        SettingsScreen(previewStepperState(committedMinutes = 45, pendingMinutes = 45, canIncrement = false), {}, {}, {}, {}, {}, {})
    }
}
