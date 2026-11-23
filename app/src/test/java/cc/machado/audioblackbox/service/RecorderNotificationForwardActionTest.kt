package cc.machado.audioblackbox.service

import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.export.ForwardRecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No Robolectric in Tier 0: exercise the action selection build() uses before resolving Context strings. */
class RecorderNotificationForwardActionTest {
    /** Oracle: a live session must select the save-and-end title and the ending intent together. */
    @Test fun recordingSelectsSaveAndEnd() {
        val action = RecorderNotification.forwardActionFor(
            ForwardRecordingState.Recording("live.m4a", 42_000L),
        )
        assertEquals(R.string.recorder_notification_action_stop_forward, action.labelRes)
        assertTrue(action.endsRecording)
    }

    /** Oracle: idle and completed sessions must offer a new recording, never ending the old one. */
    @Test fun idleAndCompletedSelectStart() {
        for (state in listOf(
            ForwardRecordingState.Idle,
            ForwardRecordingState.Success("live.m4a", 42_000L),
        )) {
            val action = RecorderNotification.forwardActionFor(state)
            assertEquals(R.string.recorder_notification_action_start_forward, action.labelRes)
            assertFalse(action.endsRecording)
        }
    }
}
