package cc.machado.audioblackbox.export

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import cc.machado.audioblackbox.CrashLogFileHolder
import cc.machado.audioblackbox.ErrorLogFileHolder
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.audio.QualityPreset
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 *
 * ## Off the main thread (PR #390 `@rev` finding 1)
 * Reading up to ~12 MB across four generations, redacting every one with
 * [redactSensitivePaths]'s regex passes, and writing the assembled report back to disk are real
 * I/O + CPU work -- exactly the kind [readErrorLog]'s own doc already warns callers to dispatch off
 * the calling thread themselves. [exportFullDiagnosticLog] is `suspend` and does that work inside
 * [withContext] on [ioDispatcher] (`Dispatchers.IO` by default); only the final `startActivity`/
 * `Toast` happens back on the caller's original dispatcher (Main, for every real UI call site).
 * [ioDispatcher] is a parameter -- not merely hardcoded -- so a test can substitute a dispatcher
 * that observes which thread the heavy work actually lands on, proving this claim rather than
 * merely asserting it (see `DiagnosticLogExporterInstrumentedTest`'s off-main-thread test).
 */
private const val DIAGNOSTICS_CACHE_SUBDIR = "diagnostics"
private const val DIAGNOSTICS_REPORT_FILENAME = "audio_blackbox_diagnostic_log.txt"

/**
 * One on-disk log generation's read outcome (PR #390 `@rev` finding 5): distinguishes a file that
 * genuinely does not exist ([Absent]) from one that exists but could not be read
 * ([ReadFailed] -- a transient I/O error, a torn write racing a rotation, etc.) from a real,
 * successfully-read (and already redacted) [Content]. Before this, both [Absent] and [ReadFailed]
 * collapsed to the same `null`/"(not present)" rendering, which could hide from support the fact
 * that a device actually had entries a transient failure just couldn't surface this one time.
 * [ReadFailed] carries only the exception's simple class name, never [Throwable.message] --
 * an exception message could itself contain an unredacted path or other raw content this whole
 * export exists to avoid leaking.
 */
internal sealed class LogGenerationRead {
    internal object Absent : LogGenerationRead()
    internal data class Content(val text: String) : LogGenerationRead()
    internal data class ReadFailed(val exceptionClassName: String) : LogGenerationRead()
}

/**
 * Builds the full diagnostic report text: a triage header followed by every generation of both
 * on-disk logs, in a fixed order -- [exportLogRead] (`export_errors.log`), then
 * [exportLogOldRead] (`export_errors.log.old`), then [crashLogRead] (`crash_log.log`), then
 * [crashLogOldRead] (`crash_log.log.old`). Each generation renders as its real content, an explicit
 * `(not present)` marker for [LogGenerationRead.Absent], or `(read failed: <ExceptionClass>)` for
 * [LogGenerationRead.ReadFailed] -- never a silently-skipped section, so a reader (or a test) can
 * always tell these three states apart.
 *
 * Deliberately takes only primitives/[LogGenerationRead] values and no [File]/[Context] at all, so
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
    exportLogRead: LogGenerationRead,
    exportLogOldRead: LogGenerationRead,
    crashLogRead: LogGenerationRead,
    crashLogOldRead: LogGenerationRead,
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
        appendDiagnosticSection("export_errors.log", exportLogRead)
        appendDiagnosticSection("export_errors.log.old", exportLogOldRead)
        appendDiagnosticSection("crash_log.log", crashLogRead)
        appendDiagnosticSection("crash_log.log.old", crashLogOldRead)
    }
}

private fun StringBuilder.appendDiagnosticSection(name: String, read: LogGenerationRead) {
    appendLine()
    appendLine("--- $name ---")
    when (read) {
        is LogGenerationRead.Absent -> appendLine("(not present)")
        is LogGenerationRead.ReadFailed -> appendLine("(read failed: ${read.exceptionClassName})")
        is LogGenerationRead.Content -> {
            append(read.text)
            if (!read.text.endsWith("\n")) appendLine()
        }
    }
}

/**
 * `true` when none of the four generations has real, non-blank content -- the exact condition
 * under which [exportFullDiagnosticLog] must show a clear "nothing to export" toast instead of
 * opening the share sheet with a report that is header-only (issue #388's acceptance criterion 2:
 * an empty log must not be shared without warning). [LogGenerationRead.Absent] and
 * [LogGenerationRead.ReadFailed] both count as "no content" here, same as before PR #390's finding
 * 5 introduced the distinction -- only the *rendering* changed, not this emptiness rule.
 */
internal fun isDiagnosticReportEmpty(vararg reads: LogGenerationRead): Boolean =
    reads.all { it !is LogGenerationRead.Content || it.text.isBlank() }

/** Reads [file] (and its `.old` rotation sibling), redacting each through [redact] --
 * distinguishes "file does not exist" ([LogGenerationRead.Absent]) from "file exists but could not
 * be read" ([LogGenerationRead.ReadFailed]), per PR #390 `@rev` finding 5. */
private fun readRedacted(file: File?, redact: (String) -> String): LogGenerationRead {
    if (file == null || !file.exists() || !file.isFile) return LogGenerationRead.Absent
    return try {
        LogGenerationRead.Content(redact(file.readText()))
    } catch (e: Exception) {
        LogGenerationRead.ReadFailed(e.javaClass.simpleName)
    }
}

private fun oldGenerationOf(file: File?): File? =
    file?.let { File(it.parent, it.name + ".old") }

/** Outcome of the (potentially heavy) read/redact/assemble/write work in
 * [buildDiagnosticExportOutcome] -- resolved entirely off the main thread, then interpreted by
 * [exportFullDiagnosticLog] back on the caller's own dispatcher to actually show a [Toast] or start
 * an [Intent]. [Failed] carries only the exception's simple class name (never [Throwable.message]),
 * same rationale as [LogGenerationRead.ReadFailed]. */
internal sealed class DiagnosticExportOutcome {
    internal object Empty : DiagnosticExportOutcome()
    internal data class Ready(val chooserIntent: Intent) : DiagnosticExportOutcome()
    internal data class Failed(val exceptionClassName: String) : DiagnosticExportOutcome()
}

/**
 * Does all the heavy lifting: reads both on-disk logs and their `.old` generations, redacts
 * sensitive paths through the same [redactSensitivePaths] logic
 * [cc.machado.audioblackbox.AudioBlackboxApplication]'s crash handler already uses, assembles the
 * full report via [buildFullDiagnosticReport], writes it to a dedicated cache subdirectory, and
 * builds (but does not launch) the share [Intent] via [FileProvider].
 *
 * Deliberately a plain, non-suspend function containing only blocking calls -- [exportFullDiagnosticLog]
 * is the one responsible for making sure this runs off the main thread, via [withContext]. Keeping
 * this function itself dispatcher-agnostic (rather than baking in its own `withContext`) is what
 * lets a test invoke it directly, synchronously, without any coroutine machinery, while production
 * still only ever calls it from inside [withContext].
 *
 * PR #390 `@rev` finding 4: [reportFile.writeText], [FileProvider.getUriForFile], and building the
 * chooser [Intent] are wrapped in a single `try`/`catch` -- an `IOException` (e.g. disk full) or a
 * `FileProvider` failure now becomes [DiagnosticExportOutcome.Failed] instead of an uncaught
 * exception that would otherwise crash the process from inside a diagnostics *export* attempt.
 */
private fun buildDiagnosticExportOutcome(
    context: Context,
    preset: QualityPreset,
    retentionMinutes: Int,
): DiagnosticExportOutcome {
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

    val exportRead = readRedacted(errorLogFile, redact)
    val exportOldRead = readRedacted(oldGenerationOf(errorLogFile), redact)
    val crashRead = readRedacted(crashLogFile, redact)
    val crashOldRead = readRedacted(oldGenerationOf(crashLogFile), redact)

    if (isDiagnosticReportEmpty(exportRead, exportOldRead, crashRead, crashOldRead)) {
        return DiagnosticExportOutcome.Empty
    }

    return try {
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
            exportLogRead = exportRead,
            exportLogOldRead = exportOldRead,
            crashLogRead = crashRead,
            crashLogOldRead = crashOldRead,
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
        DiagnosticExportOutcome.Ready(chooser)
    } catch (e: Exception) {
        DiagnosticExportOutcome.Failed(e.javaClass.simpleName)
    }
}

private fun showToast(context: Context, @StringRes resId: Int) {
    Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
}

/**
 * The single UI-reachable action for issue #388: reads both on-disk logs (and their `.old`
 * generations), redacts sensitive paths, assembles the full report, writes it to a dedicated cache
 * subdirectory, and opens the standard Android share sheet (`ACTION_SEND`) for it via [FileProvider]
 * -- see this file's class-level doc for the `FileProvider`-vs-`EXTRA_TEXT` decision, the
 * exposed-surface rationale, and the off-main-thread rationale (PR #390 `@rev` finding 1).
 *
 * `suspend`: the actual read/redact/assemble/write work ([buildDiagnosticExportOutcome]) runs
 * inside [withContext] on [ioDispatcher] (`Dispatchers.IO` by default, overridable for tests);
 * `startActivity`/`Toast` happen after [withContext] returns, i.e. back on the caller's own
 * dispatcher -- Main, for every real call site (see `SettingsScreen.kt`'s
 * `rememberCoroutineScope().launch { ... }` wiring).
 *
 * Reachable with no `ERROR` entries at all (wired from Settings, not the dashboard's error card),
 * and with an empty log: an empty result shows [R.string.settings_diagnostics_export_empty_toast]
 * instead of sharing a near-blank file silently. A write/`FileProvider`/chooser-build failure (PR
 * #390 `@rev` finding 4) shows [R.string.settings_diagnostics_export_error_toast] instead of
 * propagating and crashing the process; likewise, if `startActivity` itself throws (e.g. no
 * activity can handle the chooser), the same generic error toast is shown rather than crashing.
 */
suspend fun exportFullDiagnosticLog(
    context: Context,
    preset: QualityPreset,
    retentionMinutes: Int,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // MUTATION-VERIFICATION ONLY (PR #390 @rev finding 1 non-vacuity proof) -- do not merge:
    // dropped the withContext(ioDispatcher) wrapper so the heavy work runs on the caller's own
    // (Main) dispatcher again, to prove exportFullDiagnosticLog_runsHeavyWorkOffTheMainThread
    // actually fails when it should.
    val outcome = buildDiagnosticExportOutcome(context, preset, retentionMinutes)
    when (outcome) {
        is DiagnosticExportOutcome.Empty ->
            showToast(context, R.string.settings_diagnostics_export_empty_toast)
        is DiagnosticExportOutcome.Failed ->
            showToast(context, R.string.settings_diagnostics_export_error_toast)
        is DiagnosticExportOutcome.Ready -> {
            try {
                context.startActivity(outcome.chooserIntent)
            } catch (e: Exception) {
                showToast(context, R.string.settings_diagnostics_export_error_toast)
            }
        }
    }
}
