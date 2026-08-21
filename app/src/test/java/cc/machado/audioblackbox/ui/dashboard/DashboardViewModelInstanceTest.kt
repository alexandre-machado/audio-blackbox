package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.CaptureErrorReason
import cc.machado.audioblackbox.audio.CaptureState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
 * Instance-behaviour tests for [DashboardViewModel] -- the effects a user tap actually triggers
 * ([toggleEngine], [DashboardViewModel.requestSave], [DashboardViewModel.dismissSaveNotice]) and
 * the live [DashboardViewModel.uiState] combine, as opposed to [DashboardViewModelTest] /
 * [DashboardFormatTest], which pin the companion object's pure mapping functions (issue #41,
 * following up on `@rev`'s finding on PR #38).
 *
 * All constructor collaborators ([CaptureState] source, the buffered-duration poll, and the
 * start/stop/save dispatch callbacks) are already injectable via constructor defaults, so no
 * production seam was needed to reach the ViewModel at the instance level.
 *
 * [DashboardViewModel.uiState] is built on `viewModelScope`, which resolves to
 * `Dispatchers.Main.immediate`; [setUp]/[tearDown] install a [StandardTestDispatcher] as Main so
 * every test drives that scope's virtual time explicitly (via `runCurrent`/`advanceTimeBy`) rather
 * than relying on real delays -- no `sleep`, no flakiness.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelInstanceTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- toggleEngine: asserted against which callback actually fired, not a re-statement of
    // the ViewModel's own `when` -- a swapped start/stop wire-up would fail these immediately. ----

    @Test
    fun `toggleEngine starts the engine from Idle and never calls stop`() {
        var startCalls = 0
        var stopCalls = 0
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            onStartEngine = { startCalls++ },
            onStopEngine = { stopCalls++ },
        )

        vm.toggleEngine()

        assertEquals(1, startCalls)
        assertEquals(0, stopCalls)
    }

    @Test
    fun `toggleEngine starts the engine from an Error state and never calls stop`() {
        var startCalls = 0
        var stopCalls = 0
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(
                CaptureState.Error(CaptureErrorReason.READ_DEAD_OBJECT, "boom"),
            ),
            onStartEngine = { startCalls++ },
            onStopEngine = { stopCalls++ },
        )

        vm.toggleEngine()

        assertEquals(1, startCalls)
        assertEquals(0, stopCalls)
    }

    @Test
    fun `toggleEngine stops the engine from Recording and never calls start`() {
        var startCalls = 0
        var stopCalls = 0
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            onStartEngine = { startCalls++ },
            onStopEngine = { stopCalls++ },
        )

        vm.toggleEngine()

        assertEquals(0, startCalls)
        assertEquals(1, stopCalls)
    }

    @Test
    fun `toggleEngine stops the engine from Paused and never calls start`() {
        var startCalls = 0
        var stopCalls = 0
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Paused),
            onStartEngine = { startCalls++ },
            onStopEngine = { stopCalls++ },
        )

        vm.toggleEngine()

        assertEquals(0, startCalls)
        assertEquals(1, stopCalls)
    }

    // ---- requestSave: transitions SaveUiState and gates on the option actually being enabled ----

    @Test
    fun `requestSave dispatches the save intent and transitions to Requested when the option is enabled`() = runTest(testDispatcher) {
        val dispatched = mutableListOf<Int>()
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            bufferedDurationMillisProvider = { 30 * 60_000L }, // buffer full at capacity
            capacityMinutes = 30,
            onSaveIntent = { minutes -> dispatched += minutes },
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.requestSave(30)
        runCurrent()

        assertEquals(listOf(30), dispatched)
        assertEquals(SaveUiState.Requested(30), observed.last().saveState)

        job.cancel()
    }

    @Test
    fun `requestSave silently ignores a disabled option -- no dispatch, no state change`() = runTest(testDispatcher) {
        val dispatched = mutableListOf<Int>()
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            bufferedDurationMillisProvider = { 0L }, // nothing buffered -- every option disabled
            capacityMinutes = 30,
            onSaveIntent = { minutes -> dispatched += minutes },
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.requestSave(30)
        runCurrent()

        assertTrue("expected no dispatch for a disabled option, got $dispatched", dispatched.isEmpty())
        assertEquals(SaveUiState.Idle, observed.last().saveState)

        job.cancel()
    }

    @Test
    fun `requestSave auto-hides the notice after the visible window elapses, driven by virtual time`() = runTest(testDispatcher) {
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            bufferedDurationMillisProvider = { 30 * 60_000L },
            capacityMinutes = 30,
            onSaveIntent = {},
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.requestSave(30)
        runCurrent()
        assertEquals(SaveUiState.Requested(30), observed.last().saveState)

        // SAVE_NOTICE_VISIBLE_MILLIS (6_000L in DashboardViewModel) elapses -- the scheduled
        // reset coroutine fires and clears the notice on its own, without another user action.
        advanceTimeBy(6_001L)
        runCurrent()

        assertEquals(SaveUiState.Idle, observed.last().saveState)

        job.cancel()
    }

    // ---- dismissSaveNotice: clears the notice, and a later unrelated emission must not
    // resurrect it -- the exact sticky-state bug class from issue #30 / PR #28 round 3. ----

    @Test
    fun `dismissSaveNotice clears the notice immediately, ahead of the auto-hide timeout`() = runTest(testDispatcher) {
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            bufferedDurationMillisProvider = { 30 * 60_000L },
            capacityMinutes = 30,
            onSaveIntent = {},
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.requestSave(30)
        runCurrent()
        assertEquals(SaveUiState.Requested(30), observed.last().saveState)

        vm.dismissSaveNotice()
        runCurrent()

        assertEquals(SaveUiState.Idle, observed.last().saveState)

        job.cancel()
    }

    @Test
    fun `dismissSaveNotice's Idle survives both the stale auto-hide timer and a later unrelated capture-state change`() = runTest(testDispatcher) {
        val captureState = MutableStateFlow<CaptureState>(CaptureState.Recording)
        val vm = DashboardViewModel(
            captureState = captureState,
            bufferedDurationMillisProvider = { 30 * 60_000L },
            capacityMinutes = 30,
            onSaveIntent = {},
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.requestSave(30)
        runCurrent()
        assertEquals(SaveUiState.Requested(30), observed.last().saveState)

        vm.dismissSaveNotice()
        runCurrent()
        assertEquals(
            "dismiss must clear the notice on the live uiState, not just the internal field",
            SaveUiState.Idle,
            observed.last().saveState,
        )

        // The reset coroutine scheduled by requestSave(30) above is still pending; if it did not
        // guard against a state that has since moved on, it would stomp Idle back to Idle here
        // (harmless) -- but a broken guard that instead *set* Requested(30) unconditionally would
        // resurrect the dismissed notice. This is exactly what must not happen.
        advanceTimeBy(6_001L)
        runCurrent()
        assertEquals(SaveUiState.Idle, observed.last().saveState)

        // An unrelated emission (capture state flips, nothing to do with saving) must not carry a
        // stale Requested value back onto the screen either.
        captureState.value = CaptureState.Paused
        runCurrent()
        assertEquals(CaptureStatus.Paused, observed.last().captureStatus)
        assertEquals(
            "an unrelated capture-state transition resurrected the dismissed save notice",
            SaveUiState.Idle,
            observed.last().saveState,
        )

        job.cancel()
    }

    // ---- the live uiState combine: capture state and buffered duration change independently ----

    @Test
    fun `uiState reflects buffered-duration and capture-state changes independently`() = runTest(testDispatcher) {
        val captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
        var bufferedMillis = 0L
        val vm = DashboardViewModel(
            captureState = captureState,
            bufferedDurationMillisProvider = { bufferedMillis },
            capacityMinutes = 30,
            tickMillis = 100L,
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        assertEquals(1, observed.size)
        assertEquals(CaptureStatus.Idle, observed[0].captureStatus)
        assertEquals(0L, observed[0].bufferedMillis)

        // Buffered duration alone changes (the poll ticks); capture state does not.
        bufferedMillis = 5 * 60_000L
        advanceTimeBy(100L)
        runCurrent()

        val afterBufferChange = observed.last()
        assertEquals(CaptureStatus.Idle, afterBufferChange.captureStatus)
        assertEquals(5 * 60_000L, afterBufferChange.bufferedMillis)

        // Capture state alone changes (a real transition); buffered duration does not.
        captureState.value = CaptureState.Recording
        runCurrent()

        val afterCaptureChange = observed.last()
        assertEquals(CaptureStatus.Recording, afterCaptureChange.captureStatus)
        assertEquals(5 * 60_000L, afterCaptureChange.bufferedMillis)

        job.cancel()
    }
}
