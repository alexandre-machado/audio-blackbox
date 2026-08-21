package cc.machado.audioblackbox.ui.dashboard

import java.util.Locale

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
