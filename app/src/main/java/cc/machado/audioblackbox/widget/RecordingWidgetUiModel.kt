package cc.machado.audioblackbox.widget

import androidx.annotation.StringRes
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.audio.CaptureState

/**
 * Which annunciator lamp color the widget's status dot renders (issue #275, `AGENTS.md` §5's
 * documented rule): green = recording, amber = paused, red = error. [IDLE_DIM] is not one of the
 * three documented state colors -- it is the "lamp off" reading for a state that is not
 * recording, paused, or erroring, kept visually distinct from all three so an idle widget never
 * reads as any of them.
 */
enum class WidgetAnnunciator {
    OK_GREEN,
    CAUTION_AMBER,
    WARNING_RED,
    IDLE_DIM,
}

/**
 * Everything [cc.machado.audioblackbox.widget.RecordingWidgetRenderer] needs to paint one
 * `RemoteViews` instance, expressed as plain resource ids and enums with no Android framework
 * dependency -- deliberately so [RecordingWidgetStateMapper.map] is testable on the plain JVM
 * (this repo has no Robolectric; see `PeriodicNotificationRefresherTest`'s doc for the same
 * constraint elsewhere).
 */
data class RecordingWidgetUiModel(
    @StringRes val statusTextRes: Int,
    val annunciator: WidgetAnnunciator,
    @StringRes val actionButtonLabelRes: Int,
    @StringRes val actionButtonDescriptionRes: Int,
    @StringRes val rootContentDescriptionRes: Int,
    /** `true` when the button's `PendingIntent` must fire [cc.machado.audioblackbox.service.RecorderService.ACTION_STOP];
     * `false` when it must fire `ACTION_START`. Never both, never neither -- see the mapper's doc. */
    val actionIsStop: Boolean,
)

/**
 * Maps real [CaptureState] -- and only real [CaptureState], never a value cached or guessed at
 * tap time -- to what the widget renders (issue #275).
 *
 * This is the exact failure class that made the removed Quick Settings tile unusable (issue
 * #267/#273): the tile's on-screen appearance flipped to "on" at tap time, independently of
 * whether capture actually started, so a crashed start still looked like a running recording. A
 * `RemoteViews`-based widget cannot make that mistake by construction -- tapping the widget's
 * button does not run any of this app's code at all, it only fires a `PendingIntent` at
 * [cc.machado.audioblackbox.service.RecorderService]; the *only* place this app ever repaints the
 * widget is [cc.machado.audioblackbox.widget.RecordingWidgetRenderer.render], which always reads
 * this mapper fed by the real, live
 * [cc.machado.audioblackbox.service.RecorderService.captureState]. There is no second, faster path
 * that could show a guess ahead of it.
 *
 * [CaptureState.Idle] is also exactly the value [cc.machado.audioblackbox.service.RecorderService]'s
 * companion object defaults to on every fresh process (a crash, an OS reclaim, a reboot) -- so
 * feeding this mapper that value after a process death renders the same honest "not recording"
 * widget a user would see after a clean stop, never a stale "on" left over from before the
 * process died. See [RecordingWidgetStateMapperTest] for the test asserting exactly this.
 */
object RecordingWidgetStateMapper {

    fun map(captureState: CaptureState): RecordingWidgetUiModel = when (captureState) {
        is CaptureState.Recording -> RecordingWidgetUiModel(
            statusTextRes = R.string.widget_status_recording,
            annunciator = WidgetAnnunciator.OK_GREEN,
            actionButtonLabelRes = R.string.widget_action_stop,
            actionButtonDescriptionRes = R.string.widget_action_stop_description,
            rootContentDescriptionRes = R.string.widget_content_description_recording,
            actionIsStop = true,
        )

        is CaptureState.Paused -> RecordingWidgetUiModel(
            statusTextRes = R.string.widget_status_paused,
            annunciator = WidgetAnnunciator.CAUTION_AMBER,
            actionButtonLabelRes = R.string.widget_action_stop,
            actionButtonDescriptionRes = R.string.widget_action_stop_description,
            rootContentDescriptionRes = R.string.widget_content_description_paused,
            actionIsStop = true,
        )

        // A refused/crashed start can never actually reach this branch today -- see this file's
        // class doc and the PR description for why (RecorderService.onStartCommand calls
        // startForeground() before engine.start() ever runs, so a while-in-use refusal kills the
        // process before CaptureState.Error is ever set). This branch exists, and is tested, for
        // every *other* way capture can fail (AudioRecord init failure, unsupported config,
        // issue #272's OOM refusal path) -- covering it here is what "never a silent crash and
        // never a stale on" means for the failure modes this widget's own code can actually see.
        is CaptureState.Error -> RecordingWidgetUiModel(
            statusTextRes = R.string.widget_status_error,
            annunciator = WidgetAnnunciator.WARNING_RED,
            actionButtonLabelRes = R.string.widget_action_start,
            actionButtonDescriptionRes = R.string.widget_action_start_description,
            rootContentDescriptionRes = R.string.widget_content_description_error,
            actionIsStop = false,
        )

        is CaptureState.Idle -> RecordingWidgetUiModel(
            statusTextRes = R.string.widget_status_idle,
            annunciator = WidgetAnnunciator.IDLE_DIM,
            actionButtonLabelRes = R.string.widget_action_start,
            actionButtonDescriptionRes = R.string.widget_action_start_description,
            rootContentDescriptionRes = R.string.widget_content_description_idle,
            actionIsStop = false,
        )
    }
}
