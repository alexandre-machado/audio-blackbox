package cc.machado.audioblackbox.ui.gallery

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.ui.ScreenHeader
import cc.machado.audioblackbox.ui.theme.AudioBlackboxTheme
import cc.machado.audioblackbox.ui.theme.AvionicsCard
import cc.machado.audioblackbox.ui.theme.AvionicsCardHeaderBar
import cc.machado.audioblackbox.ui.theme.AvionicsTag
import cc.machado.audioblackbox.ui.theme.CARD_INNER_PADDING
import cc.machado.audioblackbox.ui.theme.CARD_SHAPE
import cc.machado.audioblackbox.ui.theme.CockpitBorder
import cc.machado.audioblackbox.ui.theme.CockpitBorderStrong
import cc.machado.audioblackbox.ui.theme.CockpitPanel
import cc.machado.audioblackbox.ui.theme.CockpitSlate
import cc.machado.audioblackbox.ui.theme.DashedDivider
import cc.machado.audioblackbox.ui.theme.FlightOrange
import cc.machado.audioblackbox.ui.theme.FlightOrangeContainer
import cc.machado.audioblackbox.ui.theme.RADIUS_RIVET
import cc.machado.audioblackbox.ui.theme.RADIUS_SM
import cc.machado.audioblackbox.ui.theme.SCREEN_GUTTER
import cc.machado.audioblackbox.ui.theme.SECTION_SPACING
import cc.machado.audioblackbox.ui.theme.TextMuted
import cc.machado.audioblackbox.ui.theme.WarningRed

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

/**
 * Does not host its own [androidx.compose.material3.Scaffold] (issue #221, closing a gap left open
 * by #73/#78): like [cc.machado.audioblackbox.ui.dashboard.DashboardScreen] and
 * [cc.machado.audioblackbox.ui.settings.SettingsScreen], this is one of the destinations switched
 * by the floating bottom bar in [cc.machado.audioblackbox.ui.MainActivity]. That single outer
 * `Scaffold`'s `innerPadding` is applied once, above this screen, by
 * [cc.machado.audioblackbox.ui.AppScaffold] -- this screen does not take a `contentPadding`
 * parameter for that, and does not apply it itself anywhere below.
 */
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(SCREEN_GUTTER),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
    ) {
        GalleryHeader()
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                uiState.isLoading -> LoadingState()
                uiState.items.isEmpty() -> EmptyState()
                else -> RecordingList(
                    items = uiState.items,
                    onPlayPauseClicked = onPlayPauseClicked,
                    onSeek = onSeek,
                    onShareClicked = onShareClicked,
                    onDeleteRequested = onDeleteRequested,
                )
            }
        }
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

@Composable
private fun GalleryHeader() {
    ScreenHeader(
        icon = painterResource(R.drawable.ic_gallery_folder),
        title = stringResource(R.string.gallery_title),
        subtitle = stringResource(R.string.gallery_subtitle),
    )
}

