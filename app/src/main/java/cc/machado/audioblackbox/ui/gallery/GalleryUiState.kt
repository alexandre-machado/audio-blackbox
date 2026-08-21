package cc.machado.audioblackbox.ui.gallery

import android.net.Uri

/**
 * One exported recording as the gallery screen renders it -- built from a
 * [cc.machado.audioblackbox.export.RecordingRow] by [GalleryViewModel.mapRowsToItems].
 * [capturedAtMillis] prefers the timestamp encoded in the filename itself
 * (`blackbox_<yyyy-MM-dd_HH-mm-ss>_<window>min.<ext>`, unchanged since before issue #32/#33) over
 * `MediaStore`'s own `DATE_ADDED` -- the file's name already carries the exact moment capture
 * started, which is what "date/time of capture" means; falls back to `DATE_ADDED` only for a row
 * whose name doesn't match that pattern (a possible hand-renamed or foreign-origin file that still
 * happens to carry the `blackbox_` prefix).
 */
data class RecordingItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMillis: Long,
    val capturedAtMillis: Long,
)

/** Playback state of one [RecordingItem] as the list renders it -- derived, never stored
 * independently, from the single [PlaybackState] the whole screen shares (see
 * [GalleryViewModel.buildUiState]): at most one [RecordingListItem] across the whole list can ever
 * be [Playing] or [Paused] at once, because both are only produced for the one item whose `uri`
 * matches the shared [PlaybackState]'s `uri`. */
sealed interface ItemPlaybackState {
    data object Stopped : ItemPlaybackState
    data class Playing(val positionMillis: Long, val durationMillis: Long) : ItemPlaybackState
    data class Paused(val positionMillis: Long, val durationMillis: Long) : ItemPlaybackState
}

/** One row [GalleryScreen] renders: the static [recording] plus its current [playback] state. */
data class RecordingListItem(
    val recording: RecordingItem,
    val playback: ItemPlaybackState,
)

/**
 * Everything [GalleryScreen] needs to render one frame. [isLoading] is only ever true before the
 * first `MediaStore` query has returned -- once [items] is known (even if empty), it stays false,
 * so a manual [GalleryViewModel.refresh] never flashes the loading state back on top of an
 * already-visible list. The empty state ([items] empty and [isLoading] false) is a real, distinct,
 * renderable state, not inferred from an absent/null list.
 */
data class GalleryUiState(
    val isLoading: Boolean = true,
    val items: List<RecordingListItem> = emptyList(),
    val pendingDelete: RecordingItem? = null,
    // A delete that failed (e.g. RecordingsRepository.delete returned false because this app does
    // not own that MediaStore row -- see GalleryViewModel.onDeleteConfirmed) -- surfaced as a real,
    // visible error, never a silent no-op or an optimistic removal of a row still sitting on disk
    // (issue #29's rule, PR #61 review finding). Dismissed the same way pendingDelete is resolved.
    val deleteError: RecordingItem? = null,
)
