package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.export.ExportState
import cc.machado.audioblackbox.export.ForwardRecordingFailureReason
import cc.machado.audioblackbox.export.ForwardRecordingState
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
 * Instance-level regression test for [DashboardViewModel.requestSave] and [DashboardViewModel.startForwardRecording]'s
 * double-tap guards (issues #40 and #208): a rapid second tap must not dispatch a second intent while the
 * first one is still in flight.
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
        exportState: MutableStateFlow<ExportState> = MutableStateFlow(ExportState.Idle),
        onSaveIntent: () -> Unit = {},
    ) = DashboardViewModel(
        captureState = MutableStateFlow(CaptureState.Recording),
        bufferedDurationMillisProvider = { 30 * 60_000L },
        capacityMinutesFlow = MutableStateFlow(30),
        exportState = exportState,
        onSaveIntent = onSaveIntent,
    )

    private fun newForwardViewModel(
        forwardRecordingState: MutableStateFlow<ForwardRecordingState> = MutableStateFlow(ForwardRecordingState.Idle),
        onStartForwardRecording: () -> Unit = {},
    ) = DashboardViewModel(
        captureState = MutableStateFlow(CaptureState.Recording),
        bufferedDurationMillisProvider = { 30 * 60_000L },
        capacityMinutesFlow = MutableStateFlow(30),
        forwardRecordingState = forwardRecordingState,
        onStartForwardRecording = onStartForwardRecording,
    )

    @Test
    fun `two rapid requestSave calls before the export round trip starts dispatch exactly one save intent`() = runTest {
        val exportState = MutableStateFlow<ExportState>(ExportState.Idle)
        var dispatchCount = 0
        val viewModel = newViewModel(exportState) { dispatchCount++ }

        // Simulates a double-tap: both calls happen before RecorderService's async dispatch has
        // had any chance to flip exportState away from Idle -- exportState is still Idle for
        // both, exactly the race the window-option-enabled check alone cannot catch.
        viewModel.requestSave()
        viewModel.requestSave()

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

        viewModel.requestSave()
        assertEquals(1, dispatchCount)

        // The real ExportState finally catches up (as it would once RecorderService's async
        // export actually starts) -- a tap arriving now must still be rejected, on the
        // exportState.value check this time, not the pending-dispatch one.
        exportState.value = ExportState.Exporting
        runCurrent()

        viewModel.requestSave()
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

        viewModel.requestSave()
        assertEquals(1, dispatchCount)

        // The export genuinely starts and finishes -- the guard must not be permanently stuck
        // once it has served its purpose for this one save.
        exportState.value = ExportState.Exporting
        runCurrent()
        exportState.value = ExportState.Success(displayName = "blackbox_2026-08-21_10-00-00_30min.m4a", bytesWritten = 1)
        runCurrent()
        exportState.value = ExportState.Idle // RecorderService's own post-notification reset
        runCurrent()

        viewModel.requestSave()
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
        val viewModel = newViewModel(exportState) {
            dispatchCount++
            if (shouldThrow) error("onSaveIntent boom")
        }

        // The exception is a real failure of the first dispatch -- it is expected to propagate
        // (requestSave() does not swallow it), but it must not leave the guard permanently set on
        // this same ViewModel instance: nothing will ever move exportState away from Idle for a
        // dispatch that never reached RecorderService, so if the guard survived this it would
        // survive forever.
        assertThrows(IllegalStateException::class.java) { viewModel.requestSave() }
        assertEquals(1, dispatchCount)

        shouldThrow = false
        viewModel.requestSave()
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

        viewModel.requestSave()
        assertEquals(1, dispatchCount)

        // No exception, and exportState never moves -- simulates onSaveIntent returning
        // normally (e.g. ContextCompat.startForegroundService() didn't throw) but the Intent
        // never actually reaching RecorderService.onStartCommand. Advancing past
        // DashboardViewModel's own DISPATCH_TIMEOUT_MILLIS (5_000L; not importable here since
        // it is private -- this must stay >= that value) is the only thing that can release the
        // guard in this scenario, since nothing else ever will.
        advanceTimeBy(5_001L)
        runCurrent()

        viewModel.requestSave()
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
        val viewModel = newViewModel(exportState) {
            dispatchCount++
            if (shouldThrow) error("onSaveIntent boom")
        }

        // t=0: first dispatch throws. Its `finally` releases the guard immediately, but (pre-fix)
        // also leaves a 5s backstop Job scheduled and uncancelled -- a stale timer armed to fire
        // at t=5000 regardless of what happens afterwards.
        assertThrows(IllegalStateException::class.java) { viewModel.requestSave() }
        assertEquals(1, dispatchCount)

        // t=2000: some time later, a second, genuine dispatch is made. It does not throw, so it
        // re-arms the guard (saveDispatchPending = true) and schedules its own backstop, which
        // (pre-fix) is unrelated to the stale one above -- both are independent, uncancelled
        // launch{} coroutines racing to flip the same flag.
        shouldThrow = false
        advanceTimeBy(2_000L)
        runCurrent()
        viewModel.requestSave()
        assertEquals(2, dispatchCount)

        // t=5000: the FIRST dispatch's stale timer (armed at t=0, 5s duration) fires here. The
        // second dispatch is still genuinely in flight -- its own confirmation (a real, non-Idle
        // ExportState) has not arrived, and its own backstop is not due until t=7000. Correct
        // behaviour is for the guard to remain set, so a third call right now is still rejected.
        advanceTimeBy(3_000L) // t: 2000 -> 5000
        runCurrent()

        viewModel.requestSave()
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

    // ---- Forward Recording Double-Tap Tests (Issue #208) ----

    @Test
    fun `two rapid startForwardRecording calls before round trip starts dispatch exactly one start intent`() = runTest {
        val forwardRecordingState = MutableStateFlow<ForwardRecordingState>(ForwardRecordingState.Idle)
        var dispatchCount = 0
        val viewModel = newForwardViewModel(forwardRecordingState = forwardRecordingState) { dispatchCount++ }

        viewModel.startForwardRecording()
        viewModel.startForwardRecording()

        assertEquals(
            "a second startForwardRecording() while the first is still awaiting a real ForwardRecordingState " +
                "must be ignored, not dispatched a second time",
            1,
            dispatchCount,
        )
    }

    @Test
    fun `once forward recording actually starts, startForwardRecording is rejected until it finishes`() = runTest {
        val forwardRecordingState = MutableStateFlow<ForwardRecordingState>(ForwardRecordingState.Idle)
        var dispatchCount = 0
        val viewModel = newForwardViewModel(forwardRecordingState = forwardRecordingState) { dispatchCount++ }

        viewModel.startForwardRecording()
        assertEquals(1, dispatchCount)

        forwardRecordingState.value = ForwardRecordingState.Recording(displayName = "blackbox_2026-08-29_11-00-00.m4a", bytesWritten = 0)
        runCurrent()

        viewModel.startForwardRecording()
        assertEquals(
            "a tap while forward recording is Recording must be ignored",
            1,
            dispatchCount,
        )
    }

    @Test
    fun `once a prior forward recording has finished, a later startForwardRecording is allowed again`() = runTest {
        val forwardRecordingState = MutableStateFlow<ForwardRecordingState>(ForwardRecordingState.Idle)
        var dispatchCount = 0
        val viewModel = newForwardViewModel(forwardRecordingState = forwardRecordingState) { dispatchCount++ }

        viewModel.startForwardRecording()
        assertEquals(1, dispatchCount)

        forwardRecordingState.value = ForwardRecordingState.Recording(displayName = "rec1.m4a", bytesWritten = 100)
        runCurrent()
        forwardRecordingState.value = ForwardRecordingState.Success(displayName = "rec1.m4a", bytesWritten = 100)
        runCurrent()
        forwardRecordingState.value = ForwardRecordingState.Idle
        runCurrent()

        viewModel.startForwardRecording()
        assertEquals(
            "a later, genuinely new start request must not be blocked after previous recording finished",
            2,
            dispatchCount,
        )
    }

    @Test
    fun `a forward recording dispatch that throws leaves startForwardRecording usable for the next call`() = runTest {
        val forwardRecordingState = MutableStateFlow<ForwardRecordingState>(ForwardRecordingState.Idle)
        var dispatchCount = 0
        var shouldThrow = true
        val viewModel = newForwardViewModel(forwardRecordingState = forwardRecordingState) {
            dispatchCount++
            if (shouldThrow) error("onStartForwardRecording boom")
        }

        assertThrows(IllegalStateException::class.java) { viewModel.startForwardRecording() }
        assertEquals(1, dispatchCount)

        shouldThrow = false
        viewModel.startForwardRecording()
        assertEquals(
            "a throwing dispatch must not permanently lock startForwardRecording out",
            2,
            dispatchCount,
        )
    }

    @Test
    fun `a forward recording dispatch that silently never reaches the service is released by timeout backstop`() = runTest {
        val forwardRecordingState = MutableStateFlow<ForwardRecordingState>(ForwardRecordingState.Idle)
        var dispatchCount = 0
        val viewModel = newForwardViewModel(forwardRecordingState = forwardRecordingState) { dispatchCount++ }

        viewModel.startForwardRecording()
        assertEquals(1, dispatchCount)

        advanceTimeBy(5_001L)
        runCurrent()

        viewModel.startForwardRecording()
        assertEquals(
            "a dispatch that never produces a real ForwardRecordingState transition must still release " +
                "the guard once the timeout backstop elapses",
            2,
            dispatchCount,
        )
    }

    @Test
    fun `a stale timeout from a throwing forward recording dispatch does not release a later dispatch guard early`() = runTest {
        val forwardRecordingState = MutableStateFlow<ForwardRecordingState>(ForwardRecordingState.Idle)
        var dispatchCount = 0
        var shouldThrow = true
        val viewModel = newForwardViewModel(forwardRecordingState = forwardRecordingState) {
            dispatchCount++
            if (shouldThrow) error("onStartForwardRecording boom")
        }

        assertThrows(IllegalStateException::class.java) { viewModel.startForwardRecording() }
        assertEquals(1, dispatchCount)

        shouldThrow = false
        advanceTimeBy(2_000L)
        runCurrent()
        viewModel.startForwardRecording()
        assertEquals(2, dispatchCount)

        advanceTimeBy(3_000L) // t: 2000 -> 5000
        runCurrent()

        viewModel.startForwardRecording()
        assertEquals(
            "a stale backstop Job from the first (thrown) dispatch must not release the second dispatch's guard early",
            2,
            dispatchCount,
        )
    }

    @Test
    fun `retrying startForwardRecording from Error state is guarded against double tap and dispatches exactly once`() = runTest {
        val forwardRecordingState = MutableStateFlow<ForwardRecordingState>(
            ForwardRecordingState.Error(ForwardRecordingFailureReason.SINK_OPEN_FAILED, "failed"),
        )
        var dispatchCount = 0
        val viewModel = newForwardViewModel(forwardRecordingState = forwardRecordingState) { dispatchCount++ }

        // Double tap on Retry button in error notice
        viewModel.startForwardRecording()
        viewModel.startForwardRecording()

        assertEquals(
            "retrying from Error state must also guard against in-flight double tap and dispatch exactly once",
            1,
            dispatchCount,
        )

        // Real state transition arrives
        forwardRecordingState.value = ForwardRecordingState.Recording(displayName = "rec_retry.m4a", bytesWritten = 0)
        runCurrent()

        // Guard is released by recording state
        forwardRecordingState.value = ForwardRecordingState.Success(displayName = "rec_retry.m4a", bytesWritten = 50)
        runCurrent()
        forwardRecordingState.value = ForwardRecordingState.Idle
        runCurrent()

        viewModel.startForwardRecording()
        assertEquals(2, dispatchCount)
    }
}
