package cc.machado.audioblackbox.ui.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.audio.CaptureErrorReason
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.export.ExportFailureReason
import cc.machado.audioblackbox.ui.theme.AudioBlackboxTheme

/**
 * Hosts [DashboardViewModel] and renders [DashboardScreen] against its live state. The seam
 * between this and [DashboardScreen] exists so every visual state is a plain, previewable
 * function of a [DashboardUiState] value -- see the `@Preview`s below -- without needing a real
 * [cc.machado.audioblackbox.service.RecorderService] running.
 */
@Composable
fun DashboardRoute(
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardScreen(
        uiState = uiState,
        onToggleEngine = viewModel::toggleEngine,
        onSave = viewModel::requestSave,
        onDismissSaveNotice = viewModel::dismissSaveNotice,
        onStartForwardRecording = { viewModel.startForwardRecording(false) },
        onStopForwardRecording = viewModel::stopForwardRecording,
        onDismissForwardNotice = viewModel::dismissForwardRecordingNotice,
        modifier = modifier,
    )
}

@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onToggleEngine: () -> Unit,
    onSave: () -> Unit,
    onDismissSaveNotice: () -> Unit,
    onStartForwardRecording: () -> Unit,
    onStopForwardRecording: () -> Unit,
    onDismissForwardNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(DASHBOARD_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DASHBOARD_PADDING),
    ) {
        StatusSection(uiState.captureStatus)
        BufferSection(uiState)
        EngineToggle(uiState.engineSwitch, onToggleEngine)
        SaveSection(uiState, onSave)
        ForwardRecordingSection(
            forwardState = uiState.forwardRecordingState,
            onStart = onStartForwardRecording,
            onStop = onStopForwardRecording,
        )
        if (uiState.saveState != SaveUiState.Idle) {
            SaveOutcomeNotice(uiState.saveState, onDismissSaveNotice)
        }
        if (uiState.forwardRecordingState is ForwardRecordingUiState.Success ||
            uiState.forwardRecordingState is ForwardRecordingUiState.Error
        ) {
            ForwardOutcomeNotice(uiState.forwardRecordingState, onDismissForwardNotice)
        }
    }
}

