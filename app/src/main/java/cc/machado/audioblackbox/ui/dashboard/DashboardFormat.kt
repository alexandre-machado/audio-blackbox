package cc.machado.audioblackbox.ui.dashboard

import androidx.annotation.StringRes
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.audio.AudioLevel
import cc.machado.audioblackbox.audio.CaptureErrorReason
import cc.machado.audioblackbox.export.ErrorLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Formats a millisecond duration as `MM:SS` for the buffer indicator (e.g. `754_000L` ->
 * `"12:34"`). A small, purely-presentational helper -- not audio/export logic -- so it is fine
 * for the UI layer to own; it does not compute or trim any audio, only renders a number that
 * [DashboardViewModel] already produced. */
fun formatMillisAsClock(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

/**
 * Maps each [CaptureErrorReason] to an actionable, user-facing string resource explaining
 * what failed and what to do (issue #39).
 *
 * Exhaustive `when` with NO `else` branch, ensuring that adding any new [CaptureErrorReason] enum
 * constant will cause a compile-time failure until an explicit user message resource is mapped.
 */
@StringRes
fun CaptureErrorReason.toUserMessageRes(): Int = when (this) {
    CaptureErrorReason.BUFFER_ALLOCATION_FAILED -> R.string.capture_error_buffer_allocation_failed
    CaptureErrorReason.UNSUPPORTED_CONFIG -> R.string.capture_error_unsupported_config
    CaptureErrorReason.AUDIO_RECORD_INIT_FAILED -> R.string.capture_error_audio_record_init_failed
    CaptureErrorReason.READ_INVALID_OPERATION -> R.string.capture_error_read_invalid_operation
    CaptureErrorReason.READ_BAD_VALUE -> R.string.capture_error_read_bad_value
    CaptureErrorReason.READ_DEAD_OBJECT -> R.string.capture_error_read_dead_object
    CaptureErrorReason.READ_UNKNOWN_ERROR -> R.string.capture_error_read_unknown_error
    CaptureErrorReason.FOREGROUND_SERVICE_PROMOTION_REFUSED ->
        R.string.capture_error_foreground_service_promotion_refused
    CaptureErrorReason.UNEXPECTED_CAPTURE_FAILURE -> R.string.capture_error_unexpected_capture_failure
}

/**
 * Converts a meter level in `0f..1f` back to the dBFS figure shown beside the bar.
 *
 * [cc.machado.audioblackbox.audio.AudioLevel.peakLevel] maps
 * [cc.machado.audioblackbox.audio.AudioLevel.MIN_DBFS]..0 dB onto 0f..1f, so this is that mapping
 * inverted -- presentation only, and deliberately not part of the measurement: the engine reports
 * one number and the UI decides how to render it, the same split every other value on this screen
 * follows.
 */
fun dbfsFor(level: Float): Int {
    val clamped = level.coerceIn(0f, 1f)
    return (AudioLevel.MIN_DBFS + clamped * -AudioLevel.MIN_DBFS).roundToInt()
}

/** `yyyy-MM-dd HH:mm:ss` in the device's default locale digits (issue #346's error-list rows) --
 * matches [DiagnosticsReportHelper]'s own incident-report timestamp format so the two surfaces
 * read the same way. A new formatter per call (not a shared, non-thread-safe
 * [java.text.SimpleDateFormat] instance) since this can be called from a background parse. */
fun formatErrorLogTimestamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

/**
 * Slices [items] into 0-indexed pages of [pageSize] (issue #346's pagination requirement) --
 * the single oracle both [DashboardViewModel] and its tests use, so "page 2 of a 45-entry list at
 * page size 20 is exactly items 20..39" is asserted once here rather than re-derived at each call
 * site. [page] is clamped into range rather than throwing: a stale page index after the
 * underlying list shrinks (e.g. after Clear) must degrade to the nearest valid page, not crash.
 */
fun <T> paginate(items: List<T>, page: Int, pageSize: Int): List<T> {
    if (items.isEmpty() || pageSize <= 0) return emptyList()
    val pageCount = errorLogPageCount(items.size, pageSize)
    val clampedPage = page.coerceIn(0, pageCount - 1)
    val start = clampedPage * pageSize
    val end = (start + pageSize).coerceAtMost(items.size)
    return items.subList(start, end)
}

/** Total number of [paginate] pages for [totalItems] items at [pageSize] per page. Always at
 * least `1` so a caller can safely compute "page X of Y" even for an empty list. */
fun errorLogPageCount(totalItems: Int, pageSize: Int): Int {
    if (pageSize <= 0) return 1
    return maxOf(1, (totalItems + pageSize - 1) / pageSize)
}

/** Renders one [ErrorLogEntry] as a plain-text block for the modal's copy-to-clipboard action
 * (issue #346) -- deliberately plain text, not JSON: this is for a human to paste into a bug
 * report or a chat with the developer, not for another program to re-parse. */
fun formatErrorLogEntryForClipboard(entry: ErrorLogEntry): String {
    val header = "[${formatErrorLogTimestamp(entry.timestampMillis)}] [${entry.severity}] " +
        "[${entry.component}] [${entry.reason}] ${entry.message}"
    return if (entry.stackTrace != null) "$header\n${entry.stackTrace}" else header
}

/** Joins a page of [ErrorLogEntry] into one clipboard payload -- see [formatErrorLogEntryForClipboard].
 * Copies only the page passed in, never the whole log (issue #346's owner decision: "copy the
 * currently visible page", not the entire on-disk log). */
fun formatErrorLogPageForClipboard(entries: List<ErrorLogEntry>): String =
    entries.joinToString(separator = "\n\n") { formatErrorLogEntryForClipboard(it) }
