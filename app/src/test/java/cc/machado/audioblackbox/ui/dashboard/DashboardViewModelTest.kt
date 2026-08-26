package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.CaptureErrorReason
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.export.ExportFailureReason
import cc.machado.audioblackbox.export.ExportState
import cc.machado.audioblackbox.export.ForwardRecordingFailureReason
import cc.machado.audioblackbox.export.ForwardRecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-mapping tests for [DashboardViewModel]'s pure companion functions -- the exact oracle
 * issue #6 requires: engine state + buffered duration -> the [DashboardUiState] the screen
 * renders. Each test pins an exact expected value (not a re-derivation of the production
 * arithmetic), so a broken mapping -- e.g. Paused rendered as Recording, or a window option
 * enabled when it shouldn't be -- fails these tests, not just an unrelated smoke check.
 *
 * Extended for issue #40: [computeWindowOptions] no longer takes a `capacityMinutes` parameter
 * (every window up to what is buffered is now enabled -- see that function's doc for why capacity
 * doesn't need to gate it separately any more), [mapSaveUiState] is the new oracle for the
 * real-progress mapping, and one `mapUiState` test now exercises a deliberately non-default
 * capacity to prove the mapping isn't hardcoded to 30 anywhere.
 */
class DashboardViewModelTest {

    // ---- mapCaptureStatus: exact 1:1 mapping, including CaptureState.Error's payload ----

    @Test
    fun `mapCaptureStatus maps Idle to CaptureStatus Idle`() {
        assertEquals(CaptureStatus.Idle, DashboardViewModel.mapCaptureStatus(CaptureState.Idle))
    }

    @Test
    fun `mapCaptureStatus maps Recording to CaptureStatus Recording`() {
        assertEquals(CaptureStatus.Recording, DashboardViewModel.mapCaptureStatus(CaptureState.Recording))
    }

    @Test
    fun `mapCaptureStatus maps Paused to CaptureStatus Paused, distinct from Recording and Idle`() {
        val mapped = DashboardViewModel.mapCaptureStatus(CaptureState.Paused)
        assertEquals(CaptureStatus.Paused, mapped)
        assertTrue(mapped != CaptureStatus.Recording && mapped != CaptureStatus.Idle)
    }

    @Test
    fun `mapCaptureStatus carries the error reason and message through unchanged`() {
        val engineError = CaptureState.Error(CaptureErrorReason.READ_DEAD_OBJECT, "AudioRecord.read() returned -6")
        val mapped = DashboardViewModel.mapCaptureStatus(engineError)
        assertEquals(CaptureStatus.Error(CaptureErrorReason.READ_DEAD_OBJECT, "AudioRecord.read() returned -6"), mapped)
    }

    // ---- mapUiState: full vs partial buffer mapping (issue #121) ----

    @Test
    fun `mapUiState correctly identifies empty buffer`() {
        val state = DashboardViewModel.mapUiState(
            captureState = CaptureState.Recording,
            bufferedMillis = 0L,
            capacityMinutes = 30,
            saveState = SaveUiState.Idle,
        )

        assertEquals(0L, state.bufferedMillis)
        assertEquals(30 * 60_000L, state.capacityMillis)
        assertFalse(state.isBufferFull)
    }

    @Test
    fun `mapUiState correctly identifies partial buffer`() {
        val state = DashboardViewModel.mapUiState(
            captureState = CaptureState.Recording,
            bufferedMillis = 12 * 60_000L,
            capacityMinutes = 30,
            saveState = SaveUiState.Idle,
        )

        assertEquals(12 * 60_000L, state.bufferedMillis)
        assertEquals(30 * 60_000L, state.capacityMillis)
        assertFalse(state.isBufferFull)
    }

    @Test
    fun `mapUiState correctly identifies full buffer`() {
        val state = DashboardViewModel.mapUiState(
            captureState = CaptureState.Recording,
            bufferedMillis = 30 * 60_000L,
            capacityMinutes = 30,
            saveState = SaveUiState.Idle,
        )

        assertEquals(30 * 60_000L, state.bufferedMillis)
        assertEquals(30 * 60_000L, state.capacityMillis)
        assertTrue(state.isBufferFull)
    }

    // ---- mapSaveUiState: the real-progress oracle (issue #40 item 2) ----

    @Test
    fun `mapSaveUiState maps Idle and Exporting through unchanged`() {
        assertEquals(SaveUiState.Idle, DashboardViewModel.mapSaveUiState(ExportState.Idle, dismissed = null))
        assertEquals(SaveUiState.Exporting, DashboardViewModel.mapSaveUiState(ExportState.Exporting, dismissed = null))
    }

    @Test
    fun `mapSaveUiState surfaces a fresh Success with the saved file name`() {
        val export = ExportState.Success(displayName = "blackbox_2026-08-21_10-00-00_5min.m4a", bytesWritten = 1234)
        val mapped = DashboardViewModel.mapSaveUiState(export, dismissed = null)
        assertEquals(SaveUiState.Success("blackbox_2026-08-21_10-00-00_5min.m4a"), mapped)
    }

    @Test
    fun `mapSaveUiState surfaces a fresh Error with its reason and message`() {
        val export = ExportState.Error(ExportFailureReason.WRITE_FAILED, "disk full")
        val mapped = DashboardViewModel.mapSaveUiState(export, dismissed = null)
        assertEquals(SaveUiState.Error(ExportFailureReason.WRITE_FAILED, "disk full"), mapped)
    }

    @Test
    fun `mapSaveUiState hides a Success that has already been dismissed`() {
        val export = ExportState.Success(displayName = "blackbox_2026-08-21_10-00-00_5min.m4a", bytesWritten = 1234)
        val mapped = DashboardViewModel.mapSaveUiState(export, dismissed = export)
        assertEquals(SaveUiState.Idle, mapped)
    }

    @Test
    fun `mapSaveUiState hides an Error that has already been dismissed`() {
        val export = ExportState.Error(ExportFailureReason.WRITE_FAILED, "disk full")
        val mapped = DashboardViewModel.mapSaveUiState(export, dismissed = export)
        assertEquals(SaveUiState.Idle, mapped)
    }

    @Test
    fun `mapSaveUiState still surfaces a new Error even if a different Error was previously dismissed`() {
        val dismissedError = ExportState.Error(ExportFailureReason.WRITE_FAILED, "disk full")
        val newError = ExportState.Error(ExportFailureReason.SINK_OPEN_FAILED, "insert rejected")
        val mapped = DashboardViewModel.mapSaveUiState(newError, dismissed = dismissedError)
        assertEquals(SaveUiState.Error(ExportFailureReason.SINK_OPEN_FAILED, "insert rejected"), mapped)
    }

    // ---- mapUiState: the full oracle end to end ----

    @Test
    fun `mapUiState reports buffer-full and clamps buffered time to capacity`() {
        val state = DashboardViewModel.mapUiState(
            captureState = CaptureState.Recording,
            bufferedMillis = 45 * 60_000L, // more than capacity -- must never be reported as such
            capacityMinutes = 30,
            saveState = SaveUiState.Idle,
        )

        assertEquals(30 * 60_000L, state.bufferedMillis)
        assertEquals(30 * 60_000L, state.capacityMillis)
        assertTrue(state.isBufferFull)
    }

    @Test
    fun `mapUiState reports not-full while the buffer is still filling`() {
        val state = DashboardViewModel.mapUiState(
            captureState = CaptureState.Recording,
            bufferedMillis = 5 * 60_000L,
            capacityMinutes = 30,
            saveState = SaveUiState.Idle,
        )

        assertFalse(state.isBufferFull)
        assertEquals(5 * 60_000L, state.bufferedMillis)
    }

    @Test
    fun `mapUiState maps Paused distinctly and carries the save state through untouched`() {
        val exporting = SaveUiState.Exporting
        val state = DashboardViewModel.mapUiState(
            captureState = CaptureState.Paused,
            bufferedMillis = 10 * 60_000L,
            capacityMinutes = 30,
            saveState = exporting,
        )

        assertEquals(CaptureStatus.Paused, state.captureStatus)
        assertEquals(exporting, state.saveState)
    }

    @Test
    fun `mapUiState reflects a non-default configured capacity, not a hardcoded 30 (issue #40 item 3)`() {
        // A test that only ever exercises capacityMinutes = 30 cannot catch a regression where
        // this value gets hardcoded back to the default constant -- see issue #40's testing bar.
        val state = DashboardViewModel.mapUiState(
            captureState = CaptureState.Recording,
            bufferedMillis = 10 * 60_000L,
            capacityMinutes = 45,
            saveState = SaveUiState.Idle,
        )

        assertEquals(45 * 60_000L, state.capacityMillis)
        assertFalse("10 min of 45 must not be reported as full", state.isBufferFull)

        // The 30 min window option must be enabled here (10 min buffered is nowhere near it, so
        // this isn't the assertion -- the point is that a 45 min capacity does not clamp
        // bufferedMillis at 30 min the way a hardcoded default would).
        val fullAtNonDefaultCapacity = DashboardViewModel.mapUiState(
            captureState = CaptureState.Recording,
            bufferedMillis = 40 * 60_000L,
            capacityMinutes = 45,
            saveState = SaveUiState.Idle,
        )
        assertEquals(40 * 60_000L, fullAtNonDefaultCapacity.bufferedMillis)
        assertFalse(fullAtNonDefaultCapacity.isBufferFull)
    }

    // ---- mapForwardRecordingUiState: the forward continuous recording oracle (issue #55) ----

    @Test
    fun `mapForwardRecordingUiState maps Idle through unchanged`() {
        assertEquals(
            ForwardRecordingUiState.Idle,
            DashboardViewModel.mapForwardRecordingUiState(ForwardRecordingState.Idle, dismissed = null, bytesPerSecond = 32_000),
        )
    }

    @Test
    fun `mapForwardRecordingUiState calculates elapsed time from bytesWritten`() {
        val forwardState = ForwardRecordingState.Recording(
            displayName = "blackbox_2026-08-25_14-30-00_forward.m4a",
            bytesWritten = 64_000L, // 2 seconds at 32000 bytes/sec
        )
        val mapped = DashboardViewModel.mapForwardRecordingUiState(forwardState, dismissed = null, bytesPerSecond = 32_000)
        assertEquals(
            ForwardRecordingUiState.Recording(
                displayName = "blackbox_2026-08-25_14-30-00_forward.m4a",
                elapsedMillis = 2_000L,
            ),
            mapped,
        )
    }

    @Test
    fun `mapForwardRecordingUiState surfaces Success with file name and bytes written`() {
        val forwardState = ForwardRecordingState.Success(
            displayName = "blackbox_2026-08-25_14-30-00_forward.m4a",
            bytesWritten = 128_000L,
        )
        val mapped = DashboardViewModel.mapForwardRecordingUiState(forwardState, dismissed = null, bytesPerSecond = 32_000)
        assertEquals(
            ForwardRecordingUiState.Success(
                displayName = "blackbox_2026-08-25_14-30-00_forward.m4a",
                bytesWritten = 128_000L,
            ),
            mapped,
        )
    }

    @Test
    fun `mapForwardRecordingUiState surfaces Error with reason and message`() {
        val forwardState = ForwardRecordingState.Error(
            reason = ForwardRecordingFailureReason.CURSOR_LAPPED,
            message = "Cursor was lapped",
        )
        val mapped = DashboardViewModel.mapForwardRecordingUiState(forwardState, dismissed = null, bytesPerSecond = 32_000)
        assertEquals(
            ForwardRecordingUiState.Error(
                reason = ForwardRecordingFailureReason.CURSOR_LAPPED,
                message = "Cursor was lapped",
            ),
            mapped,
        )
    }

    @Test
    fun `mapForwardRecordingUiState hides Success when dismissed`() {
        val forwardState = ForwardRecordingState.Success(
            displayName = "blackbox_2026-08-25_14-30-00_forward.m4a",
            bytesWritten = 128_000L,
        )
        val mapped = DashboardViewModel.mapForwardRecordingUiState(forwardState, dismissed = forwardState, bytesPerSecond = 32_000)
        assertEquals(ForwardRecordingUiState.Idle, mapped)
    }

    @Test
    fun `mapForwardRecordingUiState hides Error when dismissed`() {
        val forwardState = ForwardRecordingState.Error(
            reason = ForwardRecordingFailureReason.WRITE_FAILED,
            message = "Disk full",
        )
        val mapped = DashboardViewModel.mapForwardRecordingUiState(forwardState, dismissed = forwardState, bytesPerSecond = 32_000)
        assertEquals(ForwardRecordingUiState.Idle, mapped)
    }

}
