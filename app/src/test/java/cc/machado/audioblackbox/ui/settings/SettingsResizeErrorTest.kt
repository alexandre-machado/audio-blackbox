package cc.machado.audioblackbox.ui.settings

import cc.machado.audioblackbox.audio.QualityPreset
import cc.machado.audioblackbox.audio.ResizeOutcome
import cc.machado.audioblackbox.audio.SwitchConfigResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the settings-screen surface of a refused resize (issue #272).
 *
 * ## The oracle
 * [SettingsViewModel.describeRefusal] must produce a message that states the actual requested
 * size, never a generic "something went wrong" (AGENTS.md §5), and [SettingsViewModel.mapUiState]
 * must pass whatever `resizeError` it is given straight through to [SettingsUiState.resizeError]
 * unmodified -- the one place [SettingsScreen] reads it to decide whether to show the dialog.
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

        val message = SettingsViewModel.describeRefusal(refusal, requestedMinutes = 90)

        assertTrue("must mention the requested minutes: $message", message.contains("90"))
        assertTrue("must mention the actual requested size in MB, not a placeholder: $message", message.contains("101"))
    }

    @Test
    fun `describeRefusal still produces a real message when the outcome details are unavailable`() {
        val message = SettingsViewModel.describeRefusal(refusal = null, requestedMinutes = 60)
        assertTrue(message.contains("60"))
        assertTrue(message.isNotBlank())
    }

    @Test
    fun `mapUiState passes resizeError straight through as the load-bearing screen signal`() {
        val withError = SettingsViewModel.mapUiState(
            committedMinutes = 30,
            pendingMinutes = 30,
            committedPreset = QualityPreset.DEFAULT,
            resizeError = "Couldn't change the recording length to 90 min (101 MB): not enough memory.",
        )
        assertEquals("Couldn't change the recording length to 90 min (101 MB): not enough memory.", withError.resizeError)

        val withoutError = SettingsViewModel.mapUiState(
            committedMinutes = 30,
            pendingMinutes = 30,
            committedPreset = QualityPreset.DEFAULT,
            resizeError = null,
        )
        assertNull(withoutError.resizeError)
    }
}
