package cc.machado.audioblackbox.service

import cc.machado.audioblackbox.export.ForwardRecordingFailureReason
import cc.machado.audioblackbox.export.ForwardRecordingState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveRoutingTest {
    /** Oracle: handleSave's production decision must select checkpoint only during live. */
    @Test fun liveSaveRoutesToCheckpoint() {
        assertTrue("live Save must checkpoint, never export", saveUsesCheckpoint(ForwardRecordingState.Recording("live", 32)))
    }

    @Test fun otherStatesKeepExportRouting() {
        // Oracle: terminal and idle states retain the pre-existing retrospective export path.
        listOf(
            ForwardRecordingState.Idle,
            ForwardRecordingState.Success("done", 32),
            ForwardRecordingState.Error(ForwardRecordingFailureReason.WRITE_FAILED, "error"),
        ).forEach { assertFalse(saveUsesCheckpoint(it)) }
    }
}
