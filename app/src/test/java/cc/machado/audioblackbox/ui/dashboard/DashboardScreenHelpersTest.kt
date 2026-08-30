package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DashboardScreenHelpersTest {

    @Test
    fun `parseTimestampFromFilename extracts correct timestamp from valid filename`() {
        val filename = "blackbox_2026-08-30_14-30-00_10min.m4a"
        val timestamp = parseTimestampFromFilename(filename)
        assertNotNull("Timestamp should not be null for standard filename", timestamp)

        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = timestamp!!
        assertEquals(2026, cal.get(Calendar.YEAR))
        assertEquals(Calendar.AUGUST, cal.get(Calendar.MONTH))
        assertEquals(30, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
    }

    @Test
    fun `parseTimestampFromFilename returns null for invalid filename format`() {
        assertNull(parseTimestampFromFilename("random_file.m4a"))
        assertNull(parseTimestampFromFilename("blackbox_invalid_date.m4a"))
        assertNull(parseTimestampFromFilename(""))
    }

    @Test
    fun `BuildConfig contains valid BUILD_DATE`() {
        val buildDate = BuildConfig.BUILD_DATE
        assertNotNull(buildDate)
        assertTrue("BUILD_DATE should match YYYY-MM-DD pattern: $buildDate", buildDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }
}
