package cc.machado.audioblackbox.widget

import android.content.Context
import cc.machado.audioblackbox.audio.CaptureState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Regression tests for [RecordingWidgetStateObserver.start]'s re-entrancy guard
 * (`if (job?.isActive == true) return`) (PR #278 review, `@rev` finding 3): plain Kotlin logic
 * over a mockable [Context] and an injectable capture-state [kotlinx.coroutines.flow.StateFlow] /
 * repaint callback -- no Robolectric needed.
 *
 * The collector runs on a [StandardTestDispatcher] driven by [advanceUntilIdle], so every
 * assertion below is made at a point where the scheduler has provably run every task it had --
 * there is no wall-clock sleep anywhere, and the "no second collector attached" assertions are
 * statements about a drained scheduler rather than about how much real time happened to pass
 * (`AGENTS.md` §3; PR #278 review, `@rev` finding 2 on the previous revision of this file).
 *
 * [RecordingWidgetStateObserver] is a process-lifetime singleton in production (deliberately never
 * reset there); every test here must call [RecordingWidgetStateObserver.resetForTest] in teardown
 * so a job started by one test cannot silently make a later test's [start] call a no-op.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingWidgetStateObserverTest {

    @After
    fun tearDown() {
        RecordingWidgetStateObserver.resetForTest()
    }

    @Test
    fun `a second start call while the first collector is active does not attach a second collector`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val context = mock<Context>()
            whenever(context.applicationContext).thenReturn(context)
            val flow = MutableStateFlow<CaptureState>(CaptureState.Idle)
            var repaints = 0

            RecordingWidgetStateObserver.start(context, flow, { repaints++ }, dispatcher)
            // StateFlow replays its current value to a brand-new collector, so the first start()
            // alone produces exactly one repaint before any real transition.
            advanceUntilIdle()
            assertEquals("the first start() must attach exactly one collector", 1, repaints)

            // The guard must make this a no-op. Without it a second collector attaches to the same
            // flow and immediately replays another repaint, which the drained scheduler below would
            // have executed -- so this assertion fails on a mutation that removes the guard.
            RecordingWidgetStateObserver.start(context, flow, { repaints++ }, dispatcher)
            advanceUntilIdle()
            assertEquals(
                "a second start() call must not attach a second collector",
                1,
                repaints,
            )

            // One real transition produces exactly one more repaint; two would mean a second
            // collector is in fact live and reacting independently.
            flow.value = CaptureState.Recording
            advanceUntilIdle()
            assertEquals(
                "exactly one collector must be attached; a second one would double this count",
                2,
                repaints,
            )
        }
}
