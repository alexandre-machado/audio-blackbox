package cc.machado.audioblackbox.service

import cc.machado.audioblackbox.billing.Entitlement
import cc.machado.audioblackbox.billing.ProSource
import cc.machado.audioblackbox.export.ForwardRecordingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FreeLimitNoticeTest {
    private val capSaved = ForwardRecordingState.Success("live.m4a", 8L, freeLimitReached = true)

    /**
     * Same contract as `ForwardRecordingEngine.acknowledgeTerminalState`: a terminal state resets
     * to Idle, anything else is left alone. Counting calls lets the test tell "cleared" apart from
     * "the state happened to change some other way".
     */
    private class Engine(initial: ForwardRecordingState) {
        val state = MutableStateFlow(initial)
        var acknowledgements = 0
        fun acknowledge() {
            acknowledgements++
            val current = state.value
            if (current is ForwardRecordingState.Success || current is ForwardRecordingState.Error) {
                state.value = ForwardRecordingState.Idle
            }
        }
    }

    // Oracle (PR #447 `@rev` finding 5): the cap-saved state the notification and dashboard render
    // from survives while the user is Free or Unknown, and is reset to Idle exactly once when the
    // entitlement becomes Pro. If the service never clears it, state stays Success(cap) and the
    // last assertions fail.
    @Test fun capNoticeClearsWhenEntitlementBecomesPro() = runTest {
        val engine = Engine(capSaved)
        val entitlement = MutableStateFlow<Entitlement>(Entitlement.Unknown)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            clearFreeLimitNoticeOnPro(engine.state, entitlement, engine::acknowledge)
        }
        runCurrent()
        assertEquals(capSaved, engine.state.value)
        entitlement.value = Entitlement.Free; runCurrent()
        assertEquals("Free must keep the cap notice", capSaved, engine.state.value)
        assertEquals(0, engine.acknowledgements)
        entitlement.value = Entitlement.Pro(ProSource.PURCHASE); runCurrent()
        assertEquals(ForwardRecordingState.Idle, engine.state.value)
        assertEquals(1, engine.acknowledgements)
    }

    // Oracle: only the cap outcome is cleared. A user-stopped Success (its own 8 s timer owns it)
    // and a session running again are never acknowledged on account of Pro.
    @Test fun proDoesNotClearOtherOutcomes() = runTest {
        val engine = Engine(ForwardRecordingState.Success("live.m4a", 8L))
        val entitlement = MutableStateFlow<Entitlement>(Entitlement.Pro(ProSource.GRANDFATHERED))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            clearFreeLimitNoticeOnPro(engine.state, entitlement, engine::acknowledge)
        }
        runCurrent()
        assertEquals(0, engine.acknowledgements)
        engine.state.value = ForwardRecordingState.Recording("next.m4a", 0, null); runCurrent()
        assertEquals(0, engine.acknowledgements)
    }
}
