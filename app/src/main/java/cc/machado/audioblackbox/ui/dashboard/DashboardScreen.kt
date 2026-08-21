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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        onSelectWindow = viewModel::requestSave,
        onDismissSaveNotice = viewModel::dismissSaveNotice,
        onSelectRetentionWindow = viewModel::selectRetentionWindow,
        onConfirmRetentionWindowChange = viewModel::confirmRetentionWindowChange,
        onCancelRetentionWindowChange = viewModel::cancelRetentionWindowChange,
        modifier = modifier,
    )
}

@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onToggleEngine: () -> Unit,
    onSelectWindow: (Int) -> Unit,
    onDismissSaveNotice: () -> Unit,
    onSelectRetentionWindow: (Int) -> Unit,
    onConfirmRetentionWindowChange: () -> Unit,
    onCancelRetentionWindowChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            StatusSection(uiState.captureStatus)
            BufferSection(uiState)
            EngineToggle(uiState.captureStatus, onToggleEngine)
            SaveSection(uiState, onSelectWindow)
            if (uiState.saveState != SaveUiState.Idle) {
                SaveOutcomeNotice(uiState.saveState, onDismissSaveNotice)
            }
            RetentionSection(uiState.retentionSection, onSelectRetentionWindow)
        }
    }
    uiState.retentionSection.pendingConfirmationMinutes?.let { pendingMinutes ->
        RetentionDiscardDialog(
            pendingMinutes = pendingMinutes,
            onConfirm = onConfirmRetentionWindowChange,
            onCancel = onCancelRetentionWindowChange,
        )
    }
}

