package cc.machado.audioblackbox.service

import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Tracks the single in-flight [AudioFocusRequest] this service holds, abandoning any prior
 * request before installing a new one. Deliberately extracted out of [RecorderService] (rather
 * than kept as a bare `focusRequest` field with abandon-then-request inlined at the call site) so
 * this specific guarantee -- a repeated `ACTION_START` can never leak a registration with
 * `AudioManager` -- is unit-testable with a mocked [AudioManager], without needing Robolectric or
 * a real `Context` (see PR #23 review, `@rev` finding 4).
 */
class AudioFocusTracker(private val audioManager: AudioManager) {
    private var current: AudioFocusRequest? = null

    /** Abandons any request currently held, then builds and installs a new one via [build]. */
    fun request(build: () -> AudioFocusRequest) {
        abandon()
        val newRequest = build()
        current = newRequest
        audioManager.requestAudioFocus(newRequest)
    }

    /** Abandons the currently held request, if any. Idempotent. */
    fun abandon() {
        current?.let { audioManager.abandonAudioFocusRequest(it) }
        current = null
    }
}
