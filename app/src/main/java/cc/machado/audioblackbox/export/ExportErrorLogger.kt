package cc.machado.audioblackbox.export

import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private const val MAX_LOG_SIZE_BYTES = 5 * 1024 * 1024L // 5 MB
private const val SCHEMA_VERSION = 1

/** Size cap for the sibling crash log (issue #371) -- deliberately stricter than
 * [MAX_LOG_SIZE_BYTES]: a crash entry carries a full stack trace (bigger than a typical export
 * error line) and a crash *loop* is exactly the pathological case this cap exists to bound, so a
 * smaller ceiling keeps worst-case on-disk growth tighter than the export log's. Same rotation
 * shape as the export log (see [rotateIfOversized]): oldest generation (`.old`) is dropped, not
 * appended to, so eviction is oldest-first. */
private const val CRASH_LOG_MAX_SIZE_BYTES = 1 * 1024 * 1024L // 1 MB

/**
 * Severity of one [ExportErrorLogEntry] (issue #346/#347/#371).
 *
 * [ERROR] is a genuine failure -- the thing #346's dashboard card exists to surface. [AUDIT] is a
 * noteworthy-but-non-fatal event on a session that still completed successfully (e.g.
 * [ForwardRecordingEngine]'s `TAIL_TRUNCATED`/`MUXER_STOP_RECOVERED` entries): worth a durable,
 * reviewable trail, but must never make the dashboard's error card render as though a recording
 * failed when it did not. [CRASH] (issue #371) is a JVM uncaught exception recorded by
 * [writeCrashLogEntrySync] -- always shown in the modal, but deliberately its own value rather
 * than [ERROR]: the app may have crashed on a screen with no export/recording in flight at all, so
 * counting it toward "did this recording fail" would misreport exactly the way #347 already found
 * for [AUDIT]. [DashboardViewModel]'s "does at least one error exist" check counts [ERROR] only;
 * the modal lists all three.
 */
enum class ErrorLogSeverity {
    ERROR,
    AUDIT,
    CRASH,
}

/** Reasons that are known to describe a session that still completed successfully -- worth a
 * durable, reviewable trail, but must never flip the dashboard's "does at least one error exist"
 * check. This is the *single* source of truth for reason->severity: both [logExportError]'s
 * default parameter (the JSON write path) and [LegacyBuilder.build] (the pre-#346 plain-text read
 * path, which never had a severity field on disk at all) resolve through [severityForReason]
 * rather than keeping their own lists, so a reason added here can never silently be misclassified
 * by the other path (issue #346 review finding on PR #349). */
private val AUDIT_REASONS = setOf("TAIL_TRUNCATED", "MUXER_STOP_RECOVERED")

/** Resolves the severity for a bare `reason` string, used both as [logExportError]'s default for
 * newly-written entries and by [LegacyBuilder.build] for entries recovered from the old
 * plain-text format that predates the severity field entirely.
 *
 * An unrecognized `reason` defaults to [ErrorLogSeverity.ERROR]: this is the fail-safe direction --
 * a genuine failure wrongly shown as AUDIT would be silently hidden from the dashboard's error
 * card (exactly what issue #346 exists to prevent), whereas a benign event wrongly shown as ERROR
 * is merely a false alarm the user can dismiss. Only reasons explicitly known to be non-fatal are
 * ever downgraded to AUDIT. */
internal fun severityForReason(reason: String): ErrorLogSeverity =
    if (reason in AUDIT_REASONS) ErrorLogSeverity.AUDIT else ErrorLogSeverity.ERROR

/** One parsed line of the durable error log (issue #346), independent of whether the line on
 * disk was written as JSON (current format) or the old plain-text format (see [readErrorLog]'s
 * doc for the migration story). [legacy] is `true` only for a line recovered from that old
 * format -- surfaced so a caller could (but does not currently need to) render it differently. */
data class ErrorLogEntry(
    val timestampMillis: Long,
    val component: String,
    val reason: String,
    val message: String,
    val stackTrace: String?,
    val severity: ErrorLogSeverity = ErrorLogSeverity.ERROR,
    val legacy: Boolean = false,
)

private data class PendingWrite(
    val file: File?,
    val timestamp: Long,
    val component: String,
    val reason: String,
    val message: String,
    val exception: Throwable?,
    val severity: ErrorLogSeverity,
    val completionLatch: CountDownLatch?
)

