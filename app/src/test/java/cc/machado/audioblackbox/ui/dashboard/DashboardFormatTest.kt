package cc.machado.audioblackbox.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins exact `MM:SS` output for [formatMillisAsClock] -- the issue's own example
 * ("12:34 de 30:00 em memória") is the primary oracle: a rounding or off-by-one regression in
 * the seconds/minutes split would fail this immediately. */
class DashboardFormatTest {

    @Test
    fun `zero millis formats as 00 colon 00`() {
        assertEquals("00:00", formatMillisAsClock(0L))
    }

    @Test
    fun `754 seconds worth of millis formats as the issue's own 12 34 example`() {
        assertEquals("12:34", formatMillisAsClock(754_000L))
    }

    @Test
    fun `exactly thirty minutes formats as 30 colon 00`() {
        assertEquals("30:00", formatMillisAsClock(30 * 60_000L))
    }

    @Test
    fun `sub-second remainder is truncated, not rounded up`() {
        // 59 seconds and 900ms must not round up to 01:00.
        assertEquals("00:59", formatMillisAsClock(59_900L))
    }

    @Test
    fun `negative input is clamped to zero instead of throwing or underflowing`() {
        assertEquals("00:00", formatMillisAsClock(-1_000L))
    }
}
