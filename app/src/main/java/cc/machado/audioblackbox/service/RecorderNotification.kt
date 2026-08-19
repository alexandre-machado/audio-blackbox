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

    /** Builds the current notification content for [state]/[bufferedDurationMillis]. Called on
     * every [RecorderService.onStartCommand] so the shown state and buffered duration stay
     * fresh, and via `NotificationManager.notify` whenever [RecorderService] observes a state
     * change while already running. */
    fun build(context: Context, state: CaptureState, bufferedDurationMillis: Long?): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_CONTENT,
            Intent(context, MainActivity::class.java).apply {
                // NEW_TASK is required because this PendingIntent is built from a Service
                // context, not an Activity one. Combined with MainActivity's
                // android:launchMode="singleTop" in the manifest, SINGLE_TOP here means tapping
                // the notification body brings the existing MainActivity instance to the front
                // instead of creating a duplicate one.
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val saveIntent = actionPendingIntent(context, RecorderService.ACTION_SAVE, REQUEST_CODE_SAVE)
        val stopIntent = actionPendingIntent(context, RecorderService.ACTION_STOP, REQUEST_CODE_STOP)

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

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentTitle(stateText)
            .setContentText(bufferedText)
            .setOngoing(true)
            // Suppresses a fresh heads-up/sound on every content refresh (e.g. the buffered
            // duration ticking up) -- only the very first post of this notification should ever
            // be noticeable, and the channel itself is already IMPORTANCE_LOW/no-sound.
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.recorder_notification_action_save), saveIntent)
            .addAction(0, context.getString(R.string.recorder_notification_action_stop), stopIntent)
            .build()
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