private val loggerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val errorChannel = Channel<PendingWrite>(capacity = 100).apply {
    loggerScope.launch {
        for (entry in this@apply) {
            writeEntrySync(entry)
        }
    }
}

/**
 * Appends one entry to [file] as a single line of JSON (issue #346: JSONL, one object per line,
 * append-only -- the owner's chosen storage format over the log's original plain-text shape).
 * Off the main thread via [errorChannel] + [Dispatchers.IO], same as before this issue.
 */
internal fun logExportError(
    file: File?,
    clock: () -> Long,
    component: String,
    reason: String,
    message: String,
    exception: Throwable?,
    severity: ErrorLogSeverity = severityForReason(reason),
) {
    if (file == null) return
    val entry = PendingWrite(
        file = file,
        timestamp = clock(),
        component = component,
        reason = reason,
        message = message,
        exception = exception,
        severity = severity,
        completionLatch = null
    )
    errorChannel.trySend(entry)
}

internal fun flushErrorLogsForTest() {
    val latch = CountDownLatch(1)
    errorChannel.trySend(
        PendingWrite(
            file = null,
            timestamp = 0L,
            component = "",
            reason = "",
            message = "",
            exception = null,
            severity = ErrorLogSeverity.ERROR,
            completionLatch = latch
        )
    )
    latch.await(5, TimeUnit.SECONDS)
}

private fun writeEntrySync(entry: PendingWrite) {
    if (entry.file == null) {
        entry.completionLatch?.countDown()
        return
    }
    try {
        var sanitizedTrace: String? = null
        if (entry.exception != null) {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.print(entry.exception.toString())
            entry.exception.stackTrace.take(15).forEach { element ->
                pw.print("\n\tat $element")
            }
            if (entry.exception.stackTrace.size > 15) {
                pw.print("\n\t... (truncated)")
            }
            sanitizedTrace = sw.toString()
        }

        val file = entry.file
        rotateIfOversized(file, MAX_LOG_SIZE_BYTES)

        val line = buildJsonLine(
            timestampMillis = entry.timestamp,
            component = entry.component,
            reason = entry.reason,
            message = entry.message,
            stackTrace = sanitizedTrace,
            severity = entry.severity,
        )
        PrintWriter(FileWriter(file, true)).use { pw ->
            pw.println(line)
        }
    } catch (e: Exception) {
        // Suppress logging failures to avoid crashing the exporter
    } finally {
        entry.completionLatch?.countDown()
    }
}

/** Rotates [file] to `file.old` (dropping any previous `.old` generation) once it exceeds
 * [maxBytes] -- shared by [writeEntrySync] and [writeCrashLogEntrySync] so the two logs' size caps
 * are enforced the same, oldest-first-eviction way (issue #371). */
private fun rotateIfOversized(file: File, maxBytes: Long) {
    if (file.exists() && file.length() > maxBytes) {
        val rotatedFile = File(file.parent, file.name + ".old")
        if (rotatedFile.exists()) {
            rotatedFile.delete()
        }
        file.renameTo(rotatedFile)
    }
}

private fun buildJsonLine(
    timestampMillis: Long,
    component: String,
    reason: String,
    message: String,
    stackTrace: String?,
    severity: ErrorLogSeverity,
): String {
    val sb = StringBuilder()
    sb.append("{")
    sb.append("\"schemaVersion\":").append(SCHEMA_VERSION).append(',')
    sb.append("\"timestampMillis\":").append(timestampMillis).append(',')
    sb.append("\"component\":\"").append(jsonEscape(component)).append("\",")
    sb.append("\"reason\":\"").append(jsonEscape(reason)).append("\",")
    sb.append("\"severity\":\"").append(severity.name).append("\",")
    sb.append("\"message\":\"").append(jsonEscape(message)).append("\"")
    if (stackTrace != null) {
        sb.append(",\"stackTrace\":\"").append(jsonEscape(stackTrace)).append("\"")
    }
    sb.append("}")
    return sb.toString()
}

