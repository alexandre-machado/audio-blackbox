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
import cc.machado.audioblackbox.export.ExportState
import cc.machado.audioblackbox.export.ForwardRecordingState
import cc.machado.audioblackbox.ui.MainActivity
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
     * [CaptureState], [ExportState], or [ForwardRecordingState] change while already running. */
    fun build(
        context: Context,
        state: CaptureState,
        bufferedDurationMillis: Long?,
        exportState: ExportState = ExportState.Idle,
        capacityMinutes: Int = RecorderService.bufferDurationMinutes,
        forwardRecordingState: ForwardRecordingState = ForwardRecordingState.Idle,
        bytesPerSecond: Int = cc.machado.audioblackbox.audio.AudioConfig.DEFAULT_SAMPLE_RATE_HZ * 2,
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
            .setContentTitle(stateText)
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

    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
