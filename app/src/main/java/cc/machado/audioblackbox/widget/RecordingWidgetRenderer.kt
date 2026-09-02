package cc.machado.audioblackbox.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.service.RecorderService

/**
 * Builds the widget's `RemoteViews` from the real, current
 * [RecorderService.captureState] -- the only place this app paints the widget (issue #275; see
 * [RecordingWidgetStateMapper]'s doc for why that single-path property is what keeps this widget
 * from repeating the removed Quick Settings tile's "shows on while the process died" defect).
 *
 * Not unit-testable on the plain JVM (this repo has no Robolectric): `RemoteViews` and
 * `PendingIntent` are real Android framework classes with no usable JVM stand-in here. That gap is
 * real and stays a Tier 2 (physical device) concern -- [RecordingWidgetStateMapper]'s mapping
 * logic, which is what actually decides *what* gets painted, is fully covered on the JVM instead;
 * this class is a thin, mechanical translation of that model into `RemoteViews` calls.
 */
object RecordingWidgetRenderer {

    private const val REQUEST_CODE_START = 5001
    private const val REQUEST_CODE_STOP = 5002

    fun render(context: Context): RemoteViews {
        val model = RecordingWidgetStateMapper.map(RecorderService.captureState.value)
        val views = RemoteViews(context.packageName, R.layout.widget_recording)

        views.setTextViewText(R.id.widget_status_text, context.getString(model.statusTextRes))
        views.setContentDescription(
            R.id.widget_root,
            context.getString(model.rootContentDescriptionRes),
        )

        views.setInt(
            R.id.widget_annunciator,
            "setColorFilter",
            ContextCompat.getColor(context, annunciatorColorRes(model.annunciator)),
        )

        views.setTextViewText(R.id.widget_action_button, context.getString(model.actionButtonLabelRes))
        views.setContentDescription(
            R.id.widget_action_button,
            context.getString(model.actionButtonDescriptionRes),
        )
        views.setOnClickPendingIntent(
            R.id.widget_action_button,
            actionPendingIntent(context, model.actionIsStop),
        )

        return views
    }

    private fun annunciatorColorRes(annunciator: WidgetAnnunciator): Int = when (annunciator) {
        WidgetAnnunciator.OK_GREEN -> R.color.widget_annunciator_recording
        WidgetAnnunciator.CAUTION_AMBER -> R.color.widget_annunciator_paused
        WidgetAnnunciator.WARNING_RED -> R.color.widget_annunciator_error
        WidgetAnnunciator.IDLE_DIM -> R.color.widget_annunciator_idle
    }

    /**
     * `PendingIntent.getForegroundService()`, not `getService()` -- the spike on issue #267/#275
     * verified this is the mechanism that actually carries `getFgsAllowWiu_forStart` when fired by
     * a genuine widget tap. A refactor that swapped this for `getService()` would compile and look
     * identical in the debugger until the very first real device tap; there is no JVM/host test
     * that can catch that regression (see this class's own doc and the PR's device-evidence
     * section), which is why the device verification step in the PR re-confirms this specific
     * field on the finished widget rather than assuming it survived.
     */
    private fun actionPendingIntent(context: Context, actionIsStop: Boolean): PendingIntent {
        val action = if (actionIsStop) RecorderService.ACTION_STOP else RecorderService.ACTION_START
        val requestCode = if (actionIsStop) REQUEST_CODE_STOP else REQUEST_CODE_START
        val intent = Intent(context, RecorderService::class.java).setAction(action)
        return PendingIntent.getForegroundService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
