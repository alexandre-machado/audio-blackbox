package cc.machado.audioblackbox.widget

import android.content.Context
import cc.machado.audioblackbox.service.RecorderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-lifetime collector on [RecorderService.captureState] that keeps every placed
 * [RecordingWidgetProvider] instance repainted with real state (issue #275). Started once from
 * [cc.machado.audioblackbox.AudioBlackboxApplication.onCreate].
 *
 * `StateFlow.collect` replays the current value to a brand-new collector before waiting for the
 * next change, so [start]'s very first collection reconciles the widget with whatever
 * [RecorderService.captureState] genuinely holds in *this* process -- see
 * [RecordingWidgetUpdater]'s doc for why that is the fix for the process-death staleness gap that
 * broke the removed Quick Settings tile, not merely a live-update convenience.
 *
 * Not unit-testable on the plain JVM as written (it touches `Context`/`AppWidgetManager` via
 * [RecordingWidgetUpdater]); [RecordingWidgetStateMapperTest] covers the state-mapping decision
 * this collector's downstream render depends on.
 */
object RecordingWidgetStateObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            RecorderService.captureState.collect {
                RecordingWidgetUpdater.refreshAll(appContext)
            }
        }
    }
}
