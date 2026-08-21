package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.export.ExportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Instance-level regression test for [DashboardViewModel.requestSave]'s double-tap guard
 * (issue #40 follow-up): a rapid second tap must not dispatch a second save intent while the
 * first one is still in flight -- previously only the window-option-enabled check guarded
 * [DashboardViewModel.requestSave], which does not change between two calls in the same tick, so
 * nothing stopped a duplicate dispatch. This is the one instance-behaviour test issue #40 needs
 * despite full instance-test coverage otherwise being issue #41/PR #42's scope -- the defect is
 * specific to this PR's own change (issue #40 item 2 wiring real `ExportState` onto the screen is
 * what turns the duplicate dispatch into a *visible* error).
 *
 * Uses [StandardTestDispatcher] (`kotlinx-coroutines-test`), never a real `delay`: the whole
 * scenario is "two calls with no time passing between them", which is exactly what calling
 * [DashboardViewModel.requestSave] twice in a row, synchronously, already reproduces -- no
 * virtual-time advance is needed to prove the double dispatch, only to let the ViewModel's
 * `init` collector (which observes [ExportState] to release the guard once a real export starts)
 * run in the second test below.
 */
class DashboardViewModelDoubleTapTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        exportState: MutableStateFlow<ExportState>,
        onSaveIntent: (Int) -> Unit,
    ) = DashboardViewModel(
        captureState = MutableStateFlow(CaptureState.Recording),
        bufferedDurationMillisProvider = { 30 * 60_000L },
        capacityMinutesFlow = MutableStateFlow(30),
        exportState = exportState,
        onSaveIntent = onSaveIntent,
    )

    @Test
    fun `two rapid requestSave calls before the export round trip starts dispatch exactly one save intent`() = runTest {
        val exportState = MutableStateFlow<ExportState>(ExportState.Idle)
        var dispatchCount = 0
        val viewModel = newViewModel(exportState) { dispatchCount++ }

        // Simulates a double-tap: both calls happen before RecorderService's async dispatch has
        // had any chance to flip exportState away from Idle -- exportState is still Idle for
        // both, exactly the race the window-option-enabled check alone cannot catch.
        viewModel.requestSave(30)
        viewModel.requestSave(30)

        assertEquals(
            "a second requestSave() while the first is still awaiting a real ExportState must " +
                "be ignored, not dispatched a second time",
            1,
            dispatchCount,
        )
    }

    @Test
    fun `once the export actually starts, requestSave is rejected until it finishes`() = runTest {
        val exportState = MutableStateFlow<ExportState>(ExportState.Idle)
        var dispatchCount = 0
        val viewModel = newViewModel(exportState) { dispatchCount++ }

        viewModel.requestSave(30)
        assertEquals(1, dispatchCount)

        // The real ExportState finally catches up (as it would once RecorderService's async
        // export actually starts) -- a tap arriving now must still be rejected, on the
        // exportState.value check this time, not the pending-dispatch one.
        exportState.value = ExportState.Exporting
        runCurrent()

        viewModel.requestSave(30)
        assertEquals(
            "a tap while a real export is Exporting must still be ignored",
            1,
            dispatchCount,
        )
    }

    @Test
    fun `once a prior export has finished, a later requestSave is allowed again`() = runTest {
        val exportState = MutableStateFlow<ExportState>(ExportState.Idle)
        var dispatchCount = 0
        val viewModel = newViewModel(exportState) { dispatchCount++ }

        viewModel.requestSave(30)
        assertEquals(1, dispatchCount)

        // The export genuinely starts and finishes -- the guard must not be permanently stuck
        // once it has served its purpose for this one save.
        exportState.value = ExportState.Exporting
        runCurrent()
        exportState.value = ExportState.Success(displayName = "blackbox_2026-08-21_10-00-00_30min.m4a", bytesWritten = 1)
        runCurrent()
        exportState.value = ExportState.Idle // RecorderService's own post-notification reset
        runCurrent()

        viewModel.requestSave(30)
        assertEquals(
            "a later, genuinely new save request must not be blocked by a guard that only " +
                "ever existed to cover the previous save's pre-Exporting gap",
            2,
            dispatchCount,
        )
    }

    // ---- the guard must never survive a failed dispatch (`@techlead` adjudication on PR #43,
    // raised to blocking -- a permanent Save lockout was already a blocking finding once in this
    // project, issue #30's stranded-notification class) ----

    @Test
    fun `a dispatch that throws leaves Save usable for the next call`() = runTest {
        val exportState = MutableStateFlow<ExportState>(ExportState.Idle)
        var dispatchCount = 0
        var shouldThrow = true
        val viewModel = newViewModel(exportState) { minutes ->
            dispatchCount++
            if (shouldThrow) error("onSaveIntent boom")
        }

        // The exception is a real failure of the first dispatch -- it is expected to propagate
        // (requestSave() does not swallow it), but it must not leave the guard permanently set on
        // this same ViewModel instance: nothing will ever move exportState away from Idle for a
        // dispatch that never reached RecorderService, so if the guard survived this it would
        // survive forever.
        assertThrows(IllegalStateException::class.java) { viewModel.requestSave(30) }
        assertEquals(1, dispatchCount)

        shouldThrow = false
        viewModel.requestSave(30)
        assertEquals(
            "a throwing dispatch must not permanently lock Save out -- the very next call on " +
                "the same instance (here, effectively immediately, no virtual time needed) must " +
                "still be able to dispatch",
            2,
            dispatchCount,
        )
    }

    @Test
    fun `a dispatch that silently never reaches the service is released by the timeout backstop`() = runTest {
        val exportState = MutableStateFlow<ExportState>(ExportState.Idle)
        var dispatchCount = 0
        val viewModel = newViewModel(exportState) { dispatchCount++ }

        viewModel.requestSave(30)
        assertEquals(1, dispatchCount)

        // No exception, and exportState never moves -- simulates onSaveIntent returning
        // normally (e.g. ContextCompat.startForegroundService() didn't throw) but the Intent
        // never actually reaching RecorderService.onStartCommand. Advancing past
        // DashboardViewModel's own DISPATCH_TIMEOUT_MILLIS (5_000L; not importable here since
        // it is private -- this must stay >= that value) is the only thing that can release the
        // guard in this scenario, since nothing else ever will.
        advanceTimeBy(5_001L)
        runCurrent()

        viewModel.requestSave(30)
        assertEquals(
            "a dispatch that never produces a real ExportState transition must still release " +
                "the guard once the timeout backstop elapses, not lock Save out forever",
            2,
            dispatchCount,
        )
    }

    // ---- issue #50: a stale timeout Job from an earlier dispatch must not release a later
    // dispatch's guard (`@rev` advisory on PR #43) ----

    @Test
    fun `a stale timeout from a throwing dispatch does not release a later dispatch's guard early`() = runTest {
        val exportState = MutableStateFlow<ExportState>(ExportState.Idle)
        var dispatchCount = 0
        var shouldThrow = true
        val viewModel = newViewModel(exportState) { minutes ->
            dispatchCount++
            if (shouldThrow) error("onSaveIntent boom")
        }

        // t=0: first dispatch throws. Its `finally` releases the guard immediately, but (pre-fix)
        // also leaves a 5s backstop Job scheduled and uncancelled -- a stale timer armed to fire
        // at t=5000 regardless of what happens afterwards.
        assertThrows(IllegalStateException::class.java) { viewModel.requestSave(30) }
        assertEquals(1, dispatchCount)

        // t=2000: some time later, a second, genuine dispatch is made. It does not throw, so it
        // re-arms the guard (saveDispatchPending = true) and schedules its own backstop, which
        // (pre-fix) is unrelated to the stale one above -- both are independent, uncancelled
        // launch{} coroutines racing to flip the same flag.
        shouldThrow = false
        advanceTimeBy(2_000L)
        runCurrent()
        viewModel.requestSave(30)
        assertEquals(2, dispatchCount)

        // t=5000: the FIRST dispatch's stale timer (armed at t=0, 5s duration) fires here. The
        // second dispatch is still genuinely in flight -- its own confirmation (a real, non-Idle
        // ExportState) has not arrived, and its own backstop is not due until t=7000. Correct
        // behaviour is for the guard to remain set, so a third call right now is still rejected.
        advanceTimeBy(3_000L) // t: 2000 -> 5000
        runCurrent()

        viewModel.requestSave(30)
        assertEquals(
            "a stale backstop Job from the first (thrown) dispatch fired at t=5000 and released " +
                "the guard belonging to the second, still-in-flight dispatch -- production " +
                "behaviour that would have to break for this assertion to hold: the uncancelled " +
                "timeout Job scheduled by the first requestSave() call must never touch " +
                "saveDispatchPending once that call's own guard has already been released by " +
                "another path",
            2,
            dispatchCount,
        )
    }
}