/**
 * Baked-in, Android-path-shape patterns for [redactSensitivePaths] -- unlike [redactSensitivePaths]'s
 * `sensitiveRoots` parameter (which is only as good as the exact directory strings a caller passes
 * in), these match by *shape*, so they still redact a path that reaches a crash log via an alias or
 * a directory this process's [android.content.Context] never itself resolved:
 *
 * - `/sdcard`, `/mnt/sdcard`, `/storage/self/primary` -- long-standing symlink aliases for primary
 *   external storage that resolve to the same place as `Environment.getExternalStorageDirectory()`
 *   / `Context.getExternalFilesDir(null)`'s parent chain, but as a *different string*, so a literal
 *   substring match against only the resolved path would miss them (`@sec` review finding on PR
 *   #372).
 * - `/storage/emulated/<n>` -- the primary volume's real path, redundant with the exact roots
 *   [AudioBlackboxApplication] passes in today, kept here too as a second, context-independent line
 *   of defense.
 * - `/storage/<uuid>` -- a *removable/secondary* volume (an SD card), addressed by its volume UUID.
 *   `Context.getExternalFilesDir(null)` alone only resolves the primary volume; this catches a
 *   second physical volume's path by its well-known shape even if nothing in this process ever
 *   asked the platform to enumerate it.
 */
private val SENSITIVE_PATH_SHAPE_PATTERNS = listOf(
    Regex("""/storage/emulated/\d+(/\S*)?"""),
    Regex("""/storage/[0-9A-Za-z]{4}-[0-9A-Za-z]{4}(/\S*)?"""),
    Regex("""/storage/self/primary(/\S*)?"""),
    Regex("""(?i)/sdcard(/\S*)?"""),
    Regex("""(?i)/mnt/sdcard(/\S*)?"""),
)

/**
 * Redacts anything under a known-sensitive path out of [text] before it is allowed into a durable
 * crash entry (issue #371's "no paths under the user's media directories" requirement) -- the exact
 * function [AudioBlackboxApplication.installCrashLogHandler] wires into [writeCrashLogEntrySync]'s
 * `sanitize` parameter in production, so a test that calls this function directly is exercising the
 * real redactor, not a stand-in that merely resembles it (`@sec` review finding on PR #372: the
 * original test asserted against a hand-written inline lambda instead).
 *
 * Two layers, applied together:
 * 1. [sensitiveRoots] -- literal substring replacement against whatever directories the caller's
 *    own [android.content.Context] resolved (`filesDir`, `cacheDir`, every volume
 *    `getExternalFilesDirs(null)` returns -- not just the primary one -- `externalCacheDir`). Since
 *    `String.replace` matches the root as a substring anywhere it occurs, this also redacts any
 *    subpath under that root (e.g. `<filesDir>/recordings/x.raw`), not just the bare root itself.
 * 2. [SENSITIVE_PATH_SHAPE_PATTERNS] -- context-independent, applied unconditionally, to catch a
 *    path that reaches the log via an alias or a volume this process's `Context` never itself
 *    resolved (a stale `/sdcard`-rooted message from a library, a second SD card, another Android
 *    user profile's `/data/user/<n>/<pkg>`).
 *
 * [packageName], if given, also redacts this app's private directory under **any** Android user
 * profile -- `/data/data/<packageName>` and `/data/user/<n>/<packageName>` -- not just the current
 * profile's, which is all `Context.filesDir`/`cacheDir` themselves resolve to. Built from the
 * caller's own package name (not a hardcoded constant) so it stays correct for the `.staging`
 * `applicationIdSuffix` build variant too.
 *
 * ## Known, explicitly acknowledged gap
 * A **relative** path (no leading `/`, e.g. a bare filename an exception happens to embed) cannot be
 * distinguished here from an unrelated word or identifier with no reliable, low-false-positive rule
 * -- this function does not attempt it. The layers above only redact *absolute* paths under a
 * known-sensitive root or of a known-sensitive shape.
 */
internal fun redactSensitivePaths(
    text: String,
    sensitiveRoots: List<String> = emptyList(),
    packageName: String? = null,
): String {
    var result = text
    for (root in sensitiveRoots) {
        if (root.isNotBlank()) {
            result = result.replace(root, "<redacted-path>")
        }
    }
    for (pattern in SENSITIVE_PATH_SHAPE_PATTERNS) {
        result = pattern.replace(result, "<redacted-path>")
    }
    if (!packageName.isNullOrBlank()) {
        val privateDirPattern = Regex("""/data/(data|user/\d+)/${Regex.escape(packageName)}(/\S*)?""")
        result = privateDirPattern.replace(result, "<redacted-path>")
    }
    return result
}

