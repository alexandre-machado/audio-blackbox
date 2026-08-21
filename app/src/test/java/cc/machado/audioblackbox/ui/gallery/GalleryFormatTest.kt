package cc.machado.audioblackbox.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pinned-value tests for the presentational formatters -- each expected string is hand-computed
 * from the input, not re-derived through the same arithmetic the function itself uses, so a broken
 * formatter (wrong rounding, wrong unit boundary, minutes/seconds swapped) actually fails these.
 */
class GalleryFormatTest {

    // ---- formatDurationClock ----

    @Test
    fun `formatDurationClock renders the verified device sample -- 300160ms as 5-00`() {
        assertEquals("5:00", formatDurationClock(300_160L))
    }

    @Test
    fun `formatDurationClock pads seconds under ten`() {
        assertEquals("1:05", formatDurationClock(65_000L))
    }

    @Test
    fun `formatDurationClock floors partial seconds rather than rounding up`() {
        assertEquals("0:59", formatDurationClock(59_999L))
    }

    @Test
    fun `formatDurationClock treats a negative duration as zero`() {
        assertEquals("0:00", formatDurationClock(-500L))
    }

    // ---- formatFileSize ----

    @Test
    fun `formatFileSize renders the verified device sample -- 2420830 bytes as 2_4 MB`() {
        assertEquals("2.4 MB", formatFileSize(2_420_830L))
    }

    @Test
    fun `formatFileSize renders sub-kilobyte sizes as plain bytes`() {
        assertEquals("512 B", formatFileSize(512L))
    }

    @Test
    fun `formatFileSize renders kilobyte-range sizes with one decimal`() {
        assertEquals("1.5 KB", formatFileSize(1_500L))
    }

    @Test
    fun `formatFileSize switches units at exactly one million bytes, not one below`() {
        assertEquals("999.9 KB", formatFileSize(999_900L))
        assertEquals("1.0 MB", formatFileSize(1_000_000L))
    }

    // ---- formatCapturedAt ----

    @Test
    fun `formatCapturedAt renders day, month, year, hour, and minute in the local calendar`() {
        // Built from java.util.Calendar fields (not SimpleDateFormat, which is what the function
        // under test itself uses), and in the default timezone -- both formatCapturedAt and this
        // expected-value computation implicitly use the same JVM-default timezone, so the
        // assertion is stable across machines without needing to fix one explicitly.
        val calendar = java.util.Calendar.getInstance()
        calendar.set(2026, java.util.Calendar.AUGUST, 21, 11, 39, 7)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val millis = calendar.timeInMillis

        val expected = String.format(
            java.util.Locale.US,
            "%02d/%02d/%04d %02d:%02d",
            calendar.get(java.util.Calendar.DAY_OF_MONTH),
            calendar.get(java.util.Calendar.MONTH) + 1,
            calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE),
        )

        assertEquals(expected, formatCapturedAt(millis))
    }
}
