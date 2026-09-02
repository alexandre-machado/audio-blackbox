package cc.machado.audioblackbox.ui.settings

import cc.machado.audioblackbox.audio.QualityPreset
import cc.machado.audioblackbox.audio.ResizeOutcome
import cc.machado.audioblackbox.audio.SwitchConfigResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the settings-screen surface of a refused resize (issue #272).
 *
 * ## The oracle
 * [SettingsViewModel.describeRefusal] must produce data that states the actual requested size,
 * never a generic "something went wrong" (AGENTS.md §5) -- it returns a [ResizeErrorInfo] rather
 * than a formatted message because [SettingsScreen] renders the actual wording through
 * `strings.xml` (`R.string.settings_resize_error_body`/`_no_mb`), which needs the raw numbers, not
 * a pre-baked English sentence. [SettingsViewModel.mapUiState] must pass whatever `resizeError` it
 * is given straight through to [SettingsUiState.resizeError] unmodified -- the one place
 * [SettingsScreen] reads it to decide whether to show the dialog.
 */
class SettingsResizeErrorTest {

    @Test
    fun `describeRefusal states the actual requested megabytes, not a generic message`() {
        val refusal = SwitchConfigResult.BufferResizeRefused(
            ResizeOutcome.Refused(
                requestedCapacityBytes = 101_000_000,
                projectedPeakBytes = 306_000_000L,
                maxHeapBytes = 268_435_456L,
            ),
        )

        val info = SettingsViewModel.describeRefusal(refusal, requestedMinutes = 90)

        assertEquals(90, info.requestedMinutes)
        assertEquals(101, info.requestedMb)
    }

    @Test
    fun `describeRefusal still produces real data when the outcome details are unavailable`() {
        val info = SettingsViewModel.describeRefusal(refusal = null, requestedMinutes = 60)
        assertEquals(60, info.requestedMinutes)
        assertNull(info.requestedMb)
    }

    @Test
    fun `mapUiState passes resizeError straight through as the load-bearing screen signal`() {
        val errorInfo = ResizeErrorInfo(requestedMinutes = 90, requestedMb = 101)
        val withError = SettingsViewModel.mapUiState(
            committedMinutes = 30,
            pendingMinutes = 30,
            committedPreset = QualityPreset.DEFAULT,
            resizeError = errorInfo,
        )
        assertEquals(errorInfo, withError.resizeError)

        val withoutError = SettingsViewModel.mapUiState(
            committedMinutes = 30,
            pendingMinutes = 30,
            committedPreset = QualityPreset.DEFAULT,
            resizeError = null,
        )
        assertNull(withoutError.resizeError)
    }
}