/**
 * Writes one JVM crash entry (issue #371) to [file] -- a sibling of `export_errors.log`, never the
 * same file -- **synchronously, on the calling thread**, bypassing [errorChannel]/[loggerScope]
 * entirely.
 *
 * ## Why synchronous, not the channel+coroutine path
 * [logExportError] is fire-and-forget onto a `Dispatchers.IO` coroutine; that is fine for an export
 * failure because the process keeps running afterward and will eventually drain the channel. It is
 * the wrong shape for a crash: [Thread.setDefaultUncaughtExceptionHandler]'s contract is that the
 * process is about to die (the platform's default handler kills it once this returns), so a write
 * that depends on a *different* thread being scheduled before then is a coin flip -- worse, if the
 * crash happened to originate from `Dispatchers.IO`'s own thread pool (or the pool is saturated,
 * or the process is already in a low-memory crash spiral), that scheduling may never happen at
 * all. [flushErrorLogsForTest]'s `CountDownLatch` solves a *different* problem (giving a **test**
 * a deterministic point to assert after, while production keeps its normal async path); it does
 * not solve "the write must physically be on disk before this function returns", which is the
 * actual requirement here. So this function does its own bounded, direct
 * open-write-flush-close on the crashing thread, with no dependency on any other thread or
 * coroutine machinery being alive.
 *
 * ## Why a sibling file, not `export_errors.log`
 * Sharing the export log would put crash entries under the same file the dashboard's "does at
 * least one error exist" check reads. That check is documented (see [ErrorLogSeverity]) to count
 * [ErrorLogSeverity.ERROR] only, specifically about *export* failures; a crash can happen on a
 * screen with no export or recording in flight, so folding it into that check would misreport a
 * healthy recording as failed (the exact #347 trap, just via a new source instead of `AUDIT`). A
 * sibling file keeps the export log's rotation, size accounting, and "does an error exist" oracle
 * completely undisturbed by crash volume, while [readErrorLog] still merges both for display.
 *
 * Returns `true` if the entry reached disk, `false` on any I/O failure -- swallowed here rather
 * than propagated, because a throwing crash-logger must never prevent the caller
 * ([AudioBlackboxApplication]'s handler) from still invoking the previous default handler.
 */
internal fun writeCrashLogEntrySync(
    file: File?,
    timestampMillis: Long,
    threadName: String,
    throwable: Throwable,
    versionName: String,
    versionCode: Long,
    sanitize: (String) -> String = { it },
): Boolean {
    if (file == null) return false
    return try {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("versionName=$versionName versionCode=$versionCode")
        throwable.printStackTrace(pw)
        val fullTrace = sanitize(sw.toString())

        file.parentFile?.mkdirs()
        rotateIfOversized(file, CRASH_LOG_MAX_SIZE_BYTES)

        val line = buildJsonLine(
            timestampMillis = timestampMillis,
            component = sanitize(threadName),
            reason = throwable.javaClass.name,
            message = sanitize(throwable.message ?: ""),
            stackTrace = fullTrace,
            severity = ErrorLogSeverity.CRASH,
        )
        // `PrintWriter(fw).use { ... }` both flushes and closes `pw2`, which in turn closes the
        // underlying `fw` -- so nothing further is done to `fw` after the block (a second
        // flush/close on an already-closed stream throws `IOException: Stream closed`, caught by
        // this same catch block and misreported as a write failure).
        PrintWriter(FileWriter(file, true)).use { pw2 ->
            pw2.println(line)
        }
        true
    } catch (e: Exception) {
        false
    }
}

private fun jsonEscape(raw: String): String {
    val sb = StringBuilder(raw.length + 16)
    for (c in raw) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) {
                sb.append(String.format(Locale.US, "\\u%04x", c.code))
            } else {
                sb.append(c)
            }
        }
    }
    return sb.toString()
}

