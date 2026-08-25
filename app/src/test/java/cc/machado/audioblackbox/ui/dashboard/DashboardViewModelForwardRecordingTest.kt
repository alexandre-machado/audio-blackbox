package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.export.ExportState
import cc.machado.audioblackbox.export.ForwardRecordingFailureReason
import cc.machado.audioblackbox.export.ForwardRecordingState
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Non-vacuous instance-level tests for forward continuous recording actions on [DashboardViewModel] (issue #55).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelForwardRecordingTest {

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
    fun `startForwardRecording dispatches when forward recording is idle`() {
        val forwardState = MutableStateFlow<ForwardRecordingState>(ForwardRecordingState.Idle)
        var startDispatched = false
        var startFromOldestArg = false

        val viewModel = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            bufferedDurationMillisProvider = { 10_000L },
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = MutableStateFlow(ExportState.Idle),
            forwardRecordingState = forwardState,
            audioConfigProvider = { AudioConfig() },
            onStartForwardRecording = { startFromOldest ->
                startDispatched = true
                startFromOldestArg = startFromOldest
            },
            onStopForwardRecording = {},
        )

        viewModel.startForwardRecording(startFromOldest = false)
        assertTrue("startForwardRecording should invoke onStartForwardRecording callback", startDispatched)
        assertFalse("startFromOldest should be false", startFromOldestArg)
    }

    @Test
    fun `startForwardRecording is a no-op when already recording`() {
        val forwardState = MutableStateFlow<ForwardRecordingState>(
            ForwardRecordingState.Recording("blackbox_2026-08-25_14-30-00_forward.m4a", 1000L)
        )
        var startDispatched = false

        val viewModel = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            bufferedDurationMillisProvider = { 10_000L },
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = MutableStateFlow(ExportState.Idle),
            forwardRecordingState = forwardState,
            audioConfigProvider = { AudioConfig() },
            onStartForwardRecording = { startDispatched = true },
            onStopForwardRecording = {},
        )

        viewModel.startForwardRecording(startFromOldest = false)
        assertFalse("startForwardRecording should be ignored if already recording", startDispatched)
    }

    @Test
    fun `stopForwardRecording dispatches when forward recording is active`() {
        val forwardState = MutableStateFlow<ForwardRecordingState>(
            ForwardRecordingState.Recording("blackbox_2026-08-25_14-30-00_forward.m4a", 1000L)
        )
        var stopDispatched = false

        val viewModel = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            bufferedDurationMillisProvider = { 10_000L },
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = MutableStateFlow(ExportState.Idle),
            forwardRecordingState = forwardState,
            audioConfigProvider = { AudioConfig() },
            onStartForwardRecording = {},
            onStopForwardRecording = { stopDispatched = true },
        )

        viewModel.stopForwardRecording()
        assertTrue("stopForwardRecording should invoke onStopForwardRecording callback", stopDispatched)
    }

    @Test
    fun `stopForwardRecording is a no-op when forward recording is idle`() {
        val forwardState = MutableStateFlow<ForwardRecordingState>(ForwardRecordingState.Idle)
        var stopDispatched = false

        val viewModel = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            bufferedDurationMillisProvider = { 10_000L },
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = MutableStateFlow(ExportState.Idle),
            forwardRecordingState = forwardState,
            audioConfigProvider = { AudioConfig() },
            onStartForwardRecording = {},
            onStopForwardRecording = { stopDispatched = true },
        )

        viewModel.stopForwardRecording()
        assertFalse("stopForwardRecording should be ignored if not currently recording", stopDispatched)
    }

    @Test
    fun `dismissForwardRecordingNotice clears terminal success state`() = runTest(testDispatcher) {
        val forwardState = MutableStateFlow<ForwardRecordingState>(ForwardRecordingState.Idle)
        val viewModel = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            bufferedDurationMillisProvider = { 10_000L },
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = MutableStateFlow(ExportState.Idle),
            forwardRecordingState = forwardState,
            audioConfigProvider = { AudioConfig() },
        )

        val observed = mutableListOf<DashboardUiState>()
        val job = launch { viewModel.uiState.collect { observed += it } }
        runCurrent()

        val successState = ForwardRecordingState.Success("blackbox_2026-08-25_14-30-00_forward.m4a", 1000L)
        forwardState.value = successState
        runCurrent()

        assertEquals(
            ForwardRecordingUiState.Success("blackbox_2026-08-25_14-30-00_forward.m4a", 1000L),
            observed.last().forwardRecordingState,
        )

        viewModel.dismissForwardRecordingNotice()
        runCurrent()

        assertEquals(ForwardRecordingUiState.Idle, observed.last().forwardRecordingState)
        job.cancel()
    }

    @Test
    fun `dismissForwardRecordingNotice clears terminal error state`() = runTest(testDispatcher) {
        val forwardState = MutableStateFlow<ForwardRecordingState>(ForwardRecordingState.Idle)
        val viewModel = DashboardViewModel(
            captureState = MutableStateFlow(CaptureState.Recording),
            bufferedDurationMillisProvider = { 10_000L },
            capacityMinutesFlow = MutableStateFlow(30),
            exportState = MutableStateFlow(ExportState.Idle),
            forwardRecordingState = forwardState,
            audioConfigProvider = { AudioConfig() },
        )

        val observed = mutableListOf<DashboardUiState>()
        val job = launch { viewModel.uiState.collect { observed += it } }
        runCurrent()

        val errorState = ForwardRecordingState.Error(ForwardRecordingFailureReason.WRITE_FAILED, "Disk full")
        forwardState.value = errorState
        runCurrent()

        assertEquals(
            ForwardRecordingUiState.Error(ForwardRecordingFailureReason.WRITE_FAILED, "Disk full"),
            observed.last().forwardRecordingState,
        )

        viewModel.dismissForwardRecordingNotice()
        runCurrent()

        assertEquals(ForwardRecordingUiState.Idle, observed.last().forwardRecordingState)
        job.cancel()
    }
}
