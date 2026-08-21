package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.export.ExportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
        capacityMinutes = 30,
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
}