@Composable
private fun StatusSection(status: CaptureStatus) {
    val (labelRes, explanationRes) = when (status) {
        is CaptureStatus.Idle -> R.string.dashboard_status_idle to R.string.dashboard_idle_explanation
        is CaptureStatus.Recording -> R.string.dashboard_status_recording to R.string.dashboard_recording_explanation
        is CaptureStatus.Paused -> R.string.dashboard_status_paused to R.string.dashboard_paused_explanation
        is CaptureStatus.Error -> R.string.dashboard_status_error to null
    }
    val label = stringResource(labelRes)
    val explanation = when (status) {
        is CaptureStatus.Error -> stringResource(R.string.dashboard_error_explanation, status.reason.readable())
        else -> explanationRes?.let { stringResource(it) }.orEmpty()
    }
    val announcement = stringResource(R.string.dashboard_status_announcement, label)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                // Announces every capture-state transition to a screen reader, per issue #6's
                // "recording state is announced to screen readers" criterion.
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
        if (status is CaptureStatus.Error) {
            Text(
                text = status.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Live pulsing dot -- the "buffer is rolling" tell required by issue #6 -- decorative only; the
 * accessible name for the Recording state is already carried by [StatusSection]'s content
 * description, so this element does not need its own. */
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
        // Once full, the buffer keeps recording by overwriting its own oldest audio (see
        // AudioCaptureEngine/RingBuffer docs) -- this line is what makes that rolling-window
        // behavior legible instead of the indicator just looking permanently "stuck at max".
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
private fun EngineToggle(status: CaptureStatus, onToggleEngine: () -> Unit) {
    val isRunning = status is CaptureStatus.Recording || status is CaptureStatus.Paused
    // Error gets its own label ("Tentar novamente") rather than reusing "Iniciar motor" -- same
    // underlying action (toggleEngine() starts the engine whenever it isn't Recording/Paused),
    // but a distinct label makes the action's meaning after a failure explicit, per issue #6's
    // "Error state is visible and actionable" criterion.
    val label = stringResource(
        when {
            status is CaptureStatus.Error -> R.string.dashboard_retry_engine
            isRunning -> R.string.dashboard_stop_engine
            else -> R.string.dashboard_start_engine
        },
    )
    val contentDescriptionText = stringResource(
        if (isRunning) R.string.dashboard_stop_engine_cd else R.string.dashboard_start_engine_cd,
    )
    Button(
        onClick = onToggleEngine,
        modifier = Modifier.semantics { contentDescription = contentDescriptionText },
    ) {
        Text(text = label)
    }
}

@Composable
private fun SaveSection(uiState: DashboardUiState, onSelectWindow: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = stringResource(R.string.dashboard_save_title), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(R.string.dashboard_save_window_label), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                uiState.windowOptions.forEach { option -> WindowChip(option, onSelectWindow) }
            }
            // The largest enabled option, i.e. the most audio a single tap of this primary button
            // can save right now -- picking a specific shorter window is what the chips above are
            // for (each one saves immediately on tap once enabled).
            val enabledOption = uiState.windowOptions.lastOrNull { it.enabled }
            val saveButtonCd = enabledOption?.let {
                stringResource(R.string.dashboard_save_button_cd, it.minutes)
            } ?: stringResource(R.string.dashboard_save_disabled_no_audio)
            Button(
                onClick = { enabledOption?.let { onSelectWindow(it.minutes) } },
                enabled = enabledOption != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = saveButtonCd },
            ) {
                Text(text = stringResource(R.string.dashboard_save_button))
            }
            if (enabledOption == null) {
                Text(
                    text = stringResource(R.string.dashboard_save_disabled_no_audio),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun WindowChip(option: WindowOption, onSelectWindow: (Int) -> Unit) {
    val label = stringResource(R.string.dashboard_save_window_option, option.minutes)
    val cd = when (option.disabledReason) {
        WindowDisabledReason.INSUFFICIENT_BUFFER ->
            stringResource(R.string.dashboard_save_window_option_cd_insufficient_buffer, option.minutes, option.availableMinutes)
        null -> stringResource(R.string.dashboard_save_window_option_cd_enabled, option.minutes)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilterChip(
            // Not a persistent single-choice toggle -- every enabled chip saves immediately on
            // tap (issue #40 item 1: more than one window can be enabled at once once the buffer
            // holds enough audio), so `selected` never reflects "the chosen option", only whether
            // this chip is usable right now.
            selected = false,
            enabled = option.enabled,
            onClick = { onSelectWindow(option.minutes) },
            label = { Text(text = label) },
            colors = FilterChipDefaults.filterChipColors(),
            modifier = Modifier.semantics { contentDescription = cd },
        )
        if (option.disabledReason == WindowDisabledReason.INSUFFICIENT_BUFFER) {
            Text(
                text = stringResource(R.string.dashboard_save_window_insufficient_buffer, option.availableMinutes),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Renders the real save outcome (issue #40 item 2) -- Exporting/Success/Error, sourced from
 * [cc.machado.audioblackbox.export.ExportEngine]'s own [cc.machado.audioblackbox.export.ExportState]
 * via [DashboardViewModel], not a bare "intent sent" placeholder. Never called with
 * [SaveUiState.Idle] -- see [DashboardScreen]'s guard above this call site. Success/Error are
 * announced to screen readers the same way [StatusSection] announces capture-state transitions,
 * so a failed save is never silent even without looking at the screen. Success only names the
 * saved file -- opening it in the gallery/sharing it needs the gallery (issue #7) and is
 * deliberately not offered here yet. */
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

/** The retention-window selector (issue #45) -- how many minutes of audio the ring buffer is
 * *configured* to hold, distinct from [SaveSection]'s "salvar o passado" window (how much of
 * what's currently buffered a save writes to a file). Shows each bounded option's approximate RAM
 * cost per the issue's "the user is spending their device's memory" requirement -- see
 * [DashboardViewModel.computeRetentionSection] for the arithmetic that number comes from. */
@Composable
private fun RetentionSection(section: RetentionSectionUiState, onSelectRetentionWindow: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = stringResource(R.string.dashboard_retention_title), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(R.string.dashboard_retention_explanation), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                section.options.forEach { option -> RetentionChip(option, onSelectRetentionWindow) }
            }
        }
    }
}

@Composable
private fun RetentionChip(option: RetentionWindowOption, onSelectRetentionWindow: (Int) -> Unit) {
    val label = stringResource(R.string.dashboard_retention_option, option.minutes, option.approxRamMb)
    val cd = stringResource(R.string.dashboard_retention_option_cd, option.minutes, option.approxRamMb)
    FilterChip(
        selected = option.selected,
        enabled = !option.selected,
        onClick = { onSelectRetentionWindow(option.minutes) },
        label = { Text(text = label) },
        colors = FilterChipDefaults.filterChipColors(),
        modifier = Modifier.semantics { contentDescription = cd },
    )
}

/** Blocks a retention-window change from ever applying silently (issue #45's core safety
 * requirement): shown whenever [RetentionSectionUiState.pendingConfirmationMinutes] is non-null,
 * i.e. the user picked a different window while the engine was still Recording/Paused with real
 * audio buffered. Dismissing (tapping outside, or the back gesture) is treated the same as
 * [onCancel] -- there is no safe default other than "nothing changes". */
@Composable
private fun RetentionDiscardDialog(pendingMinutes: Int, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = stringResource(R.string.dashboard_retention_confirm_title)) },
        text = { Text(text = stringResource(R.string.dashboard_retention_confirm_body, pendingMinutes)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.dashboard_retention_confirm_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = stringResource(R.string.dashboard_retention_confirm_cancel))
            }
        },
    )
}

private fun CaptureErrorReason.readable(): String = name.lowercase().replace('_', ' ')

// ---- Previews: one per mandatory state (issue #6) ----

private fun previewState(
    status: CaptureStatus,
    bufferedMillis: Long = 0L,
    capacityMinutes: Int = 30,
    saveState: SaveUiState = SaveUiState.Idle,
    pendingRetentionConfirmationMinutes: Int? = null,
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
    pendingRetentionConfirmationMinutes = pendingRetentionConfirmationMinutes,
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

@Preview(showBackground = true, name = "Error")
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

@Preview(showBackground = true, name = "Retention discard confirmation")
@Composable
private fun DashboardScreenRetentionConfirmPreview() {
    AudioBlackboxTheme {
        DashboardScreen(
            previewState(
                CaptureStatus.Recording,
                bufferedMillis = 12 * 60_000L,
                pendingRetentionConfirmationMinutes = 60,
            ),
            {}, {}, {}, {}, {}, {},
        )
    }
}
