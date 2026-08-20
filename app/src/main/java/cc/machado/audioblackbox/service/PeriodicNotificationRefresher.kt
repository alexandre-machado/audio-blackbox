package cc.machado.audioblackbox.service

import cc.machado.audioblackbox.audio.CaptureState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Drives a periodic notification refresh while capture is steadily [CaptureState.Recording]
 * (issue #30). [RecorderService.onCreate]'s existing `engine.state.collect { refreshNotification()
 * }` only fires on a *transition* -- a [StateFlow] never re-emits the same value -- so while
 * recording runs steadily, nothing ever calls `refreshNotification()` again, and the buffered
 * duration text (which keeps changing every second, unlike [CaptureState] itself) freezes at
 * whatever it was the last time some other transition happened to rebuild it. This class supplies
 * exactly the missing "keep refreshing while nothing transitions" tick; it does not replace the
 * transition-driven collector, which still owns "refresh instantly on every state change" and
 * stays untouched (see [RecorderService.onCreate]).
 *
 * ## Why a tick and not a buffered-duration flow or a chronometer
 * A `flow { while (true) { emit(bufferedDurationMillis()); delay(...) } }` combined with
 * `engine.state` would need the same delay-loop-per-Recording-run shape as this class anyway (it
 * still has to stop ticking outside Recording), so it does not remove any complexity, only moves
 * where the polling loop lives. `NotificationCompat.Builder.setUsesChronometer`/`setWhen` was
 * rejected because their semantics are elapsed *wall-clock* time since a fixed anchor, which is
 * not the same number as buffered *content* duration -- the two diverge the moment the ring
 * buffer saturates and buffered duration pins while wall-clock time keeps climbing (see the
 * saturation case below); a chronometer cannot represent "pinned" without being manually stopped
 * and restarted every tick anyway, at which point it buys nothing over calling `notify()`
 * directly.
 *
 * ## Cadence and cost
 * [intervalMillis] defaults to 10 seconds. [RecorderNotification]'s buffered-duration text has
 * `mm:ss` (one-second) resolution, and this is a silent, `IMPORTANCE_LOW`, `setOnlyAlertOnce`
 * notification a user only glances at -- refreshing faster than a human can perceive would only
 * spend battery for no visible benefit. Each tick is one `NotificationManager.notify()} call on
 * an already-posted notification (no heads-up, no sound/vibration per the channel/builder setup
 * in [RecorderNotification]); this class holds no wakelock, and schedules nothing through
 * `AlarmManager`/`WorkManager`, so at a 6-per-minute cadence over a run of hours the cost is
 * bounded to that many cheap in-process `notify()` calls -- it cannot independently keep the
 * device out of Doze the way a wakelock or exact alarm would.
 *
 * ## Lifecycle
 * [run] is a suspend function meant to be launched in the same [kotlinx.coroutines.CoroutineScope]
 * as the transition-driven collector ([RecorderService.serviceScope]). [collectLatest] cancels
 * the in-flight delay/tick loop the instant [state] emits *anything* -- a transition out of
 * Recording included -- which is exactly "must stop when capture stops": leaving Recording tears
 * the loop down with no separate stop() call needed, and re-entering Recording (a fresh
 * [collectLatest] value) starts a fresh loop. Cancelling the caller's scope (as
 * `RecorderService.onDestroy()` already does) cancels whatever this is suspended in, so nothing
 * here can outlive that scope's cancellation.
 */
class PeriodicNotificationRefresher(
    private val state: StateFlow<CaptureState>,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) {
    suspend fun run(onTick: suspend () -> Unit) {
        state.collectLatest { current ->
            if (current is CaptureState.Recording) {
                while (true) {
                    delay(intervalMillis)
                    onTick()
                }
            }
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS = 10_000L
    }
}