@Composable
private fun StatusSection(status: CaptureStatus) {
    val (labelRes, explanationRes) = when (status) {
        is CaptureStatus.Idle -> R.string.dashboard_status_idle to R.string.dashboard_idle_explanation
        is CaptureStatus.Recording -> R.string.dashboard_status_recording to R.string.dashboard_recording_explanation
        is CaptureStatus.Paused -> R.string.dashboard_status_paused to R.string.dashboard_paused_explanation
        is CaptureStatus.Error -> R.string.dashboard_status_error to status.reason.toUserMessageRes()
    }
    val label = stringResource(labelRes)
    val explanation = stringResource(explanationRes)
    val announcement = stringResource(R.string.dashboard_status_announcement, label)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = announcement
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (status is CaptureStatus.Recording) {
                RecordingPulse()
            } else {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(statusColor(status), CircleShape),
                )
            }
            Text(text = label, style = MaterialTheme.typography.headlineMedium)
        }
        if (explanation.isNotEmpty()) {
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (status is CaptureStatus.Error && status.message.isNotBlank()) {
            Text(
                text = status.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RecordingPulse() {
    val transition = rememberInfiniteTransition(label = "recording-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recording-pulse-alpha",
    )
    Box(
        modifier = Modifier
            .size(16.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .clearAndSetSemantics {},
    )
}

private fun statusColor(status: CaptureStatus): Color = when (status) {
    is CaptureStatus.Idle -> Color.Gray
    is CaptureStatus.Paused -> Color(0xFFF9A825)
    is CaptureStatus.Error -> Color(0xFFB3261E)
    is CaptureStatus.Recording -> Color.Unspecified
}

@Composable
private fun BufferSection(uiState: DashboardUiState) {
    val bufferedLabel = formatMillisAsClock(uiState.bufferedMillis)
    val capacityLabel = formatMillisAsClock(uiState.capacityMillis)
    val statusText = stringResource(R.string.dashboard_buffer_status, bufferedLabel, capacityLabel)
    val progressCd = stringResource(R.string.dashboard_buffer_status_cd, bufferedLabel, capacityLabel)
    val fraction = if (uiState.capacityMillis > 0) {
        (uiState.bufferedMillis.toFloat() / uiState.capacityMillis.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = progressCd },
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (uiState.isBufferFull) {
            Text(
                text = stringResource(R.string.dashboard_buffer_full_notice),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun EngineToggle(engineSwitch: EngineSwitchUiState, onToggleEngine: () -> Unit) {
    val stateTextRes = when {
        engineSwitch.pending && engineSwitch.checked -> R.string.dashboard_engine_switch_state_stopping
        engineSwitch.pending && !engineSwitch.checked -> R.string.dashboard_engine_switch_state_starting
        engineSwitch.error != null -> R.string.dashboard_engine_switch_state_error
        engineSwitch.paused -> R.string.dashboard_engine_switch_state_paused
        engineSwitch.checked -> R.string.dashboard_engine_switch_state_on
        else -> R.string.dashboard_engine_switch_state_off
    }
    val label = stringResource(R.string.dashboard_engine_switch_label)
    val stateText = stringResource(stateTextRes)
    val announcement = stringResource(R.string.dashboard_engine_switch_announcement, stateText)
    val stateColor = when {
        engineSwitch.pending -> MaterialTheme.colorScheme.onSurfaceVariant
        engineSwitch.error != null -> MaterialTheme.colorScheme.error
        engineSwitch.paused -> Color(0xFFF9A825)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = announcement
            },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.titleMedium)
            Text(text = stateText, style = MaterialTheme.typography.bodyMedium, color = stateColor)
        }
        Switch(
            checked = engineSwitch.checked,
            onCheckedChange = { onToggleEngine() },
            enabled = engineSwitch.enabled,
            modifier = Modifier.testTag(ENGINE_SWITCH_TEST_TAG),
        )
    }
}

/**
 * Issue #121: one save action, the whole buffer -- the 5/15/30-minute chip row that used to sit
 * here is gone. The button is disabled only when [DashboardUiState.bufferedMillis] is genuinely
 * zero (nothing recorded yet); otherwise it always saves everything currently buffered, and both
 * the button's content description and the notice below it name the *real* buffered duration --
 * never the configured capacity -- so a partially-filled buffer is never oversold as more than it
 * actually holds.
 */
@Composable
private fun SaveSection(uiState: DashboardUiState, onSave: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = stringResource(R.string.dashboard_save_title), style = MaterialTheme.typography.titleMedium)
            val canSave = uiState.bufferedMillis > 0L
            val bufferedLabel = formatMillisAsClock(uiState.bufferedMillis)
            val saveButtonCd = if (canSave) {
                stringResource(R.string.dashboard_save_button_cd, bufferedLabel)
            } else {
                stringResource(R.string.dashboard_save_disabled_no_audio)
            }
            Button(
                onClick = onSave,
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = saveButtonCd },
            ) {
                Text(text = stringResource(R.string.dashboard_save_button))
            }
            when {
                !canSave -> Text(
                    text = stringResource(R.string.dashboard_save_disabled_no_audio),
                    style = MaterialTheme.typography.bodySmall,
                )
                !uiState.isBufferFull -> Text(
                    text = stringResource(R.string.dashboard_save_partial_buffer_notice, bufferedLabel),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ForwardRecordingSection(
    forwardState: ForwardRecordingUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (forwardState) {
                is ForwardRecordingUiState.Recording -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RecordingPulse()
                        Text(
                            text = stringResource(R.string.dashboard_forward_recording_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    val elapsedFormatted = formatMillisAsClock(forwardState.elapsedMillis)
                    val elapsedText = stringResource(R.string.dashboard_forward_elapsed_label, elapsedFormatted)
                    val elapsedCd = stringResource(R.string.dashboard_forward_elapsed_cd, elapsedFormatted)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                                contentDescription = elapsedCd
                            },
                    ) {
                        Text(
                            text = elapsedText,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.testTag(FORWARD_ELAPSED_TEST_TAG),
                        )
                        Text(
                            text = forwardState.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    Button(
                        onClick = onStop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(FORWARD_STOP_BUTTON_TEST_TAG),
                    ) {
                        Text(text = stringResource(R.string.dashboard_forward_stop_button))
                    }
                }
                else -> {
                    Text(
                        text = stringResource(R.string.dashboard_forward_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.dashboard_forward_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(FORWARD_START_BUTTON_TEST_TAG),
                    ) {
                        Text(text = stringResource(R.string.dashboard_forward_start_button))
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveOutcomeNotice(saveState: SaveUiState, onDismiss: () -> Unit) {
    val titleRes: Int
    val body: String
    val dismissible: Boolean
    when (saveState) {
        SaveUiState.Idle -> return
        SaveUiState.Exporting -> {
            titleRes = R.string.dashboard_save_exporting_title
            body = stringResource(R.string.dashboard_save_exporting_body)
            dismissible = false
        }
        is SaveUiState.Success -> {
            titleRes = R.string.dashboard_save_success_title
            body = stringResource(R.string.dashboard_save_success_body, saveState.displayName)
            dismissible = true
        }
        is SaveUiState.Error -> {
            titleRes = R.string.dashboard_save_error_title
            body = saveState.message
            dismissible = true
        }
    }
    val title = stringResource(titleRes)
    val announcement = stringResource(R.string.dashboard_save_outcome_announcement, title, body)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = announcement
            },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            if (dismissible) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.dashboard_save_notice_dismiss))
                }
            }
        }
    }
}

@Composable
private fun ForwardOutcomeNotice(
    forwardState: ForwardRecordingUiState,
    onDismiss: () -> Unit,
) {
    val titleRes: Int
    val body: String
    when (forwardState) {
        is ForwardRecordingUiState.Success -> {
            titleRes = R.string.dashboard_forward_success_title
            body = stringResource(R.string.dashboard_forward_success_body, forwardState.displayName)
        }
        is ForwardRecordingUiState.Error -> {
            titleRes = R.string.dashboard_forward_error_title
            body = forwardState.message
        }
        else -> return
    }
    val title = stringResource(titleRes)
    val announcement = stringResource(R.string.dashboard_forward_outcome_announcement, title, body)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = announcement
            },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.dashboard_save_notice_dismiss))
            }
        }
    }
}

