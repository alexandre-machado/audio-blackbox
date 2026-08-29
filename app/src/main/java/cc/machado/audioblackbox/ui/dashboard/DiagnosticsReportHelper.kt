package cc.machado.audioblackbox.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import cc.machado.audioblackbox.BuildConfig
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.audio.QualityPreset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsReportHelper {

    fun buildSaveErrorReport(
        reason: String,
        message: String,
        preset: QualityPreset,
        capacityMinutes: Int,
        bufferedMillis: Long,
        timestampMillis: Long = System.currentTimeMillis(),
    ): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMillis))
        val bufferedSec = bufferedMillis / 1000L
        return """
=== AUDIO BLACKBOX INCIDENT REPORT ===
Timestamp: $timestamp
App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})
Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})

[FAILURE CONTEXT]
Type: Audio Save / Export Failure
Reason Code: $reason
Message: $message

[AUDIO ENGINE SPECS]
Active Preset: ${preset.name} (${preset.sampleRateHz} Hz, ${if (preset.channelCount == 1) "Mono" else "Stereo"})
Configured Capacity: $capacityMinutes min
Buffered Audio at Failure: ${bufferedSec}s (${bufferedMillis} ms)
======================================
""".trimIndent()
    }

    fun buildForwardErrorReport(
        reason: String,
        message: String,
        preset: QualityPreset,
        capacityMinutes: Int,
        timestampMillis: Long = System.currentTimeMillis(),
    ): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMillis))
        return """
=== AUDIO BLACKBOX INCIDENT REPORT ===
Timestamp: $timestamp
App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})
Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})

[FAILURE CONTEXT]
Type: Continuous Live Recording Failure
Reason Code: $reason
Message: $message

[AUDIO ENGINE SPECS]
Active Preset: ${preset.name} (${preset.sampleRateHz} Hz, ${if (preset.channelCount == 1) "Mono" else "Stereo"})
Configured Capacity: $capacityMinutes min
======================================
""".trimIndent()
    }

    fun shareReport(context: Context, report: String, chooserTitle: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Audio Blackbox Incident Report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        val chooser = Intent.createChooser(sendIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun copyToClipboard(context: Context, report: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Audio Blackbox Diagnostics", report)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, context.getString(R.string.dashboard_error_copied_toast), Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            // Ignore clipboard failure on restricted OEM environments
        }
    }
}
