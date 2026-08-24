package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.CaptureErrorReason
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.export.ExportFailureReason
import cc.machado.audioblackbox.export.ExportState
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

    // ---- computeWindowOptions: the window-selector fix (issue #40 item 1) ----

    @Test
    fun `all options are disabled with INSUFFICIENT_BUFFER when nothing has been buffered yet`() {
        val options = DashboardViewModel.computeWindowOptions(bufferedMillis = 0L)

        assertEquals(3, options.size)
        options.forEach { option ->
            assertFalse("option $option should be disabled", option.enabled)
            assertEquals(WindowDisabledReason.INSUFFICIENT_BUFFER, option.disabledReason)
            assertEquals(0, option.availableMinutes)
        }
    }

    @Test
    fun `the mandatory case -- a requested window longer than what is buffered -- is disabled with the available minutes attached`() {
        // 12 minutes buffered: the 15 and 30 min options both request more than is available.
        val options = DashboardViewModel.computeWindowOptions(bufferedMillis = 12 * 60_000L)

        val fifteen = options.single { it.minutes == 15 }
        assertFalse(fifteen.enabled)
        assertEquals(WindowDisabledReason.INSUFFICIENT_BUFFER, fifteen.disabledReason)
        assertEquals(12, fifteen.availableMinutes)

        val thirty = options.single { it.minutes == 30 }
        assertFalse(thirty.enabled)
        assertEquals(WindowDisabledReason.INSUFFICIENT_BUFFER, thirty.disabledReason)
        assertEquals(12, thirty.availableMinutes)
    }

    @Test
    fun `a shorter option within the buffered amount is now enabled, closing issue #40's gap`() {
        // 12 minutes buffered: the 5 min option has enough audio behind it, and the service can
        // now honour that exact window (issue #40 item 1) -- this must be enabled, not disabled.
        val options = DashboardViewModel.computeWindowOptions(bufferedMillis = 12 * 60_000L)

        val five = options.single { it.minutes == 5 }
        assertTrue(five.enabled)
        assertEquals(null, five.disabledReason)
        assertEquals(12, five.availableMinutes)
    }

    @Test
    fun `every option becomes enabled once the buffer holds enough for all of them`() {
        val options = DashboardViewModel.computeWindowOptions(bufferedMillis = 30 * 60_000L)

        options.forEach { option ->
            assertTrue("option $option should be enabled", option.enabled)
            assertEquals(null, option.disabledReason)
        }
    }

    @Test
    fun `an option becomes enabled at the exact minute it is first fully buffered, not one minute early`() {
        val notYet = DashboardViewModel.computeWindowOptions(bufferedMillis = 15 * 60_000L - 1L)
        assertFalse(notYet.single { it.minutes == 15 }.enabled)

        val exact = DashboardViewModel.computeWindowOptions(bufferedMillis = 15 * 60_000L)
        assertTrue(exact.single { it.minutes == 15 }.enabled)
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
}
