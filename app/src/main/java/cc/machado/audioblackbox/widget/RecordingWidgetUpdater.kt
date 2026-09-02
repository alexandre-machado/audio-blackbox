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
 * [RecordingWidgetStateObserver] starting is *also* part of what closes the process-death
 * staleness gap: it is started from [cc.machado.audioblackbox.AudioBlackboxApplication.onCreate],
 * which runs at the very start of every fresh process (crash-restart, OS reclaim, reboot, alike).
 * `StateFlow` emits its current value immediately to a brand-new collector, so the very first
 * collection fires this method with whatever
 * [cc.machado.audioblackbox.service.RecorderService.captureState] genuinely holds in the new
 * process (`Idle`, unless a session is already active) -- overwriting any stale `RemoteViews` a
 * previous, now-dead process may have left painted on the launcher's home screen, *the next time a
 * process actually starts*. Nothing here proactively triggers that new process start: after an
 * unclean death while genuinely recording, the launcher's stale `RemoteViews` (still showing
 * "Recording") persists until whichever comes first -- the framework's own `updatePeriodMillis`
 * broadcast (`recording_widget_info.xml`, currently 30 minutes, the Android-enforced floor, and
 * longer under Doze/App Standby throttling), the user reopening the app (which starts a fresh
 * process), or the user tapping the stale widget itself (`RecordingWidgetProvider`'s
 * `PendingIntent`, which starts a fresh process the same way and then resolves correctly, per the
 * spike's verified findings on re-promoting an already-running/newly-eligible foreground service).
 * This is the mechanism that keeps the widget from repeating the removed Quick Settings tile's
 * "still shows on after the process that would have turned it off is gone" defect once one of
 * those three things happens -- it does not make that window itself immediate (`@rev` review on
 * PR #278, finding 2).
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