/**
 * Reads and parses the durable error log for the dashboard's error-list card/modal (issue #346).
 * Blocking, plain file I/O -- callers must dispatch this to a background dispatcher (e.g.
 * `Dispatchers.IO`) themselves; this function does no threading of its own so it stays a plain,
 * synchronously-testable oracle.
 *
 * ## What is included -- the `.old` rotation generation is included, not excluded
 * Both [file] and its rotated `file.old` sibling (if present) are read and merged, each newest-
 * first, [file]'s entries before `.old`'s -- so a rotation happening between two reads never hides
 * an error that was live a moment before. This is the owner's explicit call for issue #346: the
 * whole point of a durable, reviewable log is that a write that happened to land just before a
 * rotation is not quietly dropped from what the user can review.
 *
 * ## Migration from the pre-#346 plain-text format -- parsed best-effort, not dropped
 * Before this issue, every line was `[ISO timestamp] [component] [reason] message`, optionally
 * followed by un-prefixed stack-trace continuation lines. Any device that already has an
 * `export_errors.log` from before this change keeps those lines on disk (nothing here rewrites or
 * truncates the file), and a line that fails to parse as JSON is retried against that older
 * shape: a line matching the legacy header pattern starts a new legacy [ErrorLogEntry], and every
 * following line that is neither a valid JSON object nor a new legacy header is folded into that
 * entry's [ErrorLogEntry.stackTrace] as an extra line. This is the owner's explicit "parsed
 * best-effort" choice among the three offered (rather than "shown as raw text" or "dropped") --
 * nothing the user had on-device before this change disappears from the list.
 *
 * ## [crashFile] (issue #371) -- merged in, chronologically, not just appended
 * If [crashFile] is given, its entries (parsed the same way, `.old` generation included) are
 * merged with [file]'s and the combined list is re-sorted newest-first by [ErrorLogEntry.timestampMillis].
 * A plain concatenation (export entries first, crash entries after) would put every crash entry
 * before or after the export entries regardless of when either actually happened, which is wrong
 * for a merged "most recent event first" view. When [crashFile] is omitted (the default, and every
 * existing call site's behavior), this degrades exactly to the pre-#371 single-file behavior above
 * with no re-sort, so no existing caller or test observes any change.
 */
internal fun readErrorLog(file: File?, crashFile: File? = null): List<ErrorLogEntry> {
    val exportEntries = if (file == null) {
        emptyList()
    } else {
        val current = parseLogFile(file)
        val rotated = parseLogFile(File(file.parent, file.name + ".old"))
        current.asReversed() + rotated.asReversed()
    }
    if (crashFile == null) return exportEntries

    val crashCurrent = parseLogFile(crashFile)
    val crashRotated = parseLogFile(File(crashFile.parent, crashFile.name + ".old"))
    val crashEntries = crashCurrent.asReversed() + crashRotated.asReversed()
    if (crashEntries.isEmpty()) return exportEntries

    return (exportEntries + crashEntries).sortedByDescending { it.timestampMillis }
}

private val legacyHeaderRegex = Regex("""^\[(.+?)\] \[(.+?)\] \[(.+?)\] (.*)$""")
private val legacyIsoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

