package cc.machado.audioblackbox.widget

import android.content.Context
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.service.RecorderService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
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
 * The re-entrancy guard (`if (job?.isActive == true) return`) and the collector's plumbing are
 * plain Kotlin over mockable interfaces (`Context`, [captureState] defaulting to the real
 * [RecorderService.captureState], and [onCaptureState] defaulting to the real
 * [RecordingWidgetUpdater.refreshAll]) -- no Robolectric needed, see
 * [RecordingWidgetStateObserverTest] (PR #278 review, `@rev` finding 3). What genuinely isn't
 * JVM-testable is [RecordingWidgetUpdater]'s own `AppWidgetManager`/`RemoteViews` mechanics, which
 * that class's own doc states plainly.
 */
object RecordingWidgetStateObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /**
     * @param dispatcher where the collector runs. Injectable purely so [RecordingWidgetStateObserverTest]
     *   can drive it with a `TestDispatcher` and assert on the re-entrancy guard deterministically,
     *   instead of sleeping real milliseconds to "prove" a second collector did not attach --
     *   `AGENTS.md` §3 forbids that kind of wall-clock escape hatch (PR #278 review, `@rev`).
     *   Production always uses the default.
     */
    fun start(
        context: Context,
        captureState: StateFlow<CaptureState> = RecorderService.captureState,
        onCaptureState: (Context) -> Unit = RecordingWidgetUpdater::refreshAll,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch(dispatcher) {
            captureState.collect {
                onCaptureState(appContext)
            }
        }
    }

    /** Test-only: cancels any active collector and clears [job]. This object is a process-lifetime
     * singleton in production (deliberately never reset there) -- a test that calls [start] must
     * call this in teardown, or a later test's [start] call silently no-ops against a job left
     * running (and bound to a mocked [Context]) by an earlier test. */
    internal fun resetForTest() {
        job?.cancel()
        job = null
    }
}
