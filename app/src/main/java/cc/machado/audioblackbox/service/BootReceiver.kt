package cc.machado.audioblackbox.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import cc.machado.audioblackbox.settings.DataStoreRecordingPreferences
import cc.machado.audioblackbox.settings.RecordingPreferences
import cc.machado.audioblackbox.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Listens for system reboot ([Intent.ACTION_BOOT_COMPLETED]) and app update ([Intent.ACTION_MY_PACKAGE_REPLACED])
 * as well as notification actions for the resume prompt (issue #79).
 *
 * Invariant: Never calls Context.startForegroundService directly on boot/update broadcasts.
 * On Android 14+ (API 34+), starting a microphone FGS from the background throws
 * ForegroundServiceStartNotAllowedException. Instead, this receiver posts a 1-tap notification
 * prompt if recording was desired.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> handleBootOrReplaced(context)
            ACTION_RESUME -> handleResume(context)
            ACTION_DISMISS -> handleDismiss(context)
        }
    }

    private fun handleBootOrReplaced(context: Context) {
        executeAsync {
            val preferences = recordingPreferencesFactory(context)
            if (preferences.isRecordingDesired()) {
                promptNotificationShower(context)
            }
        }
    }

    private fun handleResume(context: Context) {
        executeAsync {
            promptNotificationCanceller(context)
            val preferences = recordingPreferencesFactory(context)
            preferences.setRecordingDesired(true)
            if (permissionChecker(context, Manifest.permission.RECORD_AUDIO)) {
                serviceStarter(context, intentFactory(context, RecorderService.ACTION_START))
            } else {
                activityStarter(context, activityIntentFactory(context))
            }
        }
    }

    private fun handleDismiss(context: Context) {
        executeAsync {
            promptNotificationCanceller(context)
            val preferences = recordingPreferencesFactory(context)
            preferences.setRecordingDesired(false)
        }
    }

    private fun executeAsync(block: suspend () -> Unit) {
        val pendingResult = try {
            goAsync()
        } catch (_: Throwable) {
            null
        }
        receiverScope.launch {
            try {
                block()
            } finally {
                try {
                    pendingResult?.finish()
                } catch (_: Throwable) {
                }
            }
        }
    }

    companion object {
        const val ACTION_RESUME = "cc.machado.audioblackbox.service.action.RESUME_RECORDING"
        const val ACTION_DISMISS = "cc.machado.audioblackbox.service.action.DISMISS_RESUME_PROMPT"

        // Injectable seams for unit testing
        var receiverScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var recordingPreferencesFactory: (Context) -> RecordingPreferences = { DataStoreRecordingPreferences(it) }
        var promptNotificationShower: (Context) -> Unit = { ResumeNotification.showPrompt(it) }
        var promptNotificationCanceller: (Context) -> Unit = { ResumeNotification.cancel(it) }
        var permissionChecker: (Context, String) -> Boolean = { ctx, perm ->
            ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
        }
        var serviceStarter: (Context, Intent) -> Unit = { ctx, intent ->
            ContextCompat.startForegroundService(ctx, intent)
        }
        var activityStarter: (Context, Intent) -> Unit = { ctx, intent ->
            ctx.startActivity(intent)
        }
        var intentFactory: (Context, String) -> Intent = { ctx, action ->
            Intent(ctx, RecorderService::class.java).setAction(action)
        }
        var activityIntentFactory: (Context) -> Intent = { ctx ->
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }

        fun resetTestOverrides() {
            receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            recordingPreferencesFactory = { DataStoreRecordingPreferences(it) }
            promptNotificationShower = { ResumeNotification.showPrompt(it) }
            promptNotificationCanceller = { ResumeNotification.cancel(it) }
            permissionChecker = { ctx, perm ->
                ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
            }
            serviceStarter = { ctx, intent ->
                ContextCompat.startForegroundService(ctx, intent)
            }
            activityStarter = { ctx, intent ->
                ctx.startActivity(intent)
            }
            intentFactory = { ctx, action ->
                Intent(ctx, RecorderService::class.java).setAction(action)
            }
            activityIntentFactory = { ctx ->
                Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            }
        }
    }
}
