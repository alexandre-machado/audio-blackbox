package cc.machado.audioblackbox.ui.gallery

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formats a millisecond duration as `M:SS` (e.g. `65_000L` -> `"1:05"`) -- small, purely
 * presentational, kept local to this screen rather than shared with
 * [cc.machado.audioblackbox.ui.dashboard]'s own `formatMillisAsClock` so this module has no
 * dependency on the dashboard package. */
fun formatDurationClock(durationMillis: Long): String {
    val totalSeconds = durationMillis.coerceAtLeast(0L) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

/** Formats a byte count the way a file manager would (decimal units, one decimal place once past
 * the first, e.g. `2_420_830L` -> `"2.4 MB"`), not binary (KiB/MiB) -- legible without needing the
 * user to know the difference. */
fun formatFileSize(sizeBytes: Long): String {
    val bytes = sizeBytes.coerceAtLeast(0L)
    return when {
        bytes < 1_000L -> "$bytes B"
        bytes < 1_000_000L -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    }
}

/** Formats a capture timestamp as `dd/MM/yyyy HH:mm` -- a numeric date pattern, not translated
 * vocabulary, so it is fine to fix here rather than route through `strings.xml`. */
fun formatCapturedAt(capturedAtMillis: Long): String {
    val formatter = SimpleDateFormat(CAPTURED_AT_PATTERN, Locale.US)
    return formatter.format(Date(capturedAtMillis))
}

/** Formats a timestamp as `dd/MM/yyyy HH:mm:ss` for detailed file inspection. */
fun formatFullDateTime(timestampMillis: Long): String {
    if (timestampMillis <= 0L) return "—"
    val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(timestampMillis))
}

/** Infers audio quality specifications (codec, bitrate) from recording metadata. */
fun inferAudioQuality(recording: RecordingItem): String {
    return if (recording.mimeType.contains("wav", ignoreCase = true) || recording.displayName.endsWith(".wav", ignoreCase = true)) {
        "16-bit PCM Linear"
    } else {
        val durationSec = recording.durationMillis / 1000L
        if (durationSec > 0L) {
            val kbps = (recording.sizeBytes * 8L) / (durationSec * 1000L)
            "AAC (~${kbps} kbps)"
        } else {
            "AAC (MPEG-4)"
        }
    }
}

private const val CAPTURED_AT_PATTERN = "dd/MM/yyyy HH:mm"
