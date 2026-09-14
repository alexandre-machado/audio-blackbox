package cc.machado.audioblackbox.export

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import cc.machado.audioblackbox.CrashLogFileHolder
import cc.machado.audioblackbox.ErrorLogFileHolder
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.audio.QualityPreset
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Issue #388: exports the *entire* on-disk diagnostic log (not just the current page the
 * dashboard's `ErrorLogModal` shows) through the Android share sheet, reachable from Settings
 * regardless of whether an `ERROR` entry exists at all -- unlike the modal, which is only
 * reachable once one does (see [cc.machado.audioblackbox.ui.dashboard.ErrorLogUiState.hasVisibleErrors]).
 *
 * ## `FileProvider`, not `EXTRA_TEXT` -- and why
 * The two on-disk logs this bundles are capped independently and neither cap is small:
 * `export_errors.log` rotates at 5&nbsp;MB (`MAX_LOG_SIZE_BYTES` in this file) and keeps one
 * rotated `.old` generation of the same size, and `crash_log.log` rotates at 1&nbsp;MB
 * (`CRASH_LOG_MAX_SIZE_BYTES`) with its own `.old` generation -- a worst case around 12&nbsp;MB of
 * text. Android's Binder transaction buffer is roughly 1&nbsp;MB *shared across the whole
 * process*, and `Intent.EXTRA_TEXT` is marshalled straight through it: a report anywhere near its
 * plausible real-world size risks `TransactionTooLargeException` (either raised directly on this
 * app's own [Context.startActivity] call, or so close to the ceiling that a receiving app's own
 * Binder traffic can push it over). A shared *file*, referenced by a `content://` [android.net.Uri]
 * and read by the receiving app through its own file descriptor, never crosses the Binder
 * transaction buffer at all regardless of the report's size. `FileProvider` is therefore the only
 * one of the two options offered in the issue that does not have a silent, size-dependent failure
 * mode.
 *
 * ## The exposed surface is deliberately narrow
 * [FileProvider] here is wired (see `res/xml/diagnostics_file_paths.xml` and the
 * `<provider>` entry in `AndroidManifest.xml`) to expose *only* a dedicated `cache-path`
 * subdirectory (`diagnostics/`) that holds nothing but this function's own generated report file --
 * never `filesDir` (where the real, live `export_errors.log`/`crash_log.log` and every other app
 * file live), never the recordings directory, never `MediaStore`. The provider itself is
 * `android:exported="false"` with `android:grantUriPermissions="true"`, and the share [Intent] adds
 * [Intent.FLAG_GRANT_READ_URI_PERMISSION] explicitly (the same pattern
 * [cc.machado.audioblackbox.ui.gallery.GalleryScreen]'s `shareRecording` already uses for its own
 * `MediaStore` uri) so the receiving app can read the one granted file without this provider ever
 * being reachable by an arbitrary external app.
 */
private const val DIAGNOSTICS_CACHE_SUBDIR = "diagnostics"
private const val DIAGNOSTICS_REPORT_FILENAME = "audio_blackbox_diagnostic_log.txt"

/**
 * Builds the full diagnostic report text: a triage header followed by every generation of both
 * on-disk logs, in a fixed order -- [exportLogText] (`export_errors.log`), then
 * [exportLogOldText] (`export_errors.log.old`), then [crashLogText] (`crash_log.log`), then
 * [crashLogOldText] (`crash_log.log.old`). A missing/absent generation is rendered as an explicit
 * `(not present)` marker rather than a silently-skipped section, so a reader (or a test) can always
 * tell "this generation does not exist on this device" apart from "this generation exists and is
 * blank".
 *
 * Deliberately takes only primitives/pre-read [String] content and no [File]/[Context] at all, so
 * it is testable on the plain JVM (per this repo's "no Robolectric" constraint) -- the caller
 * ([exportFullDiagnosticLog]) does the actual file reads and redaction and passes the results in.
 */
internal fun buildFullDiagnosticReport(
    versionName: String,
    versionCode: Long,
    deviceModel: String,
    androidVersion: String,
    preset: QualityPreset,
    retentionMinutes: Int,
    exportLogText: String?,
    exportLogOldText: String?,
    crashLogText: String?,
    crashLogOldText: String?,
    timestampMillis: Long = System.currentTimeMillis(),
): String {
    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMillis))
    return buildString {
        appendLine("=== AUDIO BLACKBOX FULL DIAGNOSTIC LOG ===")
        appendLine("Generated: $timestamp")
        appendLine("App Version: $versionName ($versionCode)")
        appendLine("Device: $deviceModel")
        appendLine("Android OS: $androidVersion")
        appendLine(
            "Active Preset: ${preset.name} (${preset.sampleRateHz} Hz, " +
                "${if (preset.channelCount == 1) "Mono" else "Stereo"})",
        )
        appendLine("Retention Window: $retentionMinutes min")
        appendLine("===========================================")
        appendDiagnosticSection("export_errors.log", exportLogText)
        appendDiagnosticSection("export_errors.log.old", exportLogOldText)
        appendDiagnosticSection("crash_log.log", crashLogText)
        appendDiagnosticSection("crash_log.log.old", crashLogOldText)
    }
}

