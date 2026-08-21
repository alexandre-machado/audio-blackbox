package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.CaptureErrorReason
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.export.ExportState
import kotlinx.coroutines.Dispatchers
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
 * Coverage for issue #46 (the engine control becomes a Material 3 [androidx.compose.material3.Switch],
 * not a Start/Stop button): [DashboardViewModel.mapEngineSwitchState] is the oracle pinning what
 * every [CaptureStatus] renders as, and the instance-level tests below pin the transitional
 * (pending) behaviour that pure function alone can't exercise -- in particular that a failed start
 * never leaves the switch showing "on" (the issue's explicit acceptance criterion).
 *
 * Per this project's established rule, no test here asserts that a collector observes every
 * intermediate [CaptureState] emission -- [MutableStateFlow] conflates by design, so only the
 * *outcome* after a real transition is pinned, never an intermediate step.
 */
class DashboardViewModelEngineSwitchTest {

    // ---- mapEngineSwitchState: the pure oracle, one case per CaptureStatus, not-pending ----

    @Test
    fun `Idle maps to an off, enabled switch`() {
        val switch = DashboardViewModel.mapEngineSwitchState(CaptureStatus.Idle, pending = false)
        assertFalse("Idle must render off", switch.checked)
        assertTrue("Idle must be interactive absent a pending toggle", switch.enabled)
        assertFalse(switch.pending)
        assertFalse(switch.paused)
        assertEquals(null, switch.error)
    }

    @Test
    fun `Recording maps to an on, enabled switch`() {
        val switch = DashboardViewModel.mapEngineSwitchState(CaptureStatus.Recording, pending = false)
        assertTrue("Recording must render on", switch.checked)
        assertTrue(switch.enabled)
        assertFalse(switch.pending)
        assertFalse(switch.paused)
        assertEquals(null, switch.error)
    }

    @Test
    fun `Paused stays checked -- the mode is still engaged -- but is flagged distinctly from Recording`() {
        val switch = DashboardViewModel.mapEngineSwitchState(CaptureStatus.Paused, pending = false)
        assertTrue(
            "Paused must not read as 'off': the engine still holds AudioRecord and will resume " +
                "on its own once the call ends, so flipping the switch off here would be a lie " +
                "about what happens next",
            switch.checked,
        )
        assertTrue(
            "Paused must be visually distinguishable from plain Recording -- this is the flag " +
                "DashboardScreen uses to render different supporting text/color for it",
            switch.paused,
        )
        assertTrue(switch.enabled)
        assertEquals(null, switch.error)
    }

    @Test
    fun `Error maps to an off switch that stays enabled and carries the failure through`() {
        val status = CaptureStatus.Error(CaptureErrorReason.AUDIO_RECORD_INIT_FAILED, "AudioRecord.state = 0")
        val switch = DashboardViewModel.mapEngineSwitchState(status, pending = false)
        assertFalse("a failed engine must not read as 'on'", switch.checked)
        assertTrue(
            "Error must stay actionable, per issue #46 -- the user can flip the switch again to retry",
            switch.enabled,
        )
        assertEquals(status, switch.error)
    }

    // ---- pending: disables the switch without ever moving checked ahead of the real state ----

    @Test
    fun `a pending toggle disables the switch but never moves checked ahead of the real state`() {
        val idlePending = DashboardViewModel.mapEngineSwitchState(CaptureStatus.Idle, pending = true)
        assertFalse(
            "a switch flipped on but not yet confirmed by the real service state must not " +
                "optimistically show 'on' -- that is exactly the 'snaps to on, silently falls " +
                "back' bug issue #46 forbids",
            idlePending.checked,
        )
        assertFalse("disabled while transitioning", idlePending.enabled)
        assertTrue(idlePending.pending)

        val recordingPending = DashboardViewModel.mapEngineSwitchState(CaptureStatus.Recording, pending = true)
        assertTrue("stopping a running engine must not jump to 'off' before it actually stops", recordingPending.checked)
        assertFalse(recordingPending.enabled)
        assertTrue(recordingPending.pending)
    }

    // ---- mapUiState wiring: engineSwitch is derived from the same captureState, not a second value ----

    @Test
    fun `mapUiState derives engineSwitch from the same captureState passed in, including pending`() {
        val state = DashboardViewModel.mapUiState(
            captureState = CaptureState.Idle,
            bufferedMillis = 0L,
            capacityMinutes = 30,
            saveState = SaveUiState.Idle,
            enginePending = true,
        )
        assertEquals(CaptureStatus.Idle, state.captureStatus)
        assertFalse(state.engineSwitch.checked)
        assertFalse(state.engineSwitch.enabled)
        assertTrue(state.engineSwitch.pending)
    }

    // ---- instance-level: toggleEngine's pending flag, reconciled only by the real captureState ----

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        captureState: MutableStateFlow<CaptureState>,
        onStartEngine: () -> Unit = {},
        onStopEngine: () -> Unit = {},
    ) = DashboardViewModel(
        captureState = captureState,
        bufferedDurationMillisProvider = { 0L },
        capacityMinutesFlow = MutableStateFlow(30),
        exportState = MutableStateFlow(ExportState.Idle),
        onStartEngine = onStartEngine,
        onStopEngine = onStopEngine,
    )

    @Test
    fun `toggling from Idle dispatches start and shows a disabled, still-off switch until the real state changes`() = runTest {
        val captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
        var startCount = 0
        val viewModel = newViewModel(captureState, onStartEngine = { startCount++ })
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.toggleEngine()
        runCurrent()

        assertEquals(1, startCount)
        assertFalse("must not optimistically flip to on before the service confirms it", viewModel.uiState.value.engineSwitch.checked)
        assertFalse("must be disabled while the outcome is unknown", viewModel.uiState.value.engineSwitch.enabled)
        assertTrue(viewModel.uiState.value.engineSwitch.pending)

        // The real outcome finally arrives, exactly as RecorderService's async start would deliver it.
        captureState.value = CaptureState.Recording
        runCurrent()

        assertTrue(viewModel.uiState.value.engineSwitch.checked)
        assertTrue(viewModel.uiState.value.engineSwitch.enabled)
        assertFalse(viewModel.uiState.value.engineSwitch.pending)
    }

    @Test
    fun `a failed start clears pending without ever having shown the switch as on`() = runTest {
        val captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
        val viewModel = newViewModel(captureState, onStartEngine = {})
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.toggleEngine()
        runCurrent()
        assertFalse(viewModel.uiState.value.engineSwitch.checked)

        // AudioRecord init failure, surfaced the same way it would be by the real engine.
        captureState.value = CaptureState.Error(CaptureErrorReason.AUDIO_RECORD_INIT_FAILED, "boom")
        runCurrent()

        assertFalse(
            "production behaviour that would have to break for this to fail: a failed start " +
                "transitioning captureState to Error while pending must not leave the switch " +
                "showing 'on' -- the whole bug this pending machinery exists to prevent",
            viewModel.uiState.value.engineSwitch.checked,
        )
        assertTrue("the switch must be usable again once the outcome (even a failure) is known", viewModel.uiState.value.engineSwitch.enabled)
        assertEquals(CaptureErrorReason.AUDIO_RECORD_INIT_FAILED, viewModel.uiState.value.engineSwitch.error?.reason)
    }

    @Test
    fun `a second toggle while one is already pending is ignored`() = runTest {
        val captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
        var startCount = 0
        val viewModel = newViewModel(captureState, onStartEngine = { startCount++ })

        viewModel.toggleEngine()
        viewModel.toggleEngine()
        runCurrent()

        assertEquals(
            "a second flip before the first toggle's real outcome is known must not dispatch " +
                "a second start -- the switch is disabled in the UI for exactly this reason",
            1,
            startCount,
        )
    }

    @Test
    fun `a toggle dispatch that throws does not leave the switch stuck disabled`() = runTest {
        val captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
        var startCount = 0
        val viewModel = newViewModel(
            captureState,
            onStartEngine = {
                startCount++
                error("dispatch boom")
            },
        )
        backgroundScope.launch { viewModel.uiState.collect {} }

        try {
            viewModel.toggleEngine()
        } catch (_: IllegalStateException) {
            // Expected: the throw propagates, same contract as requestSave()'s dispatch guard.
        }
        runCurrent()

        assertEquals(1, startCount)
        assertTrue(
            "a dispatch that never reached the service must not permanently disable the switch",
            viewModel.uiState.value.engineSwitch.enabled,
        )
        assertFalse(viewModel.uiState.value.engineSwitch.pending)
    }

    @Test
    fun `a dispatch that silently never reaches the service is released by the timeout backstop`() = runTest {
        val captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
        var startCount = 0
        val viewModel = newViewModel(captureState, onStartEngine = { startCount++ })
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.toggleEngine()
        runCurrent()
        assertEquals(1, startCount)
        assertTrue(viewModel.uiState.value.engineSwitch.pending)

        // No exception, and captureState never moves -- the Intent silently never reached
        // RecorderService. Only the backstop timeout can recover the switch from this.
        advanceTimeBy(5_001L)
        runCurrent()

        assertFalse(
            "the backstop must release the switch even if the real state never changed",
            viewModel.uiState.value.engineSwitch.pending,
        )
        assertTrue(viewModel.uiState.value.engineSwitch.enabled)

        viewModel.toggleEngine()
        runCurrent()
        assertEquals(
            "the switch must be usable again after the backstop released it",
            2,
            startCount,
        )
    }
}
