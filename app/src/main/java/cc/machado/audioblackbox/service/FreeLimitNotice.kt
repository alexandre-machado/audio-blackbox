package cc.machado.audioblackbox.service

import cc.machado.audioblackbox.billing.Entitlement
import cc.machado.audioblackbox.export.ForwardRecordingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Issue #443 follow-up (PR #447 `@rev` finding 5). A live session stopped by the free cap keeps
 * its terminal `Success(freeLimitReached = true)` state on purpose, so the ongoing notification and
 * the dashboard keep saying why it stopped. Once the user is Pro that message ("Pro removes this
 * limit") is no longer true, so this calls [acknowledge] (the engine's
 * `acknowledgeTerminalState`, which resets to Idle and is a no-op unless the state is still
 * terminal) the moment both conditions hold. Both surfaces render from that one state, so both
 * clear together.
 *
 * Only the cap outcome is acted on. A user-stopped `Success` keeps its own short visibility timer
 * in [RecorderService], and Free or Unknown never clears the notice.
 */
internal suspend fun clearFreeLimitNoticeOnPro(
    forwardState: Flow<ForwardRecordingState>,
    entitlement: Flow<Entitlement>,
    acknowledge: () -> Unit,
) {
    combine(forwardState, entitlement) { state, current ->
        state is ForwardRecordingState.Success && state.freeLimitReached && current is Entitlement.Pro
    }
        .distinctUntilChanged()
        .collect { superseded -> if (superseded) acknowledge() }
}
