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

/**
 * Severity of one [ExportErrorLogEntry] (issue #346/#347).
 *
 * [ERROR] is a genuine failure -- the thing #346's dashboard card exists to surface. [AUDIT] is a
 * noteworthy-but-non-fatal event on a session that still completed successfully (e.g.
 * [ForwardRecordingEngine]'s `TAIL_TRUNCATED`/`MUXER_STOP_RECOVERED` entries): worth a durable,
 * reviewable trail, but must never make the dashboard's error card render as though a recording
 * failed when it did not. [DashboardViewModel]'s "does at least one error exist" check counts
 * [ERROR] only; the modal lists both.
 */
enum class ErrorLogSeverity {
    ERROR,
    AUDIT,
}

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
    severity: ErrorLogSeverity = ErrorLogSeverity.ERROR,
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
        if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
            val rotatedFile = File(file.parent, file.name + ".old")
            if (rotatedFile.exists()) {
                rotatedFile.delete()
            }
            file.renameTo(rotatedFile)
        }

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
 */
internal fun readErrorLog(file: File?): List<ErrorLogEntry> {
    if (file == null) return emptyList()
    val current = parseLogFile(file)
    val rotated = parseLogFile(File(file.parent, file.name + ".old"))
    return current.asReversed() + rotated.asReversed()
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
        severity = ErrorLogSeverity.ERROR,
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
