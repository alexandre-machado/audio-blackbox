package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.CaptureErrorReason
import cc.machado.audioblackbox.audio.CaptureState
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

    // ---- computeWindowOptions: the window-selector gap's exact enable/disable rule ----

    @Test
    fun `all options are disabled with INSUFFICIENT_BUFFER when nothing has been buffered yet`() {
        val options = DashboardViewModel.computeWindowOptions(bufferedMillis = 0L, capacityMinutes = 30)

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
        val options = DashboardViewModel.computeWindowOptions(bufferedMillis = 12 * 60_000L, capacityMinutes = 30)

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
    fun `a shorter option within the buffered amount is disabled as not-yet-supported, not silently allowed`() {
        // 12 minutes buffered: the 5 min option has enough audio behind it, but the engine has
        // no way to export a window shorter than everything buffered (issue #6 gap) -- this must
        // never be silently enabled, which would risk producing a shorter file than requested.
        val options = DashboardViewModel.computeWindowOptions(bufferedMillis = 12 * 60_000L, capacityMinutes = 30)

        val five = options.single { it.minutes == 5 }
        assertFalse(five.enabled)
        assertEquals(WindowDisabledReason.PARTIAL_WINDOW_NOT_SUPPORTED, five.disabledReason)
    }

    @Test
    fun `only the option matching capacity becomes enabled, and only once the buffer holds that much`() {
        val notYetFull = DashboardViewModel.computeWindowOptions(bufferedMillis = 29 * 60_000L, capacityMinutes = 30)
        assertFalse(notYetFull.single { it.minutes == 30 }.enabled)
        assertEquals(WindowDisabledReason.INSUFFICIENT_BUFFER, notYetFull.single { it.minutes == 30 }.disabledReason)

        val full = DashboardViewModel.computeWindowOptions(bufferedMillis = 30 * 60_000L, capacityMinutes = 30)
        val thirty = full.single { it.minutes == 30 }
        assertTrue(thirty.enabled)
        assertEquals(null, thirty.disabledReason)
        // The 5 and 15 options stay disabled even once the buffer is full -- the engine still
        // cannot honor a shorter request, only a full one.
        assertFalse(full.single { it.minutes == 5 }.enabled)
        assertFalse(full.single { it.minutes == 15 }.enabled)
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
    fun `mapUiState maps Paused distinctly and carries the save-request state through untouched`() {
        val requested = SaveUiState.Requested(minutes = 30)
        val state = DashboardViewModel.mapUiState(
            captureState = CaptureState.Paused,
            bufferedMillis = 10 * 60_000L,
            capacityMinutes = 30,
            saveState = requested,
        )

        assertEquals(CaptureStatus.Paused, state.captureStatus)
        assertEquals(requested, state.saveState)
    }
}
