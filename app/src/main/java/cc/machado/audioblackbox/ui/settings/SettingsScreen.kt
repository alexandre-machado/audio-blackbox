package cc.machado.audioblackbox.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        RetentionStepperSection(uiState.retentionStepper, onDecrement, onIncrement, onApply)
        AboutSection(versionName = versionName)
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
    // Locked while a discard confirmation is showing (mirrors SettingsViewModel's own guard in
    // incrementPending/decrementPending/commitPendingRetentionWindow) -- the controls are visibly
    // disabled here too, not just ignored, so the user is not left tapping a control that silently
    // does nothing.
    val locked = stepper.pendingConfirmationMinutes != null
    val valueLabel = stringResource(R.string.settings_retention_value, stepper.pendingMinutes, stepper.approxPendingRamMb)
    val decrementCd = stringResource(R.string.settings_retention_decrement_cd)
    val incrementCd = stringResource(R.string.settings_retention_increment_cd)
    // Re-announced to a screen reader on every pending-value change, same LiveRegion pattern
    // DashboardScreen's StatusSection/EngineToggle already use -- the announcement adds the
    // active/not-yet-applied state on top of the visible "%d min (~%d MB)" label, which is exactly
    // the kind of information issue #66 flagged as missing when a *_cd string merely repeats its
    // visible label.
    val stateAnnouncement = if (stepper.isDirty) {
        stringResource(R.string.settings_retention_value_pending_cd, stepper.pendingMinutes, stepper.approxPendingRamMb)
    } else {
        stringResource(R.string.settings_retention_value_active_cd, stepper.pendingMinutes, stepper.approxPendingRamMb)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = stringResource(R.string.settings_retention_title), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(R.string.settings_retention_explanation), style = MaterialTheme.typography.bodySmall)

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
                Text(text = valueLabel, style = MaterialTheme.typography.headlineSmall)
                IconButton(
                    onClick = onIncrement,
                    enabled = stepper.canIncrement && !locked,
                    modifier = Modifier.semantics { contentDescription = incrementCd },
                ) {
                    Text(text = "+", style = MaterialTheme.typography.headlineSmall)
                }
            }

            // The pending-vs-active distinction issue #73 requires stay visible, not just tracked
            // internally: shown only while the two actually differ.
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

/**
 * About section displaying the app version.
 */
@Composable
private fun AboutSection(
    modifier: Modifier = Modifier,
    versionName: String = BuildConfig.VERSION_NAME,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_about_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.settings_version_label, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/** Issue #84: shown at most once ever, when [SettingsUiState.clampNotice] is non-null -- a user
 * whose stored retention window (e.g. 60 min) was silently clamped down by issue #72's interim
 * safety clamp (to [ClampNotice.newMinutes], e.g. 45) gets exactly this one explanation of the old
 * value, the new value, and why. Dismissing it (the only action offered -- there is nothing to
 * confirm or cancel, just acknowledge) calls [onAcknowledge], which persists that it has been seen
 * so it never shows again. */
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

private fun previewState(
    pendingMinutes: Int = 30,
    committedMinutes: Int = 30,
    pendingConfirmationMinutes: Int? = null,
    clampNotice: ClampNotice? = null,
): SettingsUiState = SettingsViewModel.mapUiState(committedMinutes, pendingMinutes, pendingConfirmationMinutes, clampNotice)

@Preview(showBackground = true, name = "Default (30 min, not dirty)")
@Composable
private fun SettingsScreenDefaultPreview() {
    AudioBlackboxTheme {
        SettingsScreen(previewState(), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "Pending change (not yet applied)")
@Composable
private fun SettingsScreenPendingPreview() {
    AudioBlackboxTheme {
        SettingsScreen(previewState(pendingMinutes = 45, committedMinutes = 30), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "At minimum bound")
@Composable
private fun SettingsScreenMinBoundPreview() {
    AudioBlackboxTheme {
        SettingsScreen(previewState(pendingMinutes = 5, committedMinutes = 5), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "At maximum bound")
@Composable
private fun SettingsScreenMaxBoundPreview() {
    AudioBlackboxTheme {
        SettingsScreen(previewState(pendingMinutes = 60, committedMinutes = 60), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "Discard confirmation")
@Composable
private fun SettingsScreenConfirmPreview() {
    AudioBlackboxTheme {
        SettingsScreen(
            previewState(pendingMinutes = 60, committedMinutes = 30, pendingConfirmationMinutes = 60),
            {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, name = "Clamp-down notice (issue #84)")
@Composable
private fun SettingsScreenClampNoticePreview() {
    AudioBlackboxTheme {
        SettingsScreen(
            previewState(clampNotice = ClampNotice(previousMinutes = 60, newMinutes = 45)),
            {}, {}, {}, {}, {}, {},
        )
    }
}
