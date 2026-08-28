package cc.machado.audioblackbox.ui.gallery

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What is currently loaded into a [RecordingPlayer], if anything. Deliberately does not carry
 * position/duration -- those change continuously while [Playing] without a discrete transition to
 * react to, the same reason [cc.machado.audioblackbox.ui.dashboard.DashboardViewModel] polls its
 * buffered duration instead of observing it -- see [GalleryViewModel]'s own position ticker. */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Playing(val uri: Uri) : PlaybackState
    data class Paused(val uri: Uri) : PlaybackState
}

/**
 * Seam over [MediaPlayer] so [GalleryViewModel] is unit-testable without Android (issue #7),
 * mirroring how [cc.machado.audioblackbox.export.ExportSink] seams `MediaStore` out of
 * [cc.machado.audioblackbox.export.ExportEngine]. The production implementation
 * ([AndroidRecordingPlayer]) is solely responsible for the "only one item plays at a time"
 * invariant: it owns exactly one underlying player at a time, and [play] always tears down
 * whatever was previously loaded -- regardless of which uri -- before starting a new one.
 */
interface RecordingPlayer {
    val playback: StateFlow<PlaybackState>

    /** Starts playing [uri] (declared as [mimeType]), stopping and releasing whatever this player
     * was previously playing first. Requests audio focus; a denial leaves [playback] at
     * [PlaybackState.Idle] and nothing plays. */
    fun play(uri: Uri, mimeType: String)

    /** No-op unless [playback] is currently [PlaybackState.Playing]. */
    fun pause()

    /** No-op unless [playback] is currently [PlaybackState.Paused]. */
    fun resume()

    fun seekTo(positionMillis: Long)

    /** The currently loaded item's position, or 0 if nothing is loaded. */
    fun currentPositionMillis(): Long

    /** The currently loaded item's total duration, or 0 if nothing is loaded. */
    fun durationMillis(): Long

    /** Stops and releases whatever is loaded, if anything, and abandons audio focus. */
    fun stop()

    /** Releases all resources; must not be used again afterward. Called from
     * [GalleryViewModel.onCleared]. */
    fun release()
}

/**
 * [RecordingPlayer] backed by a real [MediaPlayer]. Requests audio focus with
 * [AudioAttributes.USAGE_MEDIA] on its own [AudioFocusRequest] registration -- playback arbitration
 * entirely separate from [cc.machado.audioblackbox.service.RecorderService]'s background microphone
 * capture (issue #3/#4, #154). Pausing this player on an incoming call therefore never touches,
 * and cannot be confused with, capture still running in the background -- issue #7's "must not
 * interfere with the recording service still capturing" requirement.
 *
 * [play]/[pause]/[resume]/[stop] are all synchronous from the caller's point of view but the
 * underlying player prepares asynchronously ([MediaPlayer.prepareAsync]): [playback] only
 * transitions to [PlaybackState.Playing] once [MediaPlayer.OnPreparedListener] actually fires, so a
 * slow-to-prepare file never reports itself as playing before it truly is.
 */
class AndroidRecordingPlayer(
    private val context: Context,
    private val audioManager: AudioManager,
    private val mediaPlayerFactory: () -> MediaPlayer = { MediaPlayer() },
    private val focusRequestFactory: (AudioManager.OnAudioFocusChangeListener) -> AudioFocusRequest = ::buildFocusRequest,
    // Extracted out of play() as its own seam (rather than a fresh AudioAttributes.Builder() call
    // inline) for the same reason RecorderService's own equivalent Builder call is never exercised
    // by a local unit test: android.jar's unit-test stub throws on every real Builder method
    // (`not mocked`) with no Robolectric in this project -- see this class's own test file.
    private val playbackAudioAttributesFactory: () -> AudioAttributes = ::buildPlaybackAudioAttributes,
) : RecordingPlayer {

    private var mediaPlayer: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    private val _playback = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { handleFocusChange(it) }

    override fun play(uri: Uri, mimeType: String) {
        teardown()
        if (!requestFocus()) return

        val player = mediaPlayerFactory()
        mediaPlayer = player
        player.setOnPreparedListener {
            it.start()
            _playback.value = PlaybackState.Playing(uri)
        }
        player.setOnCompletionListener {
            teardown()
        }
        player.setOnErrorListener { _, _, _ ->
            teardown()
            true
        }
        try {
            player.setAudioAttributes(playbackAudioAttributesFactory())
            player.setDataSource(context, uri)
            player.prepareAsync()
        } catch (e: IOException) {
            teardown()
        } catch (e: IllegalStateException) {
            teardown()
        }
    }

    override fun pause() {
        val current = playback.value
        if (current !is PlaybackState.Playing) return
        mediaPlayer?.pause()
        _playback.value = PlaybackState.Paused(current.uri)
    }

    override fun resume() {
        val current = playback.value
        if (current !is PlaybackState.Paused) return
        mediaPlayer?.start()
        _playback.value = PlaybackState.Playing(current.uri)
    }

    override fun seekTo(positionMillis: Long) {
        mediaPlayer?.seekTo(positionMillis.coerceAtLeast(0L).toInt())
    }

    override fun currentPositionMillis(): Long =
        if (playback.value == PlaybackState.Idle) 0L else (mediaPlayer?.currentPosition ?: 0).toLong()

    override fun durationMillis(): Long =
        if (playback.value == PlaybackState.Idle) 0L else (mediaPlayer?.duration ?: 0).toLong()

    override fun stop() {
        teardown()
    }

    override fun release() {
        teardown()
    }

    /** Reacts to an [AudioManager.OnAudioFocusChangeListener] callback -- exposed as its own
     * function (rather than only as a lambda passed to [focusRequestFactory]) so a unit test can
     * drive it directly without needing a real [AudioFocusRequest] to carry a retrievable listener
     * through. Loss (permanent or transient, e.g. an incoming call) pauses playback per issue #7's
     * "pauses on focus loss" requirement; this class never attempts to auto-resume on
     * [AudioManager.AUDIOFOCUS_GAIN] -- resuming playback the user did not ask to resume would be
     * its own surprise. */
    internal fun handleFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> pause()
        }
    }

    private fun requestFocus(): Boolean {
        val request = focusRequestFactory(focusListener)
        val result = audioManager.requestAudioFocus(request)
        return if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = request
            true
        } else {
            false
        }
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    /** Releases whatever is currently loaded (any state -- Idle/Playing/Paused) and abandons
     * focus. Safe to call when nothing is loaded. This is the single place that ever tears down
     * [mediaPlayer], which is what makes "starting playback of item B stops item A" true by
     * construction: [play] always calls this before installing a new player. */
    private fun teardown() {
        mediaPlayer?.let { player ->
            try {
                player.stop()
            } catch (e: IllegalStateException) {
                // Already stopped/never started far enough to be stoppable -- release() below is
                // still safe and is the part that actually matters.
            }
            player.release()
        }
        mediaPlayer = null
        abandonFocus()
        _playback.value = PlaybackState.Idle
    }

    private companion object {
        fun buildPlaybackAudioAttributes(): AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

        fun buildFocusRequest(listener: AudioManager.OnAudioFocusChangeListener): AudioFocusRequest =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(buildPlaybackAudioAttributes())
                .setOnAudioFocusChangeListener(listener)
                .build()
    }
}