// ---- Previews ----

private fun previewState(
    status: CaptureStatus,
    bufferedMillis: Long = 0L,
    capacityMinutes: Int = 30,
    saveState: SaveUiState = SaveUiState.Idle,
    forwardRecordingState: ForwardRecordingUiState = ForwardRecordingUiState.Idle,
    enginePending: Boolean = false,
): DashboardUiState = DashboardViewModel.mapUiState(
    captureState = when (status) {
        is CaptureStatus.Idle -> CaptureState.Idle
        is CaptureStatus.Recording -> CaptureState.Recording
        is CaptureStatus.Paused -> CaptureState.Paused
        is CaptureStatus.Error -> CaptureState.Error(status.reason, status.message)
    },
    bufferedMillis = bufferedMillis,
    capacityMinutes = capacityMinutes,
    saveState = saveState,
    forwardRecordingState = forwardRecordingState,
    enginePending = enginePending,
)

@Preview(showBackground = true, name = "Idle")
@Composable
private fun DashboardScreenIdlePreview() {
    AudioBlackboxTheme {
        DashboardScreen(previewState(CaptureStatus.Idle), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "Recording")
@Composable
private fun DashboardScreenRecordingPreview() {
    AudioBlackboxTheme {
        DashboardScreen(previewState(CaptureStatus.Recording, bufferedMillis = 12 * 60_000L + 34_000L), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "Paused")
@Composable
private fun DashboardScreenPausedPreview() {
    AudioBlackboxTheme {
        DashboardScreen(previewState(CaptureStatus.Paused, bufferedMillis = 8 * 60_000L), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "Error - AudioRecord init failed")
@Composable
private fun DashboardScreenErrorPreview() {
    AudioBlackboxTheme {
        DashboardScreen(
            previewState(
                CaptureStatus.Error(CaptureErrorReason.AUDIO_RECORD_INIT_FAILED, "AudioRecord.state = 0"),
            ),
            {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, name = "Error - Buffer allocation failed")
@Composable
private fun DashboardScreenErrorBufferAllocationPreview() {
    AudioBlackboxTheme {
        DashboardScreen(
            previewState(
                CaptureStatus.Error(
                    CaptureErrorReason.BUFFER_ALLOCATION_FAILED,
                    "Failed to allocate 57600000-byte ring buffer: OutOfMemoryError",
                ),
            ),
            {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, name = "Engine switch starting (pending)")
@Composable
private fun DashboardScreenEngineStartingPreview() {
    AudioBlackboxTheme {
        DashboardScreen(previewState(CaptureStatus.Idle, enginePending = true), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "Engine switch stopping (pending)")
@Composable
private fun DashboardScreenEngineStoppingPreview() {
    AudioBlackboxTheme {
        DashboardScreen(
            previewState(CaptureStatus.Recording, bufferedMillis = 5 * 60_000L, enginePending = true),
            {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, name = "Buffer full")
@Composable
private fun DashboardScreenBufferFullPreview() {
    AudioBlackboxTheme {
        DashboardScreen(
            previewState(CaptureStatus.Recording, bufferedMillis = 30 * 60_000L),
            {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, name = "Forward recording active")
@Composable
private fun DashboardScreenForwardRecordingPreview() {
    AudioBlackboxTheme {
        DashboardScreen(
            previewState(
                CaptureStatus.Recording,
                bufferedMillis = 15 * 60_000L,
                forwardRecordingState = ForwardRecordingUiState.Recording(
                    displayName = "blackbox_2026-08-25_14-30-00_forward.m4a",
                    elapsedMillis = 74_000L,
                ),
            ),
            {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, name = "Save exporting")
@Composable
private fun DashboardScreenSaveExportingPreview() {
    AudioBlackboxTheme {
        DashboardScreen(
            previewState(
                CaptureStatus.Recording,
                bufferedMillis = 30 * 60_000L,
                saveState = SaveUiState.Exporting,
            ),
            {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, name = "Save success")
@Composable
private fun DashboardScreenSaveSuccessPreview() {
    AudioBlackboxTheme {
        DashboardScreen(
            previewState(
                CaptureStatus.Recording,
                bufferedMillis = 30 * 60_000L,
                saveState = SaveUiState.Success("blackbox_2026-08-21_10-15-00_30min.m4a"),
            ),
            {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, name = "Save error")
@Composable
private fun DashboardScreenSaveErrorPreview() {
    AudioBlackboxTheme {
        DashboardScreen(
            previewState(
                CaptureStatus.Recording,
                bufferedMillis = 30 * 60_000L,
                saveState = SaveUiState.Error(
                    ExportFailureReason.SINK_OPEN_FAILED,
                    "MediaStore insert rejected",
                ),
            ),
            {}, {}, {}, {}, {}, {},
        )
    }
}

/** Outer padding applied to the dashboard content column. */
val DASHBOARD_PADDING = 24.dp

/** Test tag for the continuous recording engine switch control. */
const val ENGINE_SWITCH_TEST_TAG = "dashboard_engine_switch"

/** Test tag for the start continuous recording button. */
const val FORWARD_START_BUTTON_TEST_TAG = "dashboard_forward_start_button"

/** Test tag for the stop continuous recording button. */
const val FORWARD_STOP_BUTTON_TEST_TAG = "dashboard_forward_stop_button"

/** Test tag for the forward recording elapsed clock display. */
const val FORWARD_ELAPSED_TEST_TAG = "dashboard_forward_elapsed"
