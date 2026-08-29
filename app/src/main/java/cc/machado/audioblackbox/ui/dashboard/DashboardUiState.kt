package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.CaptureErrorReason
import cc.machado.audioblackbox.audio.QualityPreset
import cc.machado.audioblackbox.export.ExportFailureReason
import cc.machado.audioblackbox.export.ForwardRecordingFailureReason

/** UI-facing mirror of [cc.machado.audioblackbox.audio.CaptureState], mapped 1:1 by
 * [DashboardViewModel] -- see its `mapCaptureStatus` -- so the screen never needs to import the
 * engine's own sealed type. */
sealed interface CaptureStatus {
    data object Idle : CaptureStatus
    data object Recording : CaptureStatus
    data object Paused : CaptureStatus
    data class Error(val reason: CaptureErrorReason, val message: String) : CaptureStatus
}

/**
 * What the primary Material 3 [androidx.compose.material3.Switch] on the dashboard (issue #46)
 * should render for one [CaptureStatus], produced by [DashboardViewModel.mapEngineSwitchState] --
 * the single oracle for "is the switch on, off, disabled, or showing an error" so [DashboardScreen]
 * never re-derives that from [CaptureStatus] itself.
 *
 * ## Paused (the hardest part -- see issue #46)
 * [CaptureStatus.Paused] maps to [checked] == `true`, not a third visual position: the recording
 * *mode* the user turned on is still engaged (the engine still holds `AudioRecord` and will resume
 * writing the instant the phone call ends -- see [cc.machado.audioblackbox.audio.AudioCaptureEngine.resume]),
 * so telling the user "this switch is off" would be a lie about what happens next. What must not
 * happen is Paused looking identical to plain Recording: [paused] is `true` only for this state so
 * the screen can give it its own supporting text/color, distinct from both Recording and Off.
 *
 * ## Pending / transitional (the other hard part)
 * [enabled] is `false` and [pending] is `true` while a user-initiated start/stop is in flight (see
 * [DashboardViewModel.toggleEngine]) -- this project chose *disabled-while-transitioning* over an
 * indeterminate third switch position: [checked] is deliberately left at whatever it already was
 * (the real, last-known [CaptureStatus]) rather than optimistically flipped, so a start that later
 * fails never has to be silently snapped back -- the switch simply never moved. See
 * [DashboardViewModel.mapEngineSwitchState]'s doc for exactly how [pending] is cleared once the
 * real outcome (Recording/Paused/Error/Idle) arrives.
 */
data class EngineSwitchUiState(
    /** Physical on/off position of the [androidx.compose.material3.Switch] thumb. Always derived
     * from the real [CaptureStatus] -- never set ahead of it (see class doc). */
    val checked: Boolean,
    /** `false` while [pending] is `true` (disabled-while-transitioning) -- the user cannot fire a
     * second toggle before the first one's real outcome is known. */
    val enabled: Boolean,
    /** `true` only while a user-initiated start/stop request has been dispatched but
     * [CaptureStatus] has not yet changed to reflect its outcome. */
    val pending: Boolean,
    /** `true` only for [CaptureStatus.Paused] -- see class doc's "Paused" section. */
    val paused: Boolean,
    /** Non-null only for [CaptureStatus.Error], carried through unchanged so the screen can show
     * what failed. The switch stays actionable (flipping it again attempts a fresh start via
     * [DashboardViewModel.toggleEngine]'s `Idle`/`Error` branch) -- an error never gets swallowed
     * by the switch simply sitting in the "off" position. */
    val error: CaptureStatus.Error?,
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

/** Everything [DashboardScreen] needs to render one frame, produced by
 * [DashboardViewModel.mapUiState] from the engine's raw state plus the buffered duration --
 * this is exactly the function issue #6 requires a unit-tested oracle for.
 *
 * The retention-window control (issue #45) moved to
 * [cc.machado.audioblackbox.ui.settings.SettingsScreen] as of issue #73 -- this state no longer
 * carries it; [capacityMillis] still reflects whatever the settings screen has committed, since
 * that comes from [cc.machado.audioblackbox.service.RecorderService]'s own reactive capacity, not
 * a value this screen owns. */
/** Observable lifecycle of forward continuous recording on the dashboard. */
sealed interface ForwardRecordingUiState {
    data object Idle : ForwardRecordingUiState
    data class Recording(val displayName: String, val elapsedMillis: Long) : ForwardRecordingUiState
    data class Success(val displayName: String, val bytesWritten: Long) : ForwardRecordingUiState
    data class Error(val reason: ForwardRecordingFailureReason, val message: String) : ForwardRecordingUiState
}

data class DashboardUiState(
    val captureStatus: CaptureStatus,
    val engineSwitch: EngineSwitchUiState,
    val bufferedMillis: Long,
    val capacityMillis: Long,
    val isBufferFull: Boolean,
    val saveState: SaveUiState,
    val forwardRecordingState: ForwardRecordingUiState = ForwardRecordingUiState.Idle,
    val qualityPreset: QualityPreset = QualityPreset.VOICE,
    /** Live microphone peak level in `0f..1f`, measured from the captured PCM by
     * [cc.machado.audioblackbox.audio.AudioLevel.peakLevel]. Always `0f` unless
     * [captureStatus] is [CaptureStatus.Recording] -- see [DashboardViewModel.mapUiState]. */
    val inputLevel: Float = 0f,
)
