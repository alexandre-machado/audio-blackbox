package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.CaptureState
import org.junit.Assert.*
import org.junit.Test

class DashboardLiveSaveTest {
    /** Oracle: the resource actually consumed by the panel stays Saving during overlap. */
    @Test fun exportingCaptionTakesPrecedenceOverLive() {
        org.junit.Assert.assertEquals(
            cc.machado.audioblackbox.R.string.dashboard_panel_save_caption_saving,
            activeSaveCaptionResource(isExporting = true, isSavingToLive = true),
        )
        org.junit.Assert.assertEquals(
            cc.machado.audioblackbox.R.string.dashboard_panel_save_caption_live,
            activeSaveCaptionResource(isExporting = false, isSavingToLive = true),
        )
        org.junit.Assert.assertNull(activeSaveCaptionResource(false, false))
    }

    /** Oracle: the actual UI-state property bound to the button disables Save only during live
     * or the existing empty/exporting states; buffered audio cannot override live.
     */
    @Test fun liveDisablesSaveAndIdleRestoresIt() {
        val idle = DashboardViewModel.mapUiState(
            captureState = CaptureState.Recording, bufferedMillis = 60_000,
            capacityMinutes = 5, saveState = SaveUiState.Idle,
        )
        assertTrue(idle.canSave)
        assertFalse(idle.copy(forwardRecordingState = ForwardRecordingUiState.Recording("live.aac", 42_000)).canSave)
        assertFalse(idle.copy(bufferedMillis = 0).canSave)
        assertFalse(idle.copy(saveState = SaveUiState.Exporting).canSave)
    }
}
