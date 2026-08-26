package cc.machado.audioblackbox.ui.gallery

import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cc.machado.audioblackbox.export.MediaStoreSink
import cc.machado.audioblackbox.export.RecordingRow
import cc.machado.audioblackbox.export.RecordingsRepository
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sources [GalleryUiState] from [RecordingsRepository] (`MediaStore` itself, via [MediaStoreSink]
 * -- issue #7) and a [RecordingPlayer], never from an app-local database: [refresh] always re-runs
 * the real query, so a file deleted outside the app disappears from [uiState] on the next refresh
 * with no phantom entry surviving anywhere in this class.
 */
class GalleryViewModel(
    private val repository: RecordingsRepository,
    private val player: RecordingPlayer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val tickMillis: Long = DEFAULT_TICK_MILLIS,
) : ViewModel() {

    // null means "the first query hasn't returned yet" -- see GalleryUiState.isLoading's doc.
    private val _recordings = MutableStateFlow<List<RecordingItem>?>(null)
    private val _pendingDelete = MutableStateFlow<RecordingItem?>(null)
    private val _deleteError = MutableStateFlow<RecordingItem?>(null)

    // Polled rather than observed for the same reason DashboardViewModel polls buffered duration:
    // MediaPlayer.getCurrentPosition() is a plain getter, not itself observable, and changes
    // continuously while playing without any discrete transition to react to. Costs nothing while
    // uiState has no subscriber, governed by the same WhileSubscribed below.
    private val positionTickFlow: Flow<Long> = flow {
        while (true) {
            emit(player.currentPositionMillis())
            delay(tickMillis)
        }
    }

    val uiState: StateFlow<GalleryUiState> = combine(
        _recordings,
        player.playback,
        positionTickFlow,
        _pendingDelete,
        _deleteError,
    ) { recordings, playback, position, pendingDelete, deleteError ->
        buildUiState(recordings, playback, position, pendingDelete, deleteError)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = buildUiState(
            _recordings.value,
            player.playback.value,
            0L,
            _pendingDelete.value,
            _deleteError.value,
        ),
    )

    init {
        refresh()
    }

    /** Re-runs the real `MediaStore` query. Called on init and can be re-invoked (e.g. pull to
     * refresh, or after a delete) so the list never drifts from what is actually on disk. */
    fun refresh() {
        viewModelScope.launch {
            val rows = withContext(ioDispatcher) { repository.queryRecordings() }
            _recordings.value = mapRowsToItems(rows)
        }
    }

    /** Toggles playback of [recording]: starts it if nothing or a different item is loaded, pauses
     * it if it is the one currently playing, resumes it if it is the one currently paused. Never
     * has to itself stop a different item first -- [RecordingPlayer.play] already guarantees that
     * (see its doc), which is also what keeps [buildUiState] able to derive every item's playback
     * state from one shared value without any item-local bookkeeping. */
    fun onPlayPauseClicked(recording: RecordingItem) {
        when (val current = player.playback.value) {
            is PlaybackState.Playing -> if (current.uri == recording.uri) player.pause() else player.play(recording.uri, recording.mimeType)
            is PlaybackState.Paused -> if (current.uri == recording.uri) player.resume() else player.play(recording.uri, recording.mimeType)
            PlaybackState.Idle -> player.play(recording.uri, recording.mimeType)
        }
    }

    fun onSeek(positionMillis: Long) {
        player.seekTo(positionMillis)
    }

    fun onDeleteRequested(recording: RecordingItem) {
        _pendingDelete.value = recording
    }

    fun onDeleteCancelled() {
        _pendingDelete.value = null
    }

    /** Dismisses a previously surfaced delete failure (see [onDeleteConfirmed]'s doc). No-op if
     * nothing is currently showing. */
    fun onDeleteErrorDismissed() {
        _deleteError.value = null
    }

    /** Deletes the row [onDeleteRequested] chose. If that item is the one currently loaded in
     * [player] (playing or paused), stops it first -- deleting the `MediaStore` row out from under
     * a live [android.media.MediaPlayer] is not something to rely on the platform handling
     * gracefully. Always [refresh]es afterward so the list reflects the real, current `MediaStore`
     * state rather than this class's own guess of "one row removed" -- in particular, if
     * [RecordingsRepository.delete] failed (most likely because this app does not own that row --
     * see [MediaStoreSink.delete]'s doc, issue #59), the row is simply still there after
     * [refresh], never optimistically dropped out from under a file that is still on disk.
     * [_deleteError] surfaces that failure as a real, dismissible, visible state rather than a
     * silent no-op (issue #29's rule, PR #61 review finding). */
    fun onDeleteConfirmed() {
        val target = _pendingDelete.value ?: return
        _pendingDelete.value = null
        _deleteError.value = null
        viewModelScope.launch {
            val playing = player.playback.value
            val targetIsLoaded = (playing as? PlaybackState.Playing)?.uri == target.uri ||
                (playing as? PlaybackState.Paused)?.uri == target.uri
            if (targetIsLoaded) player.stop()
            // RecordingsRepository.delete is documented never to throw (MediaStoreSink itself
            // already catches SecurityException), but this call is still wrapped rather than
            // trusted blindly: an uncaught exception here would crash this launch{} entirely,
            // silently skipping both the error surfaced below and the refresh() that keeps the
            // list honest either way (PR #61 review finding -- delete failures must never be a
            // silent no-op, whether they come back as `false` or as an unexpected throw).
            val deleted = try {
                withContext(ioDispatcher) { repository.delete(target.uri) }
            } catch (e: Exception) {
                false
            }
            if (!deleted) {
                _deleteError.value = target
            }
            refresh()
        }
    }

    override fun onCleared() {
        player.release()
    }

    companion object {
        private const val DEFAULT_TICK_MILLIS = 250L
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val FILENAME_TIMESTAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss"

        // Matches ExportEngine.filenameFor's own pattern -- see RecordingItem.capturedAtMillis's
        // doc for why the filename, not MediaStore's DATE_ADDED, is preferred when it parses.
        // `\d+(?:min|s)` (not just `\d+min`) since issue #129's follow-up: a sub-minute save now
        // names its file `..._45s.m4a` instead of the misleadingly-useless `..._0min.m4a` (see
        // ExportEngine.filenameFor's secondsLabel doc) -- this regex only anchors on the suffix
        // shape to find the timestamp group, so both forms must keep matching or every sub-minute
        // recording would silently fall back to DATE_ADDED sorting instead of its real capture time.
        private val FILENAME_REGEX = Regex("""^blackbox_(\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})_\d+(?:min|s)\.\w+$""")

        /** Pure oracle for "does this filename carry a capture timestamp, and if so, what is it"
         * -- `null` on any row whose name doesn't match [FILENAME_REGEX] or whose captured
         * timestamp fails to parse, so the caller can fall back to `DATE_ADDED`. */
        fun parseCapturedAtMillis(displayName: String): Long? {
            val match = FILENAME_REGEX.matchEntire(displayName) ?: return null
            val formatter = SimpleDateFormat(FILENAME_TIMESTAMP_PATTERN, Locale.US).apply { isLenient = false }
            return try {
                formatter.parse(match.groupValues[1])?.time
            } catch (e: ParseException) {
                null
            }
        }

        /** [RecordingRow] -> [RecordingItem], newest capture first. This is the oracle issue #7
         * requires: fed rows from all three locations and both `.m4a`/`.wav` extensions, it must
         * produce one correctly-sorted list -- see [GalleryViewModelTest]'s multi-location case. */
        fun mapRowsToItems(rows: List<RecordingRow>): List<RecordingItem> =
            rows.map { row ->
                RecordingItem(
                    uri = row.uri,
                    displayName = row.displayName,
                    mimeType = row.mimeType,
                    sizeBytes = row.sizeBytes,
                    durationMillis = row.durationMillis,
                    capturedAtMillis = parseCapturedAtMillis(row.displayName) ?: row.dateAddedMillis,
                )
            }.sortedByDescending { it.capturedAtMillis }

        /** The single state-mapping oracle: recordings + shared playback state + polled position
         * + a pending delete + a delete failure -> the exact [GalleryUiState] the screen renders.
         * Deliberately the only place that decides which single item (if any) is Playing/Paused --
         * see [ItemPlaybackState]'s doc for why this makes two items Playing at once structurally
         * impossible, not just a rule this function happens to follow. */
        fun buildUiState(
            recordings: List<RecordingItem>?,
            playback: PlaybackState,
            positionMillis: Long,
            pendingDelete: RecordingItem?,
            deleteError: RecordingItem? = null,
        ): GalleryUiState {
            val items = (recordings ?: emptyList()).map { recording ->
                val itemPlayback: ItemPlaybackState = when {
                    playback is PlaybackState.Playing && playback.uri == recording.uri ->
                        ItemPlaybackState.Playing(positionMillis, recording.durationMillis)
                    playback is PlaybackState.Paused && playback.uri == recording.uri ->
                        ItemPlaybackState.Paused(positionMillis, recording.durationMillis)
                    else -> ItemPlaybackState.Stopped
                }
                RecordingListItem(recording, itemPlayback)
            }
            return GalleryUiState(
                isLoading = recordings == null,
                items = items,
                pendingDelete = pendingDelete,
                deleteError = deleteError,
            )
        }

        /** Standard [ViewModelProvider.Factory] wiring for
         * [cc.machado.audioblackbox.ui.MainActivity]/[GalleryRoute]'s `viewModel(factory = ...)`
         * call -- this class's constructor parameters all have defaults for testability, which
         * defeats reflection-based construction, the same reason
         * [cc.machado.audioblackbox.ui.dashboard.DashboardViewModel] has its own explicit
         * `Factory`. Builds the real [MediaStoreSink]/[AndroidRecordingPlayer] from [context]'s
         * application context. */
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    val repository = MediaStoreSink(appContext)
                    val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val player = AndroidRecordingPlayer(appContext, audioManager)
                    GalleryViewModel(repository, player)
                }
            }
        }
    }
}