@Composable
private fun LoadingState() {
    val loadingLabel = stringResource(R.string.gallery_loading)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = loadingLabel },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.clearAndSetSemantics {},
            color = FlightOrange,
        )
        Text(
            text = loadingLabel,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = TextMuted,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun EmptyState() {
    AvionicsCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AvionicsCardHeaderBar(
                label = stringResource(R.string.gallery_empty_card_label),
            )
            Surface(
                shape = RoundedCornerShape(RADIUS_SM),
                color = FlightOrangeContainer,
                border = BorderStroke(1.dp, FlightOrange.copy(alpha = 0.4f)),
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_gallery_folder),
                        contentDescription = null,
                        tint = FlightOrange,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.gallery_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = stringResource(R.string.gallery_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RecordingList(
    items: List<RecordingListItem>,
    onPlayPauseClicked: (RecordingItem) -> Unit,
    onSeek: (Long) -> Unit,
    onShareClicked: (RecordingItem) -> Unit,
    onDeleteRequested: (RecordingItem) -> Unit,
) {
    var expandedUri by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
    ) {
        items(items, key = { it.recording.uri.toString() }) { item ->
            val uriStr = item.recording.uri.toString()
            val isExpanded = expandedUri == uriStr
            RecordingCard(
                item = item,
                isExpanded = isExpanded,
                onToggleExpand = {
                    expandedUri = if (isExpanded) null else uriStr
                },
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
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onPlayPauseClicked: () -> Unit,
    onSeek: (Long) -> Unit,
    onShareClicked: () -> Unit,
    onDeleteClicked: () -> Unit,
) {
    val recording = item.recording
    val durationSizeLabel = stringResource(
        R.string.gallery_item_duration_size,
        formatDurationClock(recording.durationMillis),
        formatFileSize(recording.sizeBytes),
    )
    val isPlaying = item.playback is ItemPlaybackState.Playing
    val playPauseCd = stringResource(
        if (isPlaying) R.string.gallery_pause_cd else R.string.gallery_play_cd,
        recording.displayName,
    )
    val shareCd = stringResource(R.string.gallery_share_cd, recording.displayName)
    val deleteCd = stringResource(R.string.gallery_delete_cd, recording.displayName)

    AvionicsCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AvionicsCardHeaderBar(
                label = stringResource(R.string.gallery_card_label),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = recording.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = durationSizeLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = FlightOrange,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledIconButton(
                        onClick = onPlayPauseClicked,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier
                            .size(38.dp)
                            .semantics { contentDescription = playPauseCd },
                    ) {
                        if (isPlaying) {
                            Icon(
                                painter = painterResource(R.drawable.ic_pause),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = onShareClicked,
                        modifier = Modifier
                            .size(38.dp)
                            .semantics { contentDescription = shareCd },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = onDeleteClicked,
                        modifier = Modifier
                            .size(38.dp)
                            .semantics { contentDescription = deleteCd },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

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

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DashedDivider()
                    Slider(
                        value = positionMillis.toFloat().coerceIn(0f, sliderMax),
                        onValueChange = { onSeek(it.toLong()) },
                        valueRange = 0f..sliderMax,
                        colors = SliderDefaults.colors(
                            thumbColor = FlightOrange,
                            activeTrackColor = FlightOrange,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = seekCd },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatDurationClock(positionMillis),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = FlightOrange,
                        )
                        Text(
                            text = formatDurationClock(durationMillis),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (isExpanded) {
                RecordingSpecificationsCard(recording = recording)
            }
        }
    }
}

@Composable
private fun RecordingSpecificationsCard(recording: RecordingItem) {
    val formatLabel = if (recording.mimeType.contains("wav", ignoreCase = true) || recording.displayName.endsWith(".wav", ignoreCase = true)) {
        "WAV (.wav)"
    } else {
        "AAC (.m4a)"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        shape = RoundedCornerShape(RADIUS_SM),
        color = CockpitSlate,
        border = BorderStroke(1.dp, CockpitBorder),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.gallery_details_title),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = FlightOrange,
            )
            DetailRow(
                label = stringResource(R.string.gallery_details_created_at),
                value = formatFullDateTime(recording.capturedAtMillis),
            )
            if (recording.savedAtMillis > 0L) {
                DetailRow(
                    label = stringResource(R.string.gallery_details_saved_at),
                    value = formatFullDateTime(recording.savedAtMillis),
                )
            }
            DetailRow(
                label = stringResource(R.string.gallery_details_duration),
                value = formatDurationClock(recording.durationMillis),
            )
            DetailRow(
                label = stringResource(R.string.gallery_details_size),
                value = formatFileSize(recording.sizeBytes),
            )
            DetailRow(
                label = stringResource(R.string.gallery_details_format),
                value = formatLabel,
            )
            DetailRow(
                label = stringResource(R.string.gallery_details_quality),
                value = inferAudioQuality(recording),
            )
            DetailRow(
                label = stringResource(R.string.gallery_details_storage),
                value = stringResource(R.string.gallery_details_storage_value),
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
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
            fontFamily = FontFamily.Monospace,
        )
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
        shape = CARD_SHAPE,
        containerColor = CockpitPanel,
        tonalElevation = 6.dp,
        title = {
            Text(
                text = stringResource(R.string.gallery_delete_confirm_title),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.gallery_delete_confirm_body),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.gallery_delete_confirm_action),
                    fontWeight = FontWeight.Bold,
                    color = WarningRed,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.gallery_delete_cancel_action),
                    color = TextMuted,
                )
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
        shape = CARD_SHAPE,
        containerColor = CockpitPanel,
        tonalElevation = 6.dp,
        title = {
            Text(
                text = stringResource(R.string.gallery_delete_error_title),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = WarningRed,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.gallery_delete_error_body),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.gallery_delete_error_dismiss),
                    fontWeight = FontWeight.Bold,
                    color = FlightOrange,
                )
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

@Preview(showBackground = true, name = "With expanded specifications")
@Composable
private fun GalleryScreenExpandedPreview() {
    val recording = RecordingItem(
        uri = "content://media/external/audio/media/1".toUri(),
        displayName = "blackbox_2026-08-30_14-30-00_10min.m4a",
        mimeType = "audio/mp4",
        sizeBytes = 4_800_000L,
        durationMillis = 600_000L,
        capturedAtMillis = 1_788_000_000_000L,
        savedAtMillis = 1_788_000_600_000L,
    )
    AudioBlackboxTheme {
        RecordingSpecificationsCard(recording = recording)
    }
}
