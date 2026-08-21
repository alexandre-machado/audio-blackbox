package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.CaptureErrorReason

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

    /** The buffer holds enough audio, but [cc.machado.audioblackbox.service.RecorderService]'s
     * `ACTION_SAVE` has no way to request less than everything currently buffered -- see the
     * gap flagged on issue #6/#5. Exporting this option today would either silently produce a
     * longer file than requested or require the UI to trim audio itself, both of which the
     * issue explicitly forbids, so it stays disabled with this reason until the service exposes
     * a window-minutes parameter. */
    PARTIAL_WINDOW_NOT_SUPPORTED,
}

/** One entry in the "salvar o passado" window selector. */
data class WindowOption(
    val minutes: Int,
    val availableMinutes: Int,
    val enabled: Boolean,
    val disabledReason: WindowDisabledReason?,
)

/** Observable lifecycle of a save request, as the dashboard screen sees it. Unlike
 * [cc.machado.audioblackbox.export.ExportState] (which [DashboardViewModel] cannot currently
 * observe in real time -- see the gap noted on issue #6), [Requested] only means the intent was
 * sent, not that the export has finished; the real outcome today only surfaces in the
 * persistent notification. */
sealed interface SaveUiState {
    data object Idle : SaveUiState
    data class Requested(val minutes: Int) : SaveUiState
}

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
) {
    companion object {
        val WINDOW_OPTION_MINUTES = listOf(5, 15, 30)
    }
}
