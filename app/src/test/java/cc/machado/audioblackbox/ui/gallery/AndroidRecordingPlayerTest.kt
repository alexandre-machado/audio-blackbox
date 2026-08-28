package cc.machado.audioblackbox.ui.gallery

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Covers [AndroidRecordingPlayer]'s own responsibility for the "only one item plays at a time"
 * invariant (issue #7), plus audio-focus request/denial/loss -- all against a mocked
 * [MediaPlayer]/[AudioManager] (final Android framework classes, mockable via mockito-core's
 * inline mock maker, same convention as [cc.machado.audioblackbox.audio.AudioCaptureEngineTest]
 * and [cc.machado.audioblackbox.audio.AudioCaptureEngineTest]) -- no Robolectric, no real device.
 *
 * [MediaPlayer.prepareAsync] is asynchronous in production; here the
 * [MediaPlayer.OnPreparedListener] this class installs is captured via
 * [org.mockito.kotlin.argumentCaptor] and invoked manually to deterministically simulate "the
 * player has finished preparing", with no `sleep` and no real timing dependency anywhere in this
 * file.
 */
class AndroidRecordingPlayerTest {

    private val context = mock<Context>()
    private val audioManager = mock<AudioManager>()

    private fun newPlayer(
        players: MutableList<MediaPlayer>,
        focusResult: Int = AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
    ): AndroidRecordingPlayer {
        whenever(audioManager.requestAudioFocus(any<AudioFocusRequest>())).thenReturn(focusResult)
        return AndroidRecordingPlayer(
            context = context,
            audioManager = audioManager,
            mediaPlayerFactory = { mock<MediaPlayer>().also { players += it } },
            focusRequestFactory = { mock<AudioFocusRequest>() },
            playbackAudioAttributesFactory = { mock() },
        )
    }

    private fun firePrepared(players: List<MediaPlayer>, index: Int) {
        val captor = argumentCaptor<MediaPlayer.OnPreparedListener>()
        verify(players[index]).setOnPreparedListener(captor.capture())
        captor.firstValue.onPrepared(players[index])
    }

    @Test
    fun `play requests audio focus and only reports Playing once the player has actually prepared`() {
        val players = mutableListOf<MediaPlayer>()
        val player = newPlayer(players)
        val uri = mock<Uri>()

        player.play(uri, "audio/mp4")

        // The listener has been installed but not fired yet -- a slow-to-prepare file must not
        // report itself as already playing.
        assertEquals(PlaybackState.Idle, player.playback.value)
        verify(audioManager).requestAudioFocus(any<AudioFocusRequest>())

        firePrepared(players, 0)

        assertEquals(PlaybackState.Playing(uri), player.playback.value)
        verify(players[0]).start()
    }

    @Test
    fun `play denied audio focus never starts a player and leaves playback Idle`() {
        val players = mutableListOf<MediaPlayer>()
        val player = newPlayer(players, focusResult = AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val uri = mock<Uri>()

        player.play(uri, "audio/mp4")

        assertEquals(PlaybackState.Idle, player.playback.value)
        assertTrue("a denied focus request must never even construct a player", players.isEmpty())
    }

    @Test
    fun `starting playback of a second item stops and releases the first player instance -- the single-player invariant`() {
        val players = mutableListOf<MediaPlayer>()
        val player = newPlayer(players)
        val uriA = mock<Uri>()
        val uriB = mock<Uri>()

        player.play(uriA, "audio/mp4")
        firePrepared(players, 0)
        assertEquals(PlaybackState.Playing(uriA), player.playback.value)

        player.play(uriB, "audio/mp4")

        // The exact behaviour that would silently break if teardown() were ever skipped before
        // installing a new player: A's real MediaPlayer must actually be stopped and released, not
        // merely have the shared PlaybackState value overwritten out from under it.
        verify(players[0]).stop()
        verify(players[0]).release()
        assertEquals(2, players.size)

        firePrepared(players, 1)

        assertEquals(PlaybackState.Playing(uriB), player.playback.value)
    }

    @Test
    fun `pausing then starting a different item still tears down the paused player first`() {
        val players = mutableListOf<MediaPlayer>()
        val player = newPlayer(players)
        val uriA = mock<Uri>()
        val uriB = mock<Uri>()
        player.play(uriA, "audio/mp4")
        firePrepared(players, 0)
        player.pause()
        assertEquals(PlaybackState.Paused(uriA), player.playback.value)

        player.play(uriB, "audio/mp4")

        verify(players[0]).release()
        assertEquals(2, players.size)
    }

    @Test
    fun `losing audio focus -- even transiently, as on an incoming call -- pauses playback`() {
        val players = mutableListOf<MediaPlayer>()
        val player = newPlayer(players)
        val uri = mock<Uri>()
        player.play(uri, "audio/mp4")
        firePrepared(players, 0)
        assertEquals(PlaybackState.Playing(uri), player.playback.value)

        player.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertEquals(PlaybackState.Paused(uri), player.playback.value)
        verify(players[0]).pause()
    }

    @Test
    fun `regaining audio focus does not auto-resume playback the user did not ask to resume`() {
        val players = mutableListOf<MediaPlayer>()
        val player = newPlayer(players)
        val uri = mock<Uri>()
        player.play(uri, "audio/mp4")
        firePrepared(players, 0)
        player.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(PlaybackState.Paused(uri), player.playback.value)

        player.handleFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(PlaybackState.Paused(uri), player.playback.value)
    }

    @Test
    fun `stop tears down the loaded player and abandons audio focus`() {
        val players = mutableListOf<MediaPlayer>()
        val player = newPlayer(players)
        val uri = mock<Uri>()
        player.play(uri, "audio/mp4")
        firePrepared(players, 0)

        player.stop()

        assertEquals(PlaybackState.Idle, player.playback.value)
        verify(players[0]).release()
        verify(audioManager).abandonAudioFocusRequest(any<AudioFocusRequest>())
    }

    @Test
    fun `playback completing on its own tears down the player the same way stop does`() {
        val players = mutableListOf<MediaPlayer>()
        val player = newPlayer(players)
        val uri = mock<Uri>()
        player.play(uri, "audio/mp4")
        firePrepared(players, 0)

        val completionCaptor = argumentCaptor<MediaPlayer.OnCompletionListener>()
        verify(players[0]).setOnCompletionListener(completionCaptor.capture())
        completionCaptor.firstValue.onCompletion(players[0])

        assertEquals(PlaybackState.Idle, player.playback.value)
        verify(players[0]).release()
    }
}