private fun parseLogFile(file: File): List<ErrorLogEntry> {
    if (!file.exists() || !file.isFile) return emptyList()
    val entries = mutableListOf<ErrorLogEntry>()
    var legacyBuilder: LegacyBuilder? = null

    fun flushLegacy() {
        legacyBuilder?.let { entries.add(it.build()) }
        legacyBuilder = null
    }

    file.forEachLine { rawLine ->
        val line = rawLine.trimEnd('\r')
        if (line.isBlank()) return@forEachLine
        val trimmed = line.trim()
        if (trimmed.startsWith("{")) {
            val parsed = parseJsonEntry(trimmed)
            if (parsed != null) {
                flushLegacy()
                entries.add(parsed)
                return@forEachLine
            }
        }
        val headerMatch = legacyHeaderRegex.matchEntire(line)
        if (headerMatch != null) {
            flushLegacy()
            val (isoTimestamp, component, reason, message) = headerMatch.destructured
            val millis = try {
                legacyIsoFormat.parse(isoTimestamp)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
            legacyBuilder = LegacyBuilder(millis, component, reason, message)
        } else if (legacyBuilder != null) {
            legacyBuilder!!.appendStackLine(line)
        }
        // A line that is neither valid JSON nor a legacy header, with no legacy entry open, is
        // unparseable noise (e.g. a torn write from a crash mid-append) -- skipped rather than
        // surfaced as a phantom entry.
    }
    flushLegacy()
    return entries
}

private class LegacyBuilder(
    val millis: Long,
    val component: String,
    val reason: String,
    val message: String,
) {
    private val stackLines = mutableListOf<String>()
    fun appendStackLine(line: String) {
        stackLines.add(line)
    }
    fun build(): ErrorLogEntry = ErrorLogEntry(
        timestampMillis = millis,
        component = component,
        reason = reason,
        message = message,
        stackTrace = if (stackLines.isEmpty()) null else stackLines.joinToString("\n"),
        // The pre-#346 plain-text format has no severity field at all -- derive it from `reason`
        // through the same mapping the JSON write path defaults through, so a legacy
        // TAIL_TRUNCATED/MUXER_STOP_RECOVERED line already on a device from before this migration
        // (issue #322/#323, predates #346) comes back AUDIT and does not pop the error card for a
        // session that actually succeeded.
        severity = severityForReason(reason),
        legacy = true,
    )
}

/** Hand-rolled: this repo has no JSON library on the JVM unit-test classpath (`org.json` is an
 * unmocked Android stub under plain JUnit, and no serialization library is otherwise depended on
 * -- see AGENTS.md's "no Robolectric" constraint), and the schema here is a small, flat, known
 * shape that does not need one. Returns `null` on any malformed line rather than throwing, so one
 * corrupted line (e.g. a torn write) never takes down the whole read. */
private fun parseJsonEntry(line: String): ErrorLogEntry? {
    val fields = parseFlatJsonObject(line) ?: return null
    val timestampMillis = fields["timestampMillis"]?.toLongOrNull() ?: return null
    val component = fields["component"] ?: return null
    val reason = fields["reason"] ?: return null
    val message = fields["message"] ?: ""
    val severity = when (fields["severity"]) {
        "AUDIT" -> ErrorLogSeverity.AUDIT
        "CRASH" -> ErrorLogSeverity.CRASH
        else -> ErrorLogSeverity.ERROR
    }
    return ErrorLogEntry(
        timestampMillis = timestampMillis,
        component = component,
        reason = reason,
        message = message,
        stackTrace = fields["stackTrace"],
        severity = severity,
        legacy = false,
    )
}

/** Parses one flat (non-nested) JSON object into a `key -> rawValue` map, where a string value
 * has already been unescaped and a numeric/boolean/null value is kept as its literal text (only
 * [parseJsonEntry]'s `timestampMillis` needs a number, via `toLongOrNull()`). Returns `null` if
 * `line` is not a syntactically well-formed flat JSON object. */
private fun parseFlatJsonObject(line: String): Map<String, String>? {
    var i = 0
    val n = line.length
    fun skipWs() { while (i < n && line[i].isWhitespace()) i++ }
    skipWs()
    if (i >= n || line[i] != '{') return null
    i++
    val result = mutableMapOf<String, String>()
    skipWs()
    if (i < n && line[i] == '}') return result
    while (i < n) {
        skipWs()
        if (i >= n || line[i] != '"') return null
        val key = readJsonString(line, i) ?: return null
        i = key.second
        skipWs()
        if (i >= n || line[i] != ':') return null
        i++
        skipWs()
        if (i >= n) return null
        val value: String
        if (line[i] == '"') {
            val strResult = readJsonString(line, i) ?: return null
            value = strResult.first
            i = strResult.second
        } else {
            val start = i
            while (i < n && line[i] != ',' && line[i] != '}') i++
            value = line.substring(start, i).trim()
        }
        result[key.first] = value
        skipWs()
        if (i < n && line[i] == ',') {
            i++
            continue
        }
        if (i < n && line[i] == '}') {
            i++
            return result
        }
        return null
    }
    return null
}

/** Reads one JSON string literal starting at `line[start]` (must be `"`), returning the
 * unescaped value paired with the index just past the closing quote, or `null` if malformed. */
private fun readJsonString(line: String, start: Int): Pair<String, Int>? {
    if (line[start] != '"') return null
    var i = start + 1
    val sb = StringBuilder()
    val n = line.length
    while (i < n) {
        val c = line[i]
        if (c == '"') {
            return sb.toString() to (i + 1)
        }
        if (c == '\\' && i + 1 < n) {
            when (val next = line[i + 1]) {
                '"' -> { sb.append('"'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                '/' -> { sb.append('/'); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append(''); i += 2 }
                'u' -> {
                    if (i + 5 < n) {
                        val hex = line.substring(i + 2, i + 6)
                        val code = hex.toIntOrNull(16)
                        if (code != null) {
                            sb.append(code.toChar())
                            i += 6
                        } else {
                            sb.append(next)
                            i += 2
                        }
                    } else {
                        i += 2
                    }
                }
                else -> { sb.append(next); i += 2 }
            }
        } else {
            sb.append(c)
            i++
        }
    }
    return null // unterminated string
}

/**
 * Deletes the durable error log entirely -- both the live [file] and its rotated `.old`
 * generation -- for the dashboard's "clear the list" action (issue #346). The destructive
 * confirmation itself lives in the UI layer, not here; this function performs the deletion
 * unconditionally once called. Off-main-thread: same contract as [readErrorLog].
 */
internal fun clearErrorLog(file: File?) {
    if (file == null) return
    file.delete()
    File(file.parent, file.name + ".old").delete()
}
