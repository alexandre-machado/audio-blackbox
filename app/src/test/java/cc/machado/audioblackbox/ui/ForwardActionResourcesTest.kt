package cc.machado.audioblackbox.ui

import cc.machado.audioblackbox.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForwardActionResourcesTest {
    /** Oracle: the production resolver consumed by ActionPanelRow and RecorderNotification
     * selects the live-file save/end resources and the existing save glyph while recording. */
    @Test fun recordingSelectsSaveAndEndResources() {
        val action = forwardActionResources(true)
        assertEquals(R.string.dashboard_panel_live_stop_label, action.label)
        assertEquals(R.string.dashboard_forward_stop_button, action.contentDescription)
        assertEquals(R.string.recorder_notification_action_stop_forward, action.notificationLabel)
        assertEquals(R.drawable.ic_bookmark_save, action.icon)
    }

    /** Oracle: outside recording the same consumers offer start, not save/end. */
    @Test fun idleSelectsStartResources() {
        val action = forwardActionResources(false)
        assertEquals(R.string.dashboard_panel_live_label, action.label)
        assertEquals(R.string.dashboard_forward_start_button, action.contentDescription)
        assertEquals(R.string.recorder_notification_action_start_forward, action.notificationLabel)
        assertNull(action.icon)
    }

}
