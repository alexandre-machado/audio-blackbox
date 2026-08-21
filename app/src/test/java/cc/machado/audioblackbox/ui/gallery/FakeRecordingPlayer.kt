package cc.machado.audioblackbox.ui.gallery

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory [RecordingPlayer] for [GalleryViewModel] instance tests -- records every [play] call
 * so a test can assert exactly which uris were started and in what order, without needing a real
 * [android.media.MediaPlayer] (that invariant -- that starting a second item actually tears down
 * the first -- is [AndroidRecordingPlayer]'s own responsibility and is covered directly by
 * [AndroidRecordingPlayerTest] instead). */
class FakeRecordingPlayer : RecordingPlayer {
    private val _playback = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    val playCalls = mutableListOf<Uri>()
    var stopCalled = 0
    var released = false
    var position = 0L

    override fun play(uri: Uri, mimeType: String) {
        playCalls += uri
        _playback.value = PlaybackState.Playing(uri)
    }

    override fun pause() {
        val current = _playback.value
        if (current is PlaybackState.Playing) _playback.value = PlaybackState.Paused(current.uri)
    }

    override fun resume() {
        val current = _playback.value
        if (current is PlaybackState.Paused) _playback.value = PlaybackState.Playing(current.uri)
    }

    override fun seekTo(positionMillis: Long) {
        position = positionMillis
    }

    override fun currentPositionMillis(): Long = position

    override fun durationMillis(): Long = 0L

    override fun stop() {
        stopCalled++
        _playback.value = PlaybackState.Idle
    }

    override fun release() {
        released = true
    }
}
