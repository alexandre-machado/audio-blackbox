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
 * End-to-end painting (real launcher host, real `AppWidgetHostView`) stays a Tier 2 (physical
 * device) concern -- [RecordingWidgetStateMapper]'s mapping logic, which is what actually decides
 * *what* gets painted, is fully covered on the JVM instead. `render`'s own mechanical calls into
 * `RemoteViews`/`PendingIntent` (both real, final Android framework classes with no Robolectric
 * shim here) are covered on the JVM via Mockito's inline mock maker constructing and verifying
 * against a mocked instance rather than a real one -- see [RecordingWidgetRendererTest].
 *
 * Issue #291: that JVM/mocked coverage proved a `RemoteViews.setInt` call was *issued*, never that
 * a real host would *accept* it -- a mocked `RemoteViews` has no `@RemotableViewMethod` allowlist
 * to reject against. The `setAccessibilityLiveRegion` call this doc used to reference shipped past
 * that mocked test and crashed inflation on a real Samsung S25 launcher. The regression test for
 * that class of defect is instrumented, not JVM: see
 * `RecordingWidgetRendererInstrumentedTest#render_appliesCleanlyToRealHostView` in the
 * `androidTest` source set, which applies this method's output to a real `AppWidgetHostView`.
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

        // Issue #291: a prior revision of this method also called
        // `views.setInt(R.id.widget_root, "setAccessibilityLiveRegion", ...)`, reasoning that
        // `RemoteViews.setInt` is a generic by-name reflective dispatcher like the `setColorFilter`
        // call below. That reasoning was wrong in a way the JVM test suite could not catch:
        // `RemoteViews.getMethod` only allows methods annotated `@RemotableViewMethod` (the
        // annotation `ImageView.setColorFilter` carries and `View.setAccessibilityLiveRegion` does
        // not), so a real host rejects the whole `RemoteViews` tree at inflate time with
        // `ActionException`, and the widget fails to add at all ("Couldn't add widget.") on a real
        // Samsung S25 launcher. See `AGENTS.md` §5: a widget cannot be given a live region from
        // this app's process; `setInt` is only safe for methods carrying `@RemotableViewMethod`.
        // `setContentDescription` above is unaffected and remains what TalkBack reads on focus.

        views.setInt(
            R.id.widget_annunciator,
            "setColorFilter",
            ContextCompat.getColor(context, annunciatorColorRes(model.annunciator)),
        )

        // Issue #275/#278: single-row layout replaces the old full-width text Button with a
        // round icon-only ImageButton (a circle has no room for a text label at a real 48dp
        // touch target). The icon itself is derived from model.actionIsStop, already the single
        // source of truth for which action the button's PendingIntent fires -- there is no
        // separate icon field on RecordingWidgetUiModel to drift out of sync with it. The label
        // string (model.actionButtonLabelRes) is intentionally unused for on-screen text now, but
        // the contentDescription below is unchanged and becomes the *only* affordance a TalkBack
        // user has for this button, since there is no visible text left to read.
        views.setImageViewResource(
            R.id.widget_action_button,
            if (model.actionIsStop) R.drawable.ic_widget_stop else R.drawable.ic_widget_record,
        )
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
