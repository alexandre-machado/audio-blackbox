package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.export.ErrorLogEntry
import cc.machado.audioblackbox.export.ErrorLogSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers issue #346's own regression-test list: the error-list card is hidden with zero errors
 * and shown with one, pagination across a page boundary, and persistence across a simulated
 * restart (a fresh [DashboardViewModel] instance observing a durable log that already existed
 * before it was constructed).
 *
 * `errorLogReader`/`errorLogClearer` are injected directly (bypassing the real
 * `Dispatchers.IO`/on-disk file the production defaults use) so every test here runs
 * deterministically under [StandardTestDispatcher]'s virtual time -- see those constructor
 * parameters' own doc on [DashboardViewModel] for why. `errorLogUiState` is built with
 * `SharingStarted.WhileSubscribed` (same shape as `uiState` itself, see
 * [DashboardViewModelInstanceTest]'s doc), so every test needs a live `launch { ... collect {} }`
 * before it does anything at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelErrorLogTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun entry(reason: String, severity: ErrorLogSeverity = ErrorLogSeverity.ERROR) = ErrorLogEntry(
        timestampMillis = 1_000L,
        component = "ExportEngine",
        reason = reason,
        message = "message for $reason",
        stackTrace = null,
        severity = severity,
    )

    @Test
    fun `hasVisibleErrors is false when the durable log is empty`() = runTest {
        val vm = DashboardViewModel(errorLogReader = { emptyList() })

        val job = launch { vm.errorLogUiState.collect {} }
        runCurrent()

        assertFalse(vm.errorLogUiState.value.hasVisibleErrors)
        job.cancel()
    }

    @Test
    fun `hasVisibleErrors becomes true once at least one ERROR-severity entry exists`() = runTest {
        val vm = DashboardViewModel(errorLogReader = { listOf(entry("SINK_OPEN_FAILED")) })

        val job = launch { vm.errorLogUiState.collect {} }
        runCurrent()

        assertTrue(vm.errorLogUiState.value.hasVisibleErrors)
        job.cancel()
    }

    @Test
    fun `an AUDIT-only log does not make the card appear`() = runTest {
        val vm = DashboardViewModel(
            errorLogReader = { listOf(entry("MUXER_STOP_RECOVERED", ErrorLogSeverity.AUDIT)) },
        )

        val job = launch { vm.errorLogUiState.collect {} }
        runCurrent()

        assertFalse(
            "An AUDIT-only log must not render as though a recording failed",
            vm.errorLogUiState.value.hasVisibleErrors,
        )
        job.cancel()
    }

    @Test
    fun `nextErrorLogPage advances exactly one page at the boundary, not an off-by-one`() = runTest {
        val entries = (1..45).map { entry("REASON_$it") }
        val vm = DashboardViewModel(errorLogReader = { entries })

        val job = launch { vm.errorLogUiState.collect {} }
        runCurrent()

        vm.openErrorLog()
        runCurrent()
        assertEquals(0, vm.errorLogUiState.value.page)
        assertEquals(20, vm.errorLogUiState.value.pageEntries.size)
        assertEquals("REASON_1", vm.errorLogUiState.value.pageEntries.first().reason)

        vm.nextErrorLogPage()
        runCurrent()
        assertEquals(1, vm.errorLogUiState.value.page)
        assertEquals("REASON_21", vm.errorLogUiState.value.pageEntries.first().reason)

        vm.nextErrorLogPage()
        runCurrent()
        // Final, partial page: only 5 of the 45 entries remain.
        assertEquals(2, vm.errorLogUiState.value.page)
        assertEquals(5, vm.errorLogUiState.value.pageEntries.size)

        // One more call past the last page must not go out of range.
        vm.nextErrorLogPage()
        runCurrent()
        assertEquals(2, vm.errorLogUiState.value.page)

        job.cancel()
    }

    @Test
    fun `previousErrorLogPage never goes below page zero`() = runTest {
        val entries = (1..45).map { entry("REASON_$it") }
        val vm = DashboardViewModel(errorLogReader = { entries })

        val job = launch { vm.errorLogUiState.collect {} }
        runCurrent()
        vm.openErrorLog()
        runCurrent()

        vm.previousErrorLogPage()
        runCurrent()

        assertEquals(0, vm.errorLogUiState.value.page)
        job.cancel()
    }

    @Test
    fun `an already-existing durable log is visible from the very first read -- persistence across a simulated restart`() = runTest {
        // Simulates process death/restart: the log already has an entry on disk *before* this
        // (freshly constructed) ViewModel instance -- standing in for
        // `AudioBlackboxApplication.onCreate`/`ErrorLogFileHolder` having already been populated
        // by a previous process -- ever reads it.
        val preExistingLog = listOf(entry("AUDIO_RECORD_INIT_FAILED"))
        val vm = DashboardViewModel(errorLogReader = { preExistingLog })

        val job = launch { vm.errorLogUiState.collect {} }
        runCurrent()

        assertTrue(vm.errorLogUiState.value.hasVisibleErrors)
        assertEquals(1, vm.errorLogUiState.value.entries.size)
        job.cancel()
    }

    @Test
    fun `confirmClearErrorLog invokes the injected clearer and hides the confirmation`() = runTest {
        var clearCalls = 0
        val vm = DashboardViewModel(
            errorLogReader = { listOf(entry("SINK_OPEN_FAILED")) },
            errorLogClearer = { clearCalls++ },
        )
        val job = launch { vm.errorLogUiState.collect {} }
        runCurrent()

        vm.openErrorLog()
        vm.requestClearErrorLog()
        runCurrent()
        assertTrue(vm.errorLogUiState.value.isClearConfirmVisible)

        vm.confirmClearErrorLog()
        runCurrent()

        assertEquals(1, clearCalls)
        assertFalse(vm.errorLogUiState.value.isClearConfirmVisible)
        job.cancel()
    }
}
