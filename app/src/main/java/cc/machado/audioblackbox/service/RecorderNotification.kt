package cc.machado.audioblackbox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.audio.QualityPreset
import cc.machado.audioblackbox.export.ExportState
import cc.machado.audioblackbox.export.ForwardRecordingState
import cc.machado.audioblackbox.ui.MainActivity
import cc.machado.audioblackbox.ui.specLabelRes
import java.util.Locale

/**
 * Builds the persistent low-importance notification [RecorderService] runs as, and the
 * `NotificationChannel` it lives on. Pure construction -- no Android context is retained across
 * calls -- so [RecorderService] owns the lifecycle and this stays a plain function holder.
 */
object RecorderNotification {

    const val CHANNEL_ID = "recorder_service"
    const val NOTIFICATION_ID = 1

    private const val REQUEST_CODE_CONTENT = 100
    private const val REQUEST_CODE_SAVE = 101
    private const val REQUEST_CODE_STOP = 102
    private const val REQUEST_CODE_STOP_FORWARD = 103
    private const val REQUEST_CODE_START_FORWARD = 104

    /** Idempotent: `NotificationManager.createNotificationChannel` is itself a no-op when the
     * channel already exists with the same id. No API-level guard needed here: `minSdk` is 29
     * (see `app/build.gradle.kts`), well above the API-26 floor `NotificationChannel` requires. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.recorder_notification_channel_name),
            // Low importance, no sound/vibration: this notification is mandatory (Android
            // requires one for a foreground service) but must not be intrusive -- it fires
            // continuously for as long as recording runs.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.recorder_notification_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Builds the current notification content for [state]/[bufferedDurationMillis]/[exportState]/[forwardRecordingState].
     * Called on every [RecorderService.onStartCommand] so the shown state and buffered duration
     * stay fresh, and via `NotificationManager.notify` whenever [RecorderService] observes a
     * [CaptureState], [ExportState], or [ForwardRecordingState] change while already running.
     *
     * ## Placement of the active quality preset (issue #341)
     * The owner's ask was "maybe the first line" -- a suggestion, not a spec -- so this weighs the
     * three real options rather than assuming it:
     *  - **Chosen: append to the title**, after the state word (see [titleFor]). The state word is
     *    the single most important fact in this notification and must never be displaced; keeping
     *    it *first* means that on the rare narrow-width device where the title's tail clips, only
     *    the added preset text is at risk, never the state.
     *  - Appending to the second line instead was rejected: that line already stacks
     *    [bufferedText] with an optional export/forward status (`extraStatus`) ahead of it, and is
     *    already the more crowded of the two lines -- piling a third fact onto it raises the odds
     *    of truncating something already there, not just the new addition.
     *  - `setSubText` was rejected too: its header-row slot is meant for a short qualifier like an
     *    account name, competes for width with the timestamp Android renders in the same row, and
     *    is a less prominent read than either content line -- worse visibility for the fact this
     *    issue exists to surface, not better.
     *
     * [QualityPresetFormat.specLabelRes] (the full form, e.g. `"44.1 kHz · Estéreo"`), not
     * `dashboardTagLabelRes`'s `S`/`M` abbreviation -- that shorthand was an owner-decided
     * dashboard-card-only choice (issue #337, tight header-tag width), not a general convention.
     * Checked against the longest realistic case: pt-BR's `"Pausado (microfone em uso)"` plus
     * `"44.1 kHz · Estéreo"` is `"Pausado (microfone em uso) · 44.1 kHz · Estéreo"` (48 chars) --
     * long, and free to clip at its tail on a narrow device, but the state word stays intact by
     * construction since it is always the leading segment.
     *
     * Updates mid-session within [PeriodicNotificationRefresher]'s existing tick cadence
     * (`DEFAULT_INTERVAL_MILLIS`, 10s): a preset switch while Recording
     * (`RecorderService.switchQualityPreset`) does not itself transition [CaptureState], so the
     * transition-driven collector in [RecorderService.onCreate] would not catch it alone -- but the
     * periodic ticker already calls [refreshNotification][RecorderService.refreshNotification]
     * unconditionally every tick, which re-reads `qualityPreset` fresh each time (see
     * [RecorderService.currentNotification]) the same way it already re-reads buffered duration.
     * No new refresh trigger was needed for this to work.
     *
     * Does not touch the home-screen widget: `widget_status_*` (see `values/strings.xml`) is a
     * wholly separate, deliberately shorter string set with its own narrow-cell reasoning already
     * documented there -- it shares no string resource with this notification.
     */
    fun build(
        context: Context,
        state: CaptureState,
        bufferedDurationMillis: Long?,
        exportState: ExportState = ExportState.Idle,
        capacityMinutes: Int = RecorderService.bufferDurationMinutes,
        forwardRecordingState: ForwardRecordingState = ForwardRecordingState.Idle,
        bytesPerSecond: Int = cc.machado.audioblackbox.audio.AudioConfig.DEFAULT_SAMPLE_RATE_HZ * 2,
        qualityPreset: QualityPreset = QualityPreset.DEFAULT,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_CONTENT,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val saveIntent = actionPendingIntent(context, RecorderService.ACTION_SAVE, REQUEST_CODE_SAVE)
        val stopIntent = actionPendingIntent(context, RecorderService.ACTION_STOP, REQUEST_CODE_STOP)
        val startForwardIntent = actionPendingIntent(context, RecorderService.ACTION_START_FORWARD, REQUEST_CODE_START_FORWARD)
        val stopForwardIntent = actionPendingIntent(context, RecorderService.ACTION_STOP_FORWARD, REQUEST_CODE_STOP_FORWARD)

        val stateText = context.getString(
            when (state) {
                is CaptureState.Recording -> R.string.recorder_notification_state_recording
                is CaptureState.Paused -> R.string.recorder_notification_state_paused
                is CaptureState.Error -> R.string.recorder_notification_state_error
                is CaptureState.Idle -> R.string.recorder_notification_state_idle
            },
        )
        val presetText = context.getString(qualityPreset.specLabelRes())
        val titleText = titleFor(stateText, presetText)
        val bufferedText = context.getString(
            R.string.recorder_notification_buffered,
            formatDuration(bufferedDurationMillis ?: 0L),
        )

        val exportText = when (exportState) {
            is ExportState.Idle -> null
            is ExportState.Exporting -> context.getString(R.string.recorder_notification_export_exporting)
            is ExportState.Success ->
                context.getString(R.string.recorder_notification_export_success, exportState.displayName)
            is ExportState.Error -> context.getString(R.string.recorder_notification_export_error)
        }

        val forwardElapsedMillis = if (bytesPerSecond > 0 && forwardRecordingState is ForwardRecordingState.Recording) {
            (forwardRecordingState.bytesWritten * 1000L) / bytesPerSecond
        } else {
            0L
        }

        val forwardText = when (forwardRecordingState) {
            is ForwardRecordingState.Idle -> null
            is ForwardRecordingState.Recording ->
                context.getString(R.string.recorder_notification_forward_recording, formatDuration(forwardElapsedMillis))
            is ForwardRecordingState.Success ->
                context.getString(R.string.recorder_notification_forward_success, forwardRecordingState.displayName)
            is ForwardRecordingState.Error ->
                context.getString(R.string.recorder_notification_forward_error)
        }

        val extraStatus = when {
            forwardText != null && exportText != null -> "$forwardText · $exportText"
            forwardText != null -> forwardText
            exportText != null -> exportText
            else -> null
        }

        val contentText = if (extraStatus != null) "$extraStatus · $bufferedText" else bufferedText

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentTitle(titleText)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(
                0,
                saveActionLabel(context, bufferedDurationMillis ?: 0L, capacityMinutes),
                saveIntent,
            )

        if (forwardRecordingState is ForwardRecordingState.Recording) {
            builder.addAction(
                0,
                context.getString(R.string.recorder_notification_action_stop_forward),
                stopForwardIntent,
            )
        } else {
            builder.addAction(
                0,
                context.getString(R.string.recorder_notification_action_start_forward),
                startForwardIntent,
            )
        }

        builder.addAction(0, context.getString(R.string.recorder_notification_action_stop), stopIntent)
        return builder.build()
    }

