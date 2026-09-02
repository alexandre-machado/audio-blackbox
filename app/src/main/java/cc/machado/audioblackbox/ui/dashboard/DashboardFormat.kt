package cc.machado.audioblackbox.ui.dashboard

import androidx.annotation.StringRes
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.audio.AudioLevel
import cc.machado.audioblackbox.audio.CaptureErrorReason
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
