package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.CaptureErrorReason
import cc.machado.audioblackbox.export.ExportFailureReason

/** UI-facing mirror of [cc.machado.audioblackbox.audio.CaptureState], mapped 1:1 by
 * [DashboardViewModel] -- see its `mapCaptureStatus` -- so the screen never needs to import the
 * engine's own sealed type. */
sealed interface CaptureStatus {
    data object Idle : CaptureStatus
    data object Recording : CaptureStatus
    data object Paused : CaptureStatus
    data class Error(val reason: CaptureErrorReason, val message: String) : CaptureStatus
}

/** Why a [WindowOption] can't be requested right now. */
enum class WindowDisabledReason {
    /** The ring buffer simply does not hold this many minutes of audio yet. */
    INSUFFICIENT_BUFFER,
}

/** One entry in the "salvar o passado" window selector. */
data class WindowOption(
    val minutes: Int,
    val availableMinutes: Int,
    val enabled: Boolean,
    val disabledReason: WindowDisabledReason?,
)

/** Observable lifecycle of a save request, as the dashboard screen sees it -- a direct mirror of
 * [cc.machado.audioblackbox.export.ExportState] (issue #40 item 2: [RecorderService][cc.machado.audioblackbox.service.RecorderService]
 * now publishes that StateFlow from its companion, the same way it already does for `engine.state`),
 * minus [cc.machado.audioblackbox.export.ExportState.Idle] and
 * [cc.machado.audioblackbox.export.ExportState.Exporting] which map 1:1 by name -- see
 * [DashboardViewModel.mapSaveUiState]. [Success]/[Error] can be individually dismissed by the user
 * before the service's own [cc.machado.audioblackbox.export.ExportEngine.acknowledgeTerminalState]
 * timeout resets the underlying [cc.machado.audioblackbox.export.ExportState] back to `Idle` --
 * see [DashboardViewModel.dismissSaveNotice]. */
sealed interface SaveUiState {
    data object Idle : SaveUiState
    data object Exporting : SaveUiState

    /** [displayName] is the filename [cc.machado.audioblackbox.export.ExportEngine] wrote, e.g.
     * `blackbox_2026-08-21_10-15-00_5min.m4a` -- this is [DashboardScreen]'s on-screen success
     * confirmation naming the saved file. Deliberately carries nothing more: a direct path to open
     * the file in the gallery/share it (the rest of issue #6's "success confirmation" criterion)
     * needs the gallery, which does not exist yet -- see issue #7. Nothing here is a placeholder
     * that pretends that part is done; it is simply not attempted until #7 lands. */
    data class Success(val displayName: String) : SaveUiState

    /** [reason]/[message] are [cc.machado.audioblackbox.export.ExportState.Error]'s own fields,
     * carried through unchanged so a failure is visible on this screen -- not only in the
     * persistent notification, which was the whole gap issue #40 item 2 closes. */
    data class Error(val reason: ExportFailureReason, val message: String) : SaveUiState
}

/** One entry in the retention-window selector (issue #45) -- how many minutes of audio the ring
 * buffer is *configured* to hold, a different concept from [WindowOption] above (how much of
 * what's buffered a single "salvar o passado" tap writes to a file). [approxRamMb] is shown
 * directly in the UI, per the issue's "the user is spending their device's memory" requirement --
 * see [DashboardViewModel.computeRetentionSection] for the arithmetic. */
data class RetentionWindowOption(
    val minutes: Int,
    val approxRamMb: Int,
    val selected: Boolean,
)

/** The retention-window section's full state, including whether a requested change is currently
 * waiting on the user's explicit discard confirmation (issue #45's core safety requirement:
 * changing this while the engine is running would discard whatever is buffered, so it must never
 * apply silently). [pendingConfirmationMinutes] is null unless that confirmation is pending. */
data class RetentionSectionUiState(
    val options: List<RetentionWindowOption>,
    val pendingConfirmationMinutes: Int?,
)

/** Everything [DashboardScreen] needs to render one frame, produced by
 * [DashboardViewModel.mapUiState] from the engine's raw state plus the buffered duration --
 * this is exactly the function issue #6 requires a unit-tested oracle for. */
data class DashboardUiState(
    val captureStatus: CaptureStatus,
    val bufferedMillis: Long,
    val capacityMillis: Long,
    val isBufferFull: Boolean,
    val windowOptions: List<WindowOption>,
    val saveState: SaveUiState,
    val retentionSection: RetentionSectionUiState,
) {
    companion object {
        val WINDOW_OPTION_MINUTES = listOf(5, 15, 30)
    }
}