    /**
     * Issue #121's mislabel fix: names what a tap right now would actually save, not the
     * configured retention window -- [RecorderService.resolveSavedMinutes] is the same oracle
     * [cc.machado.audioblackbox.ui.dashboard.DashboardViewModel]'s Save button description uses,
     * so the two surfaces can never disagree about which quantity "N min" refers to.
     * [PeriodicNotificationRefresher] keeps this within its tick cadence of the real buffered
     * amount while recording.
     *
     * Floors to whole minutes for the common case (a compact notification action has no room for
     * mm:ss precision), but never renders the "0 min" issue #129 flagged as misleading: once
     * something is buffered but not yet a full minute, this switches to whole seconds instead --
     * still floored, so it never overstates either. An empty buffer (0 ms, 0 s) is the one case
     * where "0" is simply true, not a mislabel.
     */
    private fun saveActionLabel(context: Context, bufferedDurationMillis: Long, capacityMinutes: Int): String {
        val savedMinutes = RecorderService.resolveSavedMinutes(bufferedDurationMillis, capacityMinutes)
        return if (savedMinutes > 0) {
            context.getString(R.string.recorder_notification_action_save, savedMinutes)
        } else {
            // Same oracle RecorderService.handleSave()'s exported filename now uses for this same
            // sub-minute case (issue #129 follow-up) -- calling it here too, rather than
            // re-deriving the seconds arithmetic locally, is what keeps the notification and the
            // filename from being able to drift apart on this narrower case.
            val savedSeconds = RecorderService.resolveSavedSeconds(bufferedDurationMillis)
            context.getString(R.string.recorder_notification_action_save_seconds, savedSeconds)
        }
    }

    private fun actionPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, RecorderService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * Composes the notification title from the already-localized state word and preset label
     * (issue #341). A plain, `Context`-free function -- unlike [build] itself -- specifically so a
     * JVM unit test can pin the composition (state word first, `" · "` separator, preset label
     * appended) without a Robolectric `Context` this repo's JVM tier does not have (see
     * `QualityPresetFormatTest`'s doc for the same constraint).
     */
    internal fun titleFor(stateText: String, presetText: String): String = "$stateText · $presetText"

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
