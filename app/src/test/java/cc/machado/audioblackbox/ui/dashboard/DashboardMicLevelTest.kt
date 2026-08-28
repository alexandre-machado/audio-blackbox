package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.CaptureErrorReason
import cc.machado.audioblackbox.audio.CaptureState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dashboard's half of the honesty rule (issue #175): the meter may only show a level while
 * capture is genuinely Recording. Paused is the case that matters -- the engine still holds
 * `AudioRecord`, but nothing reaches the ring buffer, so a live-looking meter there would claim
 * capture that is not happening. That is exactly what the old animated placeholder did, and it is
 * why #155's silencing strand could sit on screen looking healthy.
 */
class DashboardMicLevelTest {

    private fun state(capture: CaptureState, level: Float) = DashboardViewModel.mapUiState(
        captureState = capture,
        bufferedMillis = 60_000L,
        capacityMinutes = 30,
        saveState = SaveUiState.Idle,
        inputLevel = level,
    )

    @Test
    fun `a real level passes through while recording`() {
        assertEquals(0.42f, state(CaptureState.Recording, 0.42f).inputLevel, 0.0001f)
    }

    @Test
    fun `paused reports an empty meter even if a level is supplied`() {
        assertEquals(
            "Paused means nothing is reaching the ring buffer -- the meter must read empty",
            0f,
            state(CaptureState.Paused, 0.9f).inputLevel,
            0f,
        )
    }

    @Test
    fun `idle reports an empty meter`() {
        assertEquals(0f, state(CaptureState.Idle, 0.9f).inputLevel, 0f)
    }

    @Test
    fun `an errored capture reports an empty meter`() {
        val errored = CaptureState.Error(CaptureErrorReason.READ_DEAD_OBJECT, "gone")
        assertEquals(0f, state(errored, 0.9f).inputLevel, 0f)
    }

    @Test
    fun `an out-of-range level is clamped rather than trusted`() {
        assertEquals(1f, state(CaptureState.Recording, 4.2f).inputLevel, 0f)
        assertEquals(0f, state(CaptureState.Recording, -1f).inputLevel, 0f)
    }
}
