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
    val savedAtMillis: Long = 0L,
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

/** One row [GalleryScreen] renders: the static [recording] plus its current [playback] state.
 *
 * [isInProgress] (issue #375, `@rev` PR #377 review): true exactly while this row is the live
 * forward recording `RecorderService`'s own `forwardRecordingState` currently reports --
 * see [cc.machado.audioblackbox.ui.gallery.GalleryViewModel.applyRefreshAndInProgressState]'s doc
 * for the oracle. Issue #53's early commit means this row is visible in `MediaStore` (and, since
 * issue #375 Part A, in this list) from the instant recording starts, with a duration that reads
 * stale/zero and silently corrects itself one or more times before the session ends -- without
 * this flag that self-correction has no visible explanation, which is exactly what PR #377 review
 * flagged: "observable" (issue #375's own requirement) means the user can tell *why* the number is
 * moving, not just that it eventually settles. [GalleryScreen] renders this as a distinct
 * "Recording" badge (`AvionicsGreen`, matching this app's documented recording-state color, see
 * `AGENTS.md` 5's semantic colour-role rule) rather than changing how the duration itself is shown,
 * so a real, still-accruing number next to an explicit "still recording" label reads as exactly
 * what it is. */
data class RecordingListItem(
    val recording: RecordingItem,
    val playback: ItemPlaybackState,
    val isInProgress: Boolean = false,
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
    // Drives the pull-to-refresh indicator (issue #375 Part B). Distinct from [isLoading]: this is
    // true for *every* GalleryViewModel.refresh() call (the initial one, an automatic
    // change-observer-driven one, or a user pull), whereas [isLoading] only ever describes "the
    // first query has not returned yet" and stays false forever after that -- see [isLoading]'s own
    // doc. Reusing [isLoading] for the indicator would flash the full-screen loading state on top
    // of an already-visible list on every automatic refresh, which is exactly the flicker issue
    // #375 says to avoid.
    val isRefreshing: Boolean = false,
)
