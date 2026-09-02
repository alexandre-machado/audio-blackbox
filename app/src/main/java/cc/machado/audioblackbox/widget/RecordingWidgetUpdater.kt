package cc.machado.audioblackbox.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/**
 * Pushes a freshly-rendered `RemoteViews` (built from the real, current
 * [cc.machado.audioblackbox.service.RecorderService.captureState]) to every placed instance of
 * [RecordingWidgetProvider]. Called from two places (issue #275):
 *
 * 1. [RecordingWidgetProvider.onUpdate] -- the framework-driven path (placement, the
 *    `updatePeriodMillis` timer, `notifyAppWidgetUpdate`-style callers).
 * 2. [RecordingWidgetStateObserver] -- a live collector on [cc.machado.audioblackbox.service.RecorderService.captureState]
 *    that calls this on every real transition, so a placed widget repaints promptly instead of
 *    waiting for the next framework-driven update.
 *
 * [RecordingWidgetStateObserver] starting is *also* what closes the process-death staleness gap:
 * it is started from [cc.machado.audioblackbox.AudioBlackboxApplication.onCreate], which runs at
 * the very start of every fresh process (crash-restart, OS reclaim, reboot, alike). `StateFlow`
 * emits its current value immediately to a brand-new collector, so the very first collection
 * fires this method with whatever [cc.machado.audioblackbox.service.RecorderService.captureState]
 * genuinely holds in the new process (`Idle`, unless a session is already active) -- overwriting
 * any stale `RemoteViews` a previous, now-dead process may have left painted on the launcher's
 * home screen, within one process start rather than waiting up to the `updatePeriodMillis` ceiling.
 * This is the mechanism that keeps the widget from repeating the removed Quick Settings tile's
 * "still shows on after the process that would have turned it off is gone" defect.
 */
object RecordingWidgetUpdater {

    fun refreshAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val component = ComponentName(context, RecordingWidgetProvider::class.java)
        val appWidgetIds = manager.getAppWidgetIds(component)
        if (appWidgetIds.isEmpty()) return
        manager.updateAppWidget(appWidgetIds, RecordingWidgetRenderer.render(context))
    }
}
