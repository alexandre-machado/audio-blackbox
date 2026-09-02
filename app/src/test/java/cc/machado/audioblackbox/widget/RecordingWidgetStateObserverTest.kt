package cc.machado.audioblackbox.widget

import android.content.Context
import cc.machado.audioblackbox.audio.CaptureState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Regression tests for [RecordingWidgetStateObserver.start]'s re-entrancy guard
 * (`if (job?.isActive == true) return`) (PR #278 review, `@rev` finding 3): plain Kotlin logic
 * over a mockable [Context] and an injectable capture-state [kotlinx.coroutines.flow.StateFlow] /
 * repaint callback -- no Robolectric needed.
 *
 * [RecordingWidgetStateObserver] is a process-lifetime singleton in production (deliberately never
 * reset there); every test here must call [RecordingWidgetStateObserver.resetForTest] in teardown
 * so a job started by one test cannot silently make a later test's [start] call a no-op.
 */
class RecordingWidgetStateObserverTest {

    @After
    fun tearDown() {
        RecordingWidgetStateObserver.resetForTest()
    }

    private fun awaitCount(counter: AtomicInteger, expected: Int, timeoutMillis: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (counter.get() >= expected) return
            Thread.sleep(1)
        }
        fail("timed out waiting for repaint count to reach $expected, was ${counter.get()}")
    }

    @Test
    fun `a second start call while the first collector is active does not attach a second collector`() {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        val flow = MutableStateFlow<CaptureState>(CaptureState.Idle)
        val repaintCount = AtomicInteger(0)

        RecordingWidgetStateObserver.start(context, flow) { repaintCount.incrementAndGet() }
        // StateFlow replays its current value to a brand-new collector immediately, so this first
        // start() call alone should produce exactly one repaint before any real transition.
        awaitCount(repaintCount, 1)

        // Second call: the guard must make this a no-op. If it didn't, a second collector would
        // attach to the same flow and immediately replay another repaint right here, before the
        // deliberate `Thread.sleep` below even lets the first collector's own state settle.
        RecordingWidgetStateObserver.start(context, flow) { repaintCount.incrementAndGet() }
        Thread.sleep(50)
        assertEquals(
            "a second start() call must not attach a second collector (no immediate extra repaint)",
            1,
            repaintCount.get(),
        )

        // One real transition should produce exactly one more repaint -- two would mean a second
        // collector is, in fact, live and reacting independently.
        flow.value = CaptureState.Recording
        awaitCount(repaintCount, 2)
        Thread.sleep(50)
        assertEquals(
            "exactly one collector must be attached; a second one would double this count",
            2,
            repaintCount.get(),
        )
    }
}
