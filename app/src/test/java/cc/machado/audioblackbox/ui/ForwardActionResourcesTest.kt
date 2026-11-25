package cc.machado.audioblackbox.ui

import cc.machado.audioblackbox.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ForwardActionResourcesTest {
    /** Oracle: the production resolver consumed by ActionPanelRow and RecorderNotification
     * selects the live-file save/end resources while recording. */
    @Test fun recordingSelectsSaveAndEndResources() {
        val action = forwardActionResources(true)
        assertEquals(R.string.dashboard_panel_live_stop_label, action.label)
        assertEquals(R.string.dashboard_forward_stop_button, action.contentDescription)
        assertEquals(R.string.recorder_notification_action_stop_forward, action.notificationLabel)
    }

    /** Oracle: outside recording the same consumers offer start, not save/end. */
    @Test fun idleSelectsStartResources() {
        val action = forwardActionResources(false)
        assertEquals(R.string.dashboard_panel_live_label, action.label)
        assertEquals(R.string.dashboard_forward_start_button, action.contentDescription)
        assertEquals(R.string.recorder_notification_action_start_forward, action.notificationLabel)
    }

}
