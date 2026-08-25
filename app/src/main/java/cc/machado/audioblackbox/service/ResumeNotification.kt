package cc.machado.audioblackbox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.ui.MainActivity

/**
 * Builds and posts the 1-tap notification prompting the user to resume continuous recording
 * after a device reboot or app update (issue #79).
 */
object ResumeNotification {

    const val CHANNEL_ID = "recording_resume_prompt"
    const val NOTIFICATION_ID = 2

    private const val REQUEST_CODE_CONTENT = 200
    private const val REQUEST_CODE_RESUME = 201
    private const val REQUEST_CODE_DISMISS = 202
    private const val REQUEST_CODE_DELETE = 203

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_resume_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_resume_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context): Notification {
        ensureChannel(context)

        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_CONTENT,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val resumeIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_RESUME,
            Intent(context, BootReceiver::class.java).setAction(BootReceiver.ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val dismissIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_DISMISS,
            Intent(context, BootReceiver::class.java).setAction(BootReceiver.ACTION_DISMISS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val deleteIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_DELETE,
            Intent(context, BootReceiver::class.java).setAction(BootReceiver.ACTION_DISMISS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentTitle(context.getString(R.string.notification_resume_title))
            .setContentText(context.getString(R.string.notification_resume_content))
            .setContentIntent(contentIntent)
            .setDeleteIntent(deleteIntent)
            .setAutoCancel(true)
            .addAction(
                0,
                context.getString(R.string.notification_resume_action_resume),
                resumeIntent,
            )
            .addAction(
                0,
                context.getString(R.string.notification_resume_action_dismiss),
                dismissIntent,
            )
            .build()
    }

    fun showPrompt(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, build(context))
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(NOTIFICATION_ID)
    }
}
