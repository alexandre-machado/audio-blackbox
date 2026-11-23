package cc.machado.audioblackbox.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import cc.machado.audioblackbox.R

/** Ending a live file saves it, leaving capture running. Shared by dashboard and notification. */
internal data class ForwardActionResources(
    @StringRes val label: Int,
    @StringRes val contentDescription: Int,
    @StringRes val notificationLabel: Int,
    @DrawableRes val icon: Int?,
)

internal fun forwardActionResources(isRecording: Boolean): ForwardActionResources =
    if (isRecording) {
        ForwardActionResources(
            R.string.dashboard_panel_live_stop_label,
            R.string.dashboard_forward_stop_button,
            R.string.recorder_notification_action_stop_forward,
            R.drawable.ic_bookmark_save,
        )
    } else {
        ForwardActionResources(
            R.string.dashboard_panel_live_label,
            R.string.dashboard_forward_start_button,
            R.string.recorder_notification_action_start_forward,
            // Idle also carries a glyph so the button's height does not jump when live starts.
            R.drawable.ic_continuous_record,
        )
    }

/** Live Save rotates the current file; idle Save retains its existing buffered-window wording. */
internal fun saveActionResources(isRecording: Boolean): ForwardActionResources =
    if (isRecording) {
        ForwardActionResources(
            R.string.save_continue_label,
            R.string.save_continue_cd,
            R.string.save_continue_label,
            R.drawable.ic_bookmark_save,
        )
    } else {
        ForwardActionResources(
            R.string.dashboard_panel_save_label,
            R.string.dashboard_save_button_cd_partial,
            R.string.recorder_notification_action_save,
            R.drawable.ic_bookmark_save,
        )
    }
