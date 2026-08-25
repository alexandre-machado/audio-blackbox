package cc.machado.audioblackbox.ui.gallery

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.ui.theme.AudioBlackboxTheme
import androidx.compose.material3.Button

/**
 * Hosts [GalleryViewModel] and renders [GalleryScreen] against its live state -- the same
 * Route/Screen seam [cc.machado.audioblackbox.ui.dashboard.DashboardRoute] uses, so every visual
 * state stays a plain, previewable function of a [GalleryUiState] value.
 *
 * This is the entry point issue #40's deferred "path to the saved file" seam needs: the dashboard
 * (PR #57, issue #45) can navigate here after a successful save without this module needing to
 * know anything about the dashboard -- see this file's own doc note in the PR description for
 * exactly what to call.
 */
@Composable
fun GalleryRoute(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewModel: GalleryViewModel = viewModel(factory = GalleryViewModel.factory(context))
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refresh()
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    GalleryScreen(
        uiState = uiState,
        onPlayPauseClicked = viewModel::onPlayPauseClicked,
        onSeek = viewModel::onSeek,
        onShareClicked = { recording -> shareRecording(context, recording) },
        onDeleteRequested = viewModel::onDeleteRequested,
        onDeleteConfirmed = viewModel::onDeleteConfirmed,
        onDeleteCancelled = viewModel::onDeleteCancelled,
        onDeleteErrorDismissed = viewModel::onDeleteErrorDismissed,
        modifier = modifier,
    )
}

/** Opens the standard Android share sheet for [recording]'s own `MediaStore` uri, declared as
 * [RecordingItem.mimeType] read straight off that row (issue #7: a `.wav` shared as `audio/mp4`
 * fails to open in the receiving app) and with
 * [Intent.FLAG_GRANT_READ_URI_PERMISSION] set so the receiving app can actually read a
 * `content://` uri it does not otherwise have access to -- without this flag the share sheet opens
 * but the receiving app fails to load the attachment. */
private fun shareRecording(context: Context, recording: RecordingItem) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = recording.mimeType
        putExtra(Intent.EXTRA_STREAM, recording.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooserTitle = context.getString(R.string.gallery_share_chooser_title)
    context.startActivity(Intent.createChooser(sendIntent, chooserTitle))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    uiState: GalleryUiState,
    onPlayPauseClicked: (RecordingItem) -> Unit,
    onSeek: (Long) -> Unit,
    onShareClicked: (RecordingItem) -> Unit,
    onDeleteRequested: (RecordingItem) -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDeleteCancelled: () -> Unit,
    onDeleteErrorDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(text = stringResource(R.string.gallery_title)) }) },
    ) { innerPadding ->
        when {
            uiState.isLoading -> LoadingState(innerPadding)
            uiState.items.isEmpty() -> EmptyState(innerPadding)
            else -> RecordingList(
                items = uiState.items,
                innerPadding = innerPadding,
                onPlayPauseClicked = onPlayPauseClicked,
                onSeek = onSeek,
                onShareClicked = onShareClicked,
                onDeleteRequested = onDeleteRequested,
            )
        }

        val pendingDelete = uiState.pendingDelete
        if (pendingDelete != null) {
            DeleteConfirmationDialog(
                recording = pendingDelete,
                onConfirm = onDeleteConfirmed,
                onDismiss = onDeleteCancelled,
            )
        }

        if (uiState.deleteError != null) {
            DeleteErrorDialog(onDismiss = onDeleteErrorDismissed)
        }
    }
}

