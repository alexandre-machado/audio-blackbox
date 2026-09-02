package cc.machado.audioblackbox.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * Home-screen widget replacement (issue #275) for the Quick Settings tile removed in #273/#274:
 * a `TileService` tap is not on Android 14+'s while-in-use permission eligibility list for a
 * `microphone`-typed foreground service, but app-widget interaction is (`AGENTS.md` §11) -- and a
 * spike proved it on the owner's hardware before this was built (issue #267, comment
 * "UNBLOCKED -- the premise is proven on hardware").
 *
 * This class deliberately does almost nothing: [onUpdate] just repaints every widget instance
 * from the real, current capture state via [RecordingWidgetUpdater]. It does not implement
 * `onReceive` for anything beyond what [AppWidgetProvider] already handles, and in particular it
 * never wires a `BroadcastReceiver` action that starts or stops capture -- the widget's button
 * fires a `PendingIntent.getForegroundService()` straight at
 * [cc.machado.audioblackbox.service.RecorderService] (see [RecordingWidgetRenderer]), the same
 * mechanism the spike verified on-device. The spike's own throwaway `TEST_START`/`TEST_STOP`
 * broadcast hooks are explicitly called out as scaffolding that must not reappear in the real
 * implementation (an exported broadcast receiver that can start/stop the microphone is exactly
 * the IPC surface `@sec` should block) -- this class has no such surface.
 *
 * `android:exported="true"` is required in the manifest for the launcher's `AppWidgetHost` to
 * bind and deliver clicks (the spike learned this the hard way: copying the tile's
 * `exported="false"` pattern breaks widget binding). The only externally reachable action is the
 * platform's own `APPWIDGET_UPDATE` broadcast, which this class answers with nothing but a
 * re-render of already-real state -- it accepts no attacker-controlled data and cannot start or
 * stop recording on its own.
 */
class RecordingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        RecordingWidgetUpdater.refreshAll(context)
    }
}
