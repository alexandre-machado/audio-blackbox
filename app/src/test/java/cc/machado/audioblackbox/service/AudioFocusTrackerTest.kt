package cc.machado.audioblackbox.service

import android.media.AudioFocusRequest
import android.media.AudioManager
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * Regression test for PR #23 review, `@rev` finding 4: a repeated `ACTION_START` (duplicate tap,
 * redelivered intent) must not leak the prior [AudioFocusRequest] registration with
 * [AudioManager]. Uses Mockito's inline mock maker (mockito-core 5+, same convention as
 * [cc.machado.audioblackbox.audio.AudioCaptureEngineTest]) to fake both final Android framework
 * types without Robolectric -- [AudioFocusTracker] is deliberately Context/Service-free so this is
 * possible at all.
 */
class AudioFocusTrackerTest {

    @Test
    fun `repeated request() abandons the prior request before installing the new one`() {
        val audioManager = mock<AudioManager>()
        val tracker = AudioFocusTracker(audioManager)
        val first = mock<AudioFocusRequest>()
        val second = mock<AudioFocusRequest>()

        tracker.request { first }
        tracker.request { second }

        verify(audioManager).abandonAudioFocusRequest(first)
        verify(audioManager).requestAudioFocus(first)
        verify(audioManager).requestAudioFocus(second)
        // The bug this guards against: overwriting the held request without ever abandoning it.
        verify(audioManager, never()).abandonAudioFocusRequest(second)
    }

    @Test
    fun `request() never abandons anything on the first call`() {
        val audioManager = mock<AudioManager>()
        val tracker = AudioFocusTracker(audioManager)
        val only = mock<AudioFocusRequest>()

        tracker.request { only }

        verify(audioManager, never()).abandonAudioFocusRequest(org.mockito.kotlin.any())
        verify(audioManager).requestAudioFocus(only)
    }

    @Test
    fun `abandon() after request() releases the held request and is idempotent`() {
        val audioManager = mock<AudioManager>()
        val tracker = AudioFocusTracker(audioManager)
        val request = mock<AudioFocusRequest>()

        tracker.request { request }
        tracker.abandon()
        tracker.abandon() // must not throw or double-abandon

        verify(audioManager, org.mockito.kotlin.times(1)).abandonAudioFocusRequest(request)
    }
}
