package cc.machado.audioblackbox.service

import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.audio.RingBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [PeriodicNotificationRefresher] (issue #30: the notification's buffered
 * duration froze while recording ran steadily, because `engine.state.collect { ... }` -- the
 * only thing driving a notification refresh -- only fires on a *transition*, and buffered
 * duration keeps changing without one).
 *
 * These exercise [PeriodicNotificationRefresher] directly, not [RecorderService]: `RecorderService`
 * extends `android.app.Service` and cannot be instantiated in a plain JUnit test in this repo (no
 * Robolectric, per this repo's convention -- see `AudioFocusTrackerTest`/`AudioCaptureEngineTest`
 * for the same constraint elsewhere). Everything Service-specific (posting to
 * `NotificationManager`, wiring `engine.state`/`exportEngine.state`, `onDestroy` cancelling
 * `serviceScope`) is a thin, untested wiring layer around this class; what actually decides
 * *when* a refresh happens -- the property this bug is about -- lives entirely here and is fully
 * covered.
 *
 * Real [RingBuffer] backs both tests' buffered-duration source (not a hand-rolled counter/mock),
 * so "advances" and "pins at saturation" are driven by the same production math the notification
 * ultimately reads through `AudioCaptureEngine.bufferedDurationMillis()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeriodicNotificationRefresherTest {

    @Test
    fun `buffered duration advances across ticks while Recording never transitions`() = runTest {
        val bytesPerSecond = 16_000 // matches AudioConfig's 16kHz mono default
        val capacityBytes = bytesPerSecond * 60 // 60s retention -- writes below stay well under it
        val ringBuffer = RingBuffer(capacityBytes = capacityBytes, bytesPerSecond = bytesPerSecond)

        // Fixed at Recording for the whole test -- collect() on this StateFlow never re-emits
        // (same value), so any tick observed here cannot be attributed to a state transition.
        val state = MutableStateFlow<CaptureState>(CaptureState.Recording)
        val refresher = PeriodicNotificationRefresher(state, intervalMillis = 10_000L)

        val observedDurationsMillis = mutableListOf<Long>()
        val job = launch {
            refresher.run {
                // A chunk of "captured" audio lands between ticks, exactly as the real capture
                // thread keeps writing into the ring buffer while the service's collector sits
                // idle between engine.state transitions.
                ringBuffer.write(ByteArray(bytesPerSecond * 3)) // 3s of audio per tick
                val bufferedBytes = ringBuffer.bufferedBytes()
                observedDurationsMillis += (bufferedBytes * 1000) / bytesPerSecond
            }
        }

        advanceTimeBy(35_000L) // ~3.5 ticks at the 10s interval used above
        job.cancel()

        assertTrue(
            "expected multiple ticks with no state transition, got ${observedDurationsMillis.size}",
            observedDurationsMillis.size >= 3,
        )
        // Strictly increasing: each tick's buffered duration must exceed the previous one, proving
        // this is a live, advancing read -- not a value the loop just resampled unchanged (which is
        // exactly what the frozen-notification bug looked like on-device).
        for (i in 1 until observedDurationsMillis.size) {
            assertTrue(
                "tick $i (${observedDurationsMillis[i]}) did not advance past tick ${i - 1} " +
                    "(${observedDurationsMillis[i - 1]})",
                observedDurationsMillis[i] > observedDurationsMillis[i - 1],
            )
        }
    }

    @Test
    fun `buffered duration pins at the retention window once the ring buffer saturates`() = runTest {
        val bytesPerSecond = 16_000
        // Small capacity (2s of retention) so a handful of ticks writing 3s each saturates it
        // quickly and repeatably, without needing an unrealistically long virtual-time run.
        val capacityBytes = bytesPerSecond * 2
        val ringBuffer = RingBuffer(capacityBytes = capacityBytes, bytesPerSecond = bytesPerSecond)

        val state = MutableStateFlow<CaptureState>(CaptureState.Recording)
        val refresher = PeriodicNotificationRefresher(state, intervalMillis = 10_000L)

        val observedDurationsMillis = mutableListOf<Long>()
        val job = launch {
            refresher.run {
                ringBuffer.write(ByteArray(bytesPerSecond * 3)) // 3s per tick, into a 2s buffer
                val bufferedBytes = ringBuffer.bufferedBytes()
                observedDurationsMillis += (bufferedBytes * 1000) / bytesPerSecond
            }
        }

        advanceTimeBy(45_000L) // several ticks past the point saturation is reached
        job.cancel()

        val retentionMillis = (capacityBytes.toLong() * 1000) / bytesPerSecond
        assertTrue(
            "expected multiple post-saturation ticks, got ${observedDurationsMillis.size}",
            observedDurationsMillis.size >= 3,
        )
        // Every tick after the very first single write already exceeds the 2s capacity (3s per
        // write), so the buffer is saturated from tick 1 onward: every observed value must equal
        // the retention window exactly, and -- unlike the "advances" test -- must NOT differ from
        // its neighbor. A frozen notification and a correctly-pinned one both show a flat sequence
        // of identical numbers; what tells them apart is that this flat value equals the retention
        // window derived from real writes, not a stale number left over from long before
        // saturation, which is what the assertion below (not just "constant") pins down.
        for ((index, observed) in observedDurationsMillis.withIndex()) {
            assertEquals(
                "tick $index: expected the retention window ($retentionMillis ms) once saturated",
                retentionMillis,
                observed,
            )
        }
    }
}
