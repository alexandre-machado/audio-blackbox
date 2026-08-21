package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.export.ExportState
import cc.machado.audioblackbox.settings.InMemoryRetentionWindowPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The discard-warning flow issue #45 exists for: changing the retention window while real audio
 * is buffered must never apply silently. Each test here answers "what would have to actually
 * break in [DashboardViewModel.selectRetentionWindow]/[DashboardViewModel.confirmRetentionWindowChange]/
 * [DashboardViewModel.cancelRetentionWindowChange] for this to fail" -- none of these re-derive
 * their expectation through the production `when`/mapping, they assert on the exact side effects
 * (whether [onRebuildEngine]/[retentionWindowPreferences] were actually invoked, and with what).
 *
 * `captureState`/`onStopEngine`/`onRebuildEngine`/`retentionWindowPreferences` are all constructor
 * seams already exposed by [DashboardViewModel] -- no production code changed to make these
 * testable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelRetentionWindowTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `selecting a new window while Idle applies immediately -- nothing buffered, nothing to lose`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val rebuildCalls = mutableListOf<Int>()
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = MutableStateFlow(ExportState.Idle),
            retentionWindowPreferences = preferences,
            onRebuildEngine = { minutes -> rebuildCalls += minutes; true },
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.selectRetentionWindow(60)
        runCurrent()

        assertEquals(listOf(60), rebuildCalls)
        assertEquals(60, preferences.currentBufferDurationMinutes())
        assertNull(
            "an Idle-engine change must never show the discard dialog",
            observed.last().retentionSection.pendingConfirmationMinutes,
        )

        job.cancel()
    }

    @Test
    fun `selecting a new window while Recording does not persist or rebuild -- it only surfaces the confirmation`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val rebuildCalls = mutableListOf<Int>()
        var stopCalls = 0
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = MutableStateFlow(ExportState.Idle),
            retentionWindowPreferences = preferences,
            onStopEngine = { stopCalls++ },
            onRebuildEngine = { minutes -> rebuildCalls += minutes; true },
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.selectRetentionWindow(60)
        runCurrent()

        assertTrue(
            "selecting a window while Recording must not rebuild before confirmation",
            rebuildCalls.isEmpty(),
        )
        assertEquals(0, stopCalls)
        assertEquals(30, preferences.currentBufferDurationMinutes())
        assertEquals(60, observed.last().retentionSection.pendingConfirmationMinutes)

        job.cancel()
    }

    @Test
    fun `confirming the change stops the engine, waits for Idle, then persists and rebuilds`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val rebuildCalls = mutableListOf<Int>()
        var stopCalls = 0
        val captureState = MutableStateFlow<CaptureState>(CaptureState.Recording)
        val vm = DashboardViewModel(
            captureState = captureState,
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = MutableStateFlow(ExportState.Idle),
            retentionWindowPreferences = preferences,
            onStopEngine = { stopCalls++ },
            onRebuildEngine = { minutes -> rebuildCalls += minutes; true },
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.selectRetentionWindow(60)
        runCurrent()
        assertEquals(60, observed.last().retentionSection.pendingConfirmationMinutes)

        vm.confirmRetentionWindowChange()
        runCurrent()

        // onStopEngine fired, but the real engine (out of this test's reach) has not actually
        // reached Idle yet -- confirmRetentionWindowChange must wait for that transition, not
        // apply the moment the button is tapped.
        assertEquals(1, stopCalls)
        assertTrue(
            "must not persist/rebuild before captureState actually reaches Idle",
            rebuildCalls.isEmpty(),
        )
        assertEquals(30, preferences.currentBufferDurationMinutes())
        assertNull(
            "the dialog itself must close as soon as the user confirms, independent of the engine",
            observed.last().retentionSection.pendingConfirmationMinutes,
        )

        // The engine actually finishes stopping (what onStopEngine's real RecorderService wiring
        // would eventually cause).
        captureState.value = CaptureState.Idle
        runCurrent()

        assertEquals(listOf(60), rebuildCalls)
        assertEquals(60, preferences.currentBufferDurationMinutes())

        job.cancel()
    }

    @Test
    fun `cancelling the change leaves the engine and the persisted value untouched`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val rebuildCalls = mutableListOf<Int>()
        var stopCalls = 0
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = MutableStateFlow(ExportState.Idle),
            retentionWindowPreferences = preferences,
            onStopEngine = { stopCalls++ },
            onRebuildEngine = { minutes -> rebuildCalls += minutes; true },
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.selectRetentionWindow(60)
        runCurrent()
        assertEquals(60, observed.last().retentionSection.pendingConfirmationMinutes)

        vm.cancelRetentionWindowChange()
        runCurrent()

        assertNull(observed.last().retentionSection.pendingConfirmationMinutes)
        assertEquals(0, stopCalls)
        assertTrue(rebuildCalls.isEmpty())
        assertEquals(30, preferences.currentBufferDurationMinutes())

        job.cancel()
    }

    @Test
    fun `selecting the already-current window is a no-op`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val rebuildCalls = mutableListOf<Int>()
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = MutableStateFlow(ExportState.Idle),
            retentionWindowPreferences = preferences,
            onRebuildEngine = { minutes -> rebuildCalls += minutes; true },
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.selectRetentionWindow(30)
        runCurrent()

        assertTrue("selecting the current value must not rebuild or persist anything", rebuildCalls.isEmpty())
        assertEquals(30, preferences.currentBufferDurationMinutes())
        assertNull(observed.last().retentionSection.pendingConfirmationMinutes)

        job.cancel()
    }

    @Test
    fun `a second tap while a confirmation is already pending does not swap which change is pending`() = runTest(testDispatcher) {
        val preferences = InMemoryRetentionWindowPreferences(initialMinutes = 30)
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = MutableStateFlow(ExportState.Idle),
            retentionWindowPreferences = preferences,
            onRebuildEngine = { true },
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.selectRetentionWindow(60)
        runCurrent()
        assertEquals(60, observed.last().retentionSection.pendingConfirmationMinutes)

        vm.selectRetentionWindow(5)
        runCurrent()

        assertEquals(
            "a stray second tap while the dialog is up must not replace the pending request",
            60,
            observed.last().retentionSection.pendingConfirmationMinutes,
        )

        job.cancel()
    }
}
