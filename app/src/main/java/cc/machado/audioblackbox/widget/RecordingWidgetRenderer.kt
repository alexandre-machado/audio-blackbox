package cc.machado.audioblackbox.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
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
 * *what* gets painted, is fully covered on the JVM instead. But `render`'s own mechanical calls
 * into `RemoteViews`/`PendingIntent` (both real, final Android framework classes with no
 * Robolectric shim here) are covered on the JVM via Mockito's inline mock maker constructing and
 * verifying against a mocked instance rather than a real one -- see
 * [RecordingWidgetRendererTest] for the regression this closes (issue #279: the
 * `setAccessibilityLiveRegion` reflective call must actually be issued on every render).
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

        // Issue #279 (`@rev` finding on PR #289): `RemoteViews.setInt(viewId, methodName, value)`
        // is a generic, by-name reflective dispatcher -- it invokes any public single-`int`-arg
        // method on the real host `View` at apply time in the launcher process, the same
        // mechanism `setColorFilter` below already relies on. `View.setAccessibilityLiveRegion`
        // has exactly that shape, so this reaches it the same way, paired with the
        // `setContentDescription` above (already called on every repaint): together this is
        // architecturally the same trick Compose's `liveRegion` semantics use -- the platform
        // accessibility layer, not an explicit `sendAccessibilityEvent`/`announceForAccessibility`
        // call, is what can turn a content-description change on a live-region-flagged node into
        // a proactive announcement. Deliberately defensive: if a host launcher ignores this
        // method name (an OEM host that doesn't reflect it, or refuses it for any reason),
        // `RemoteViews.setInt` applies host-side at `reapply()` time and simply no-ops for that
        // one call -- it cannot throw here, and every other view property on this same
        // `RemoteViews` still applies normally. Whether a real launcher actually turns this into
        // an audible TalkBack announcement is UNVERIFIED -- an on-device (Tier 2) check, not
        // something the API surface alone can answer (see `AGENTS.md` §5).
        views.setInt(
            R.id.widget_root,
            "setAccessibilityLiveRegion",
            View.ACCESSIBILITY_LIVE_REGION_POLITE,
        )

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