private fun StringBuilder.appendDiagnosticSection(name: String, content: String?) {
    appendLine()
    appendLine("--- $name ---")
    if (content.isNullOrEmpty()) {
        appendLine("(not present)")
    } else {
        append(content)
        if (!content.endsWith("\n")) appendLine()
    }
}

/**
 * `true` when every generation of both logs is missing or blank -- the exact condition under which
 * [exportFullDiagnosticLog] must show a clear "nothing to export" toast instead of opening the share
 * sheet with a report that is header-only (issue #388's acceptance criterion 2: an empty log must
 * not be shared without warning).
 */
internal fun isDiagnosticReportEmpty(
    exportLogText: String?,
    exportLogOldText: String?,
    crashLogText: String?,
    crashLogOldText: String?,
): Boolean =
    exportLogText.isNullOrBlank() &&
        exportLogOldText.isNullOrBlank() &&
        crashLogText.isNullOrBlank() &&
        crashLogOldText.isNullOrBlank()

/** Reads [file] (and its `.old` rotation sibling), redacting each through [redact] -- `null` when
 * the file does not exist or fails to read, so [isDiagnosticReportEmpty] can tell "absent" apart
 * from "present but empty" as cleanly as [buildFullDiagnosticReport] renders it. */
private fun readRedacted(file: File?, redact: (String) -> String): String? {
    if (file == null || !file.exists() || !file.isFile) return null
    return try {
        redact(file.readText())
    } catch (e: Exception) {
        null
    }
}

private fun oldGenerationOf(file: File?): File? =
    file?.let { File(it.parent, it.name + ".old") }

/**
 * The single UI-reachable action for issue #388: reads both on-disk logs (and their `.old`
 * generations), redacts sensitive paths through the same [redactSensitivePaths] logic
 * [cc.machado.audioblackbox.AudioBlackboxApplication]'s crash handler already uses, assembles the
 * full report via [buildFullDiagnosticReport], writes it to a dedicated cache subdirectory, and
 * opens the standard Android share sheet (`ACTION_SEND`) for it via [FileProvider] -- see this
 * file's class-level doc for the `FileProvider`-vs-`EXTRA_TEXT` decision and the exposed-surface
 * rationale.
 *
 * Reachable with no `ERROR` entries at all (wired from Settings, not the dashboard's error card),
 * and with an empty log: [isDiagnosticReportEmpty] is checked first, and an empty result shows
 * [R.string.settings_diagnostics_export_empty_toast] instead of sharing a near-blank file silently.
 */
fun exportFullDiagnosticLog(
    context: Context,
    preset: QualityPreset,
    retentionMinutes: Int,
) {
    val errorLogFile = ErrorLogFileHolder.file
    val crashLogFile = CrashLogFileHolder.file

    val sensitiveRoots = sensitiveRootsFor(
        filesDir = context.filesDir,
        cacheDir = context.cacheDir,
        externalCacheDir = context.externalCacheDir,
        externalFilesDirs = context.getExternalFilesDirs(null)?.toList().orEmpty(),
    )
    val packageName = context.packageName
    val redact: (String) -> String = { raw -> redactSensitivePaths(raw, sensitiveRoots, packageName) }

    val exportText = readRedacted(errorLogFile, redact)
    val exportOldText = readRedacted(oldGenerationOf(errorLogFile), redact)
    val crashText = readRedacted(crashLogFile, redact)
    val crashOldText = readRedacted(oldGenerationOf(crashLogFile), redact)

    if (isDiagnosticReportEmpty(exportText, exportOldText, crashText, crashOldText)) {
        // Toast requires a thread with a prepared Looper. Every real UI caller (the Settings
        // button, see SettingsScreen.kt) is already on the main thread, but posting explicitly to
        // Looper.getMainLooper() makes this function itself thread-safe to call from anywhere
        // (e.g. a test calling it directly off the instrumentation thread, which has no prepared
        // Looper of its own) instead of silently depending on the caller's thread.
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                context.getString(R.string.settings_diagnostics_export_empty_toast),
                Toast.LENGTH_SHORT,
            ).show()
        }
        return
    }

    val (versionName, versionCode) = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        (info.versionName ?: "unknown") to info.longVersionCode
    } catch (e: Exception) {
        "unknown" to -1L
    }

    val report = buildFullDiagnosticReport(
        versionName = versionName,
        versionCode = versionCode,
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        preset = preset,
        retentionMinutes = retentionMinutes,
        exportLogText = exportText,
        exportLogOldText = exportOldText,
        crashLogText = crashText,
        crashLogOldText = crashOldText,
    )

    val diagnosticsCacheDir = File(context.cacheDir, DIAGNOSTICS_CACHE_SUBDIR).apply { mkdirs() }
    val reportFile = File(diagnosticsCacheDir, DIAGNOSTICS_REPORT_FILENAME)
    reportFile.writeText(report)

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", reportFile)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_diagnostics_export_subject))
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(
        sendIntent,
        context.getString(R.string.settings_diagnostics_export_chooser_title),
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}
