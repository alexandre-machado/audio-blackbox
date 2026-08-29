package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.CaptureErrorReason
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.audio.QualityPreset
import cc.machado.audioblackbox.export.ExportFailureReason
import cc.machado.audioblackbox.export.ExportState
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
 * All constructor collaborators ([CaptureState] source, the buffered-duration poll, the real
 * [ExportState] source, and the start/stop/save dispatch callbacks) are already injectable via
 * constructor defaults, so no production seam was needed to reach the ViewModel at the instance
 * level. Every test injects its own `exportState` `MutableStateFlow` rather than relying on the
 * constructor's default ([cc.machado.audioblackbox.service.RecorderService.exportState]) -- that
 * default is a companion-object singleton shared with every other consumer in this test JVM
 * process (real production code, same as `RecorderService.engine`), and this suite mutates
 * `ExportState` directly, so sharing it here would leak state across tests.
 *
 * [DashboardViewModel.uiState] is built on `viewModelScope`, which resolves to
 * `Dispatchers.Main.immediate`; [setUp]/[tearDown] install a [StandardTestDispatcher] as Main so
 * every test drives that scope's virtual time explicitly (via `runCurrent`) rather than relying on
 * real delays -- no `sleep`, no flakiness.
 *
 * ## Ported from issue #41/PR #42 for issue #40 (`@techlead` adjudication on PR #43)
 * PR #42 landed on `main` while PR #43 was rewriting [SaveUiState] from a single
 * `Idle | Requested(minutes)` pair (only ever meaning "the intent was sent") into a real mirror of
 * [ExportState] -- `Idle | Exporting | Success | Error` -- so every test below that touched
 * `SaveUiState.Requested` needed re-deriving against the new model, not just a mechanical rename.
 * Accounting for every one of PR #42's 10 tests, since dropping coverage silently is exactly what
 * `@techlead` is blocking on:
 *   - the 4 `toggleEngine` tests: **kept unchanged** -- `SaveUiState` never enters into them.
 *   - `requestSave dispatches ... transitions to Requested`: **ported** as
 *     `requestSave dispatches the save intent, and the real ExportState.Exporting becomes
 *     visible once RecorderService's round trip lands` -- the meaningful assertion (a dispatched
 *     save eventually becomes visible as an in-progress state on `uiState`) still holds, now
 *     against the real `ExportState` this PR wires in rather than a same-tick local flag.
 *   - `requestSave silently ignores a disabled option`: **kept**, same assertion, now with an
 *     explicit injected `exportState`.
 *   - `requestSave auto-hides the notice after the visible window elapses`: **dropped, and this
 *     is a genuine behavior change, not a quiet coverage loss.** `SAVE_NOTICE_VISIBLE_MILLIS` and
 *     the ViewModel-owned reset coroutine it tested no longer exist -- issue #40 item 2 moved
 *     outcome-visibility timing to [cc.machado.audioblackbox.service.RecorderService]'s own
 *     `EXPORT_OUTCOME_VISIBLE_MILLIS`/`exportEngine.acknowledgeTerminalState()`, which is
 *     service-owned and out of this ViewModel's reach entirely (this ViewModel only ever sees
 *     whatever `ExportState` the service's `StateFlow` hands it). That timing lives server-side
 *     now and needs its own (already-existing, in `RecorderNotification`/`RecorderService`
 *     coverage) test, not a ViewModel one.
 *   - `dismissSaveNotice clears the notice immediately, ahead of the auto-hide timeout`:
 *     **ported** as `dismissSaveNotice clears a Success notice immediately`, replacing the fake
 *     `Requested` state with a real `ExportState.Success`.
 *   - `dismissSaveNotice's Idle survives both the stale auto-hide timer and a later unrelated
 *     capture-state change`: **ported**, minus the now-nonexistent auto-hide-timer half (see
 *     above), keeping exactly the sticky-state assertion `@techlead` called out by name: an
 *     unrelated `CaptureState` transition must never resurrect a dismissed outcome. This is the
 *     same regression class as issue #30/PR #28 round 3.
 *   - `uiState reflects buffered-duration and capture-state changes independently`: **kept
 *     unchanged** -- `SaveUiState` never enters into it either.
 *
 * [DashboardViewModelDoubleTapTest] (added by this same PR) is a separate concern -- the
 * *dispatch-guard* behavior of a rapid double-tap -- and does not overlap any test here: nothing
 * in this file calls `requestSave` twice.
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

    // ---- requestSave: dispatches, and the real ExportState becomes visible on uiState ----

    // ---- requestSave: dispatches, and the real ExportState becomes visible on uiState ----

    @Test
    fun `requestSave dispatches the save intent, and the real ExportState Exporting becomes visible once RecorderService's round trip lands`() = runTest(testDispatcher) {
        var dispatched = 0
        val exportState = MutableStateFlow<ExportState>(ExportState.Idle)
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            bufferedDurationMillisProvider = { 30 * 60_000L }, // buffer full at capacity
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = exportState,
            onSaveIntent = { dispatched++ },
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.requestSave()
        runCurrent()

        assertEquals(1, dispatched)
        // The dispatch alone does not flip the screen's state -- unlike the old fake `Requested`,
        // there is nothing to show yet until RecorderService's own async round trip actually
        // starts the export (issue #40 item 2: no more "intent sent" placeholder state).
        assertEquals(SaveUiState.Idle, observed.last().saveState)

        // Simulates RecorderService's exportState StateFlow (published from its companion, see
        // issue #40 item 2) actually transitioning once the export starts.
        exportState.value = ExportState.Exporting
        runCurrent()

        assertEquals(SaveUiState.Exporting, observed.last().saveState)

        job.cancel()
    }

    @Test
    fun `requestSave silently ignores when buffer is empty -- no dispatch, no state change`() = runTest(testDispatcher) {
        var dispatched = 0
        val exportState = MutableStateFlow<ExportState>(ExportState.Idle)
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            bufferedDurationMillisProvider = { 0L }, // nothing buffered -- save disabled
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = exportState,
            onSaveIntent = { dispatched++ },
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        vm.requestSave()
        runCurrent()

        assertEquals(0, dispatched)
        assertEquals(SaveUiState.Idle, observed.last().saveState)

        job.cancel()
    }

    // ---- dismissSaveNotice: clears the notice, and a later unrelated emission must not
    // resurrect it -- the exact sticky-state bug class from issue #30 / PR #28 round 3. ----

    @Test
    fun `dismissSaveNotice clears a Success notice immediately`() = runTest(testDispatcher) {
        val exportState = MutableStateFlow<ExportState>(ExportState.Idle)
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            bufferedDurationMillisProvider = { 30 * 60_000L },
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = exportState,
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        val success = ExportState.Success(displayName = "blackbox_2026-08-21_10-00-00_30min.m4a", bytesWritten = 1234)
        exportState.value = success
        runCurrent()
        assertEquals(SaveUiState.Success("blackbox_2026-08-21_10-00-00_30min.m4a"), observed.last().saveState)

        vm.dismissSaveNotice()
        runCurrent()

        assertEquals(SaveUiState.Idle, observed.last().saveState)

        job.cancel()
    }

    @Test
    fun `dismissSaveNotice's Idle survives a later unrelated capture-state change`() = runTest(testDispatcher) {
        val captureState = MutableStateFlow<CaptureState>(CaptureState.Recording)
        val exportState = MutableStateFlow<ExportState>(ExportState.Idle)
        val vm = DashboardViewModel(
            captureState = captureState,
            bufferedDurationMillisProvider = { 30 * 60_000L },
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = exportState,
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        val success = ExportState.Success(displayName = "blackbox_2026-08-21_10-00-00_30min.m4a", bytesWritten = 1234)
        exportState.value = success
        runCurrent()
        assertEquals(SaveUiState.Success("blackbox_2026-08-21_10-00-00_30min.m4a"), observed.last().saveState)

        vm.dismissSaveNotice()
        runCurrent()
        assertEquals(
            "dismiss must clear the notice on the live uiState, not just an internal field",
            SaveUiState.Idle,
            observed.last().saveState,
        )

        // An unrelated emission (capture state flips, nothing to do with saving) must not carry
        // the dismissed outcome back onto the screen either -- the exact sticky-state bug class
        // that hit issue #30/PR #28 round 3: a broken guard here would resurrect Success on any
        // later, unrelated recomposition rather than staying cleared.
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
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = MutableStateFlow(ExportState.Idle),
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

    // ---- uiState.qualityPreset: wired through the real combine chain, not a hand-built fixture
    // (issue #206, `@rev` finding on PR #207) ----

    @Test
    fun `uiState qualityPreset reflects emissions from the service's qualityPresetFlow`() = runTest(testDispatcher) {
        val qualityPresetFlow = MutableStateFlow(QualityPreset.VOICE)
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Idle),
            bufferedDurationMillisProvider = { 0L },
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = MutableStateFlow(ExportState.Idle),
            qualityPresetFlow = qualityPresetFlow,
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        assertEquals(QualityPreset.VOICE, observed.last().qualityPreset)

        qualityPresetFlow.value = QualityPreset.HIGH_FIDELITY
        runCurrent()

        assertEquals(
            "uiState.qualityPreset must track qualityPresetFlow through the live combine chain",
            QualityPreset.HIGH_FIDELITY,
            observed.last().qualityPreset,
        )

        job.cancel()
    }

    // ---- The finding-1 regression test: the frozen error snapshot must not drift with an
    // unrelated uiState tick while the error notice is showing (issue #206, `@rev` finding on
    // PR #207 -- this test fails against the pre-fix code, where SaveUiState.Error carried no
    // frozen fields and the screen re-derived "at failure" telemetry live from uiState on every
    // recomposition). ----

    @Test
    fun `SaveUiState Error's frozen bufferedMillis and timestamp survive an unrelated uiState tick`() = runTest(testDispatcher) {
        var bufferedMillis = 12 * 60_000L
        val exportState = MutableStateFlow<ExportState>(ExportState.Idle)
        val vm = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            bufferedDurationMillisProvider = { bufferedMillis },
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = exportState,
            qualityPresetFlow = MutableStateFlow(QualityPreset.VOICE),
            tickMillis = 100L,
            nowMillisProvider = { 1_755_000_000_000L },
        )
        val observed = mutableListOf<DashboardUiState>()
        val job = launch { vm.uiState.collect { observed += it } }
        runCurrent()

        // The failure happens with 12 minutes buffered.
        exportState.value = ExportState.Error(ExportFailureReason.WRITE_FAILED, "disk full")
        runCurrent()

        val errorAtFailure = observed.last().saveState
        assertTrue(errorAtFailure is SaveUiState.Error)
        errorAtFailure as SaveUiState.Error
        assertEquals(1_755_000_000_000L, errorAtFailure.timestampMillis)
        assertEquals(12 * 60_000L, errorAtFailure.bufferedMillis)

        // Capture keeps running after the failure (this PR's whole point): the buffer keeps
        // filling, and bufferedMillisFlow's own periodic tick fires an unrelated uiState
        // recomposition two minutes later, well after the error is already on screen.
        bufferedMillis = 14 * 60_000L
        advanceTimeBy(100L)
        runCurrent()

        val errorAfterUnrelatedTick = observed.last().saveState
        assertTrue(errorAfterUnrelatedTick is SaveUiState.Error)
        errorAfterUnrelatedTick as SaveUiState.Error
        assertEquals(
            "the buffered-audio figure in an already-shown error notice must stay frozen at the " +
                "instant of failure, not drift to whatever is buffered now",
            12 * 60_000L,
            errorAfterUnrelatedTick.bufferedMillis,
        )
        assertEquals(
            "the timestamp in an already-shown error notice must stay frozen at the instant of " +
                "failure, not drift to \"now\"",
            1_755_000_000_000L,
            errorAfterUnrelatedTick.timestampMillis,
        )

        // Meanwhile the live telemetry elsewhere on the dashboard (uiState.bufferedMillis itself)
        // is correctly still ticking -- this test is about the frozen snapshot inside the error
        // notice, not about breaking the live buffer readout.
        assertEquals(14 * 60_000L, observed.last().bufferedMillis)

        job.cancel()
    }
}