@Composable
private fun LoadingState(innerPadding: PaddingValues) {
    val loadingLabel = stringResource(R.string.gallery_loading)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .semantics { contentDescription = loadingLabel },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics {})
        Text(
            text = loadingLabel,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun EmptyState(innerPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = stringResource(R.string.gallery_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(R.string.gallery_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun RecordingList(
    items: List<RecordingListItem>,
    innerPadding: PaddingValues,
    onPlayPauseClicked: (RecordingItem) -> Unit,
    onSeek: (Long) -> Unit,
    onShareClicked: (RecordingItem) -> Unit,
    onDeleteRequested: (RecordingItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.recording.uri.toString() }) { item ->
            RecordingCard(
                item = item,
                onPlayPauseClicked = { onPlayPauseClicked(item.recording) },
                onSeek = onSeek,
                onShareClicked = { onShareClicked(item.recording) },
                onDeleteClicked = { onDeleteRequested(item.recording) },
            )
        }
    }
}

@Composable
private fun RecordingCard(
    item: RecordingListItem,
    onPlayPauseClicked: () -> Unit,
    onSeek: (Long) -> Unit,
    onShareClicked: () -> Unit,
    onDeleteClicked: () -> Unit,
) {
    val recording = item.recording
    val capturedAtLabel = formatCapturedAt(recording.capturedAtMillis)
    val durationSizeLabel = stringResource(
        R.string.gallery_item_duration_size,
        formatDurationClock(recording.durationMillis),
        formatFileSize(recording.sizeBytes),
    )
    val isPlaying = item.playback is ItemPlaybackState.Playing
    val playPauseLabel = stringResource(if (isPlaying) R.string.gallery_pause else R.string.gallery_play)
    val playPauseCd = stringResource(
        if (isPlaying) R.string.gallery_pause_cd else R.string.gallery_play_cd,
        capturedAtLabel,
    )
    val shareCd = stringResource(R.string.gallery_share_cd, capturedAtLabel)
    val deleteCd = stringResource(R.string.gallery_delete_cd, capturedAtLabel)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = capturedAtLabel, style = MaterialTheme.typography.titleMedium)
            Text(text = durationSizeLabel, style = MaterialTheme.typography.bodyMedium)

            val activePlayback = item.playback
            if (activePlayback is ItemPlaybackState.Playing || activePlayback is ItemPlaybackState.Paused) {
                val (positionMillis, durationMillis) = when (activePlayback) {
                    is ItemPlaybackState.Playing -> activePlayback.positionMillis to activePlayback.durationMillis
                    is ItemPlaybackState.Paused -> activePlayback.positionMillis to activePlayback.durationMillis
                    ItemPlaybackState.Stopped -> 0L to 0L
                }
                val elapsedTotalLabel = stringResource(
                    R.string.gallery_elapsed_total,
                    formatDurationClock(positionMillis),
                    formatDurationClock(durationMillis),
                )
                val seekCd = stringResource(R.string.gallery_seek_cd, elapsedTotalLabel)
                val sliderMax = durationMillis.coerceAtLeast(1L).toFloat()
                Slider(
                    value = positionMillis.toFloat().coerceIn(0f, sliderMax),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..sliderMax,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = seekCd },
                )
                Text(text = elapsedTotalLabel, style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPlayPauseClicked,
                    modifier = Modifier.semantics { contentDescription = playPauseCd },
                ) {
                    Text(text = playPauseLabel)
                }
                OutlinedButton(
                    onClick = onShareClicked,
                    modifier = Modifier.semantics { contentDescription = shareCd },
                ) {
                    Text(text = stringResource(R.string.gallery_share))
                }
                OutlinedButton(
                    onClick = onDeleteClicked,
                    modifier = Modifier.semantics { contentDescription = deleteCd },
                ) {
                    Text(text = stringResource(R.string.gallery_delete))
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirmationDialog(
    recording: RecordingItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.gallery_delete_confirm_title)) },
        text = { Text(text = stringResource(R.string.gallery_delete_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.gallery_delete_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.gallery_delete_cancel_action))
            }
        },
    )
}

/** Shown when [RecordingsRepository.delete][cc.machado.audioblackbox.export.RecordingsRepository.delete]
 * returns `false` -- most likely because this app no longer owns that `MediaStore` row (issue
 * #59). A real, visible failure rather than the row silently reappearing with no explanation
 * (issue #29's rule). */
@Composable
private fun DeleteErrorDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.gallery_delete_error_title)) },
        text = { Text(text = stringResource(R.string.gallery_delete_error_body)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.gallery_delete_error_dismiss))
            }
        },
    )
}

// ---- Previews ----

@Preview(showBackground = true, name = "Empty")
@Composable
private fun GalleryScreenEmptyPreview() {
    AudioBlackboxTheme {
        GalleryScreen(GalleryUiState(isLoading = false, items = emptyList()), {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "With recordings")
@Composable
private fun GalleryScreenListPreview() {
    val recording = RecordingItem(
        uri = "content://media/external/audio/media/1".toUri(),
        displayName = "blackbox_2026-08-21_11-39-07_5min.m4a",
        mimeType = "audio/mp4",
        sizeBytes = 2_420_830L,
        durationMillis = 300_160L,
        capturedAtMillis = 1_755_780_000_000L,
    )
    val playingRecording = RecordingItem(
        uri = "content://media/external/audio/media/2".toUri(),
        displayName = "blackbox_2026-08-20_09-00-00_15min.wav",
        mimeType = "audio/wav",
        sizeBytes = 158_000_000L,
        durationMillis = 900_000L,
        capturedAtMillis = 1_755_680_000_000L,
    )
    AudioBlackboxTheme {
        GalleryScreen(
            GalleryUiState(
                isLoading = false,
                items = listOf(
                    RecordingListItem(recording, ItemPlaybackState.Stopped),
                    RecordingListItem(playingRecording, ItemPlaybackState.Playing(120_000L, 900_000L)),
                ),
            ),
            {}, {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, name = "Delete confirmation")
@Composable
private fun GalleryScreenDeleteConfirmationPreview() {
    val recording = RecordingItem(
        uri = "content://media/external/audio/media/1".toUri(),
        displayName = "blackbox_2026-08-21_11-39-07_5min.m4a",
        mimeType = "audio/mp4",
        sizeBytes = 2_420_830L,
        durationMillis = 300_160L,
        capturedAtMillis = 1_755_780_000_000L,
    )
    AudioBlackboxTheme {
        GalleryScreen(
            GalleryUiState(
                isLoading = false,
                items = listOf(RecordingListItem(recording, ItemPlaybackState.Stopped)),
                pendingDelete = recording,
            ),
            {}, {}, {}, {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, name = "Delete error")
@Composable
private fun GalleryScreenDeleteErrorPreview() {
    val recording = RecordingItem(
        uri = "content://media/external/audio/media/1".toUri(),
        displayName = "blackbox_2025-01-05_23-10-00_30min.wav",
        mimeType = "audio/wav",
        sizeBytes = 158_000_000L,
        durationMillis = 1_800_000L,
        capturedAtMillis = 1_736_118_600_000L,
    )
    AudioBlackboxTheme {
        GalleryScreen(
            GalleryUiState(
                isLoading = false,
                items = listOf(RecordingListItem(recording, ItemPlaybackState.Stopped)),
                deleteError = recording,
            ),
            {}, {}, {}, {}, {}, {}, {},
        )
    }
}
