package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.audio.CaptureErrorReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins exact `MM:SS` output for [formatMillisAsClock] -- the issue's own example
 * ("12:34 de 30:00 em memória") is the primary oracle: a rounding or off-by-one regression in
 * the seconds/minutes split would fail this immediately.
 *
 * Also tests [CaptureErrorReason.toUserMessageRes] (issue #39), ensuring every capture error
 * reason maps exhaustively to an actionable user-facing string resource. */
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

    // ---- CaptureErrorReason.toUserMessageRes (issue #39) ----

    @Test
    fun `every CaptureErrorReason enum entry maps to a valid non-zero string resource`() {
        for (reason in CaptureErrorReason.entries) {
            val resId = reason.toUserMessageRes()
            assertTrue("Expected non-zero string resource for $reason", resId != 0)
        }
    }

    @Test
    fun `each CaptureErrorReason maps to its distinct expected string resource`() {
        assertEquals(
            R.string.capture_error_buffer_allocation_failed,
            CaptureErrorReason.BUFFER_ALLOCATION_FAILED.toUserMessageRes(),
        )
        assertEquals(
            R.string.capture_error_unsupported_config,
            CaptureErrorReason.UNSUPPORTED_CONFIG.toUserMessageRes(),
        )
        assertEquals(
            R.string.capture_error_audio_record_init_failed,
            CaptureErrorReason.AUDIO_RECORD_INIT_FAILED.toUserMessageRes(),
        )
        assertEquals(
            R.string.capture_error_read_invalid_operation,
            CaptureErrorReason.READ_INVALID_OPERATION.toUserMessageRes(),
        )
        assertEquals(
            R.string.capture_error_read_bad_value,
            CaptureErrorReason.READ_BAD_VALUE.toUserMessageRes(),
        )
        assertEquals(
            R.string.capture_error_read_dead_object,
            CaptureErrorReason.READ_DEAD_OBJECT.toUserMessageRes(),
        )
        assertEquals(
            R.string.capture_error_read_unknown_error,
            CaptureErrorReason.READ_UNKNOWN_ERROR.toUserMessageRes(),
        )
    }

    @Test
    fun `every CaptureErrorReason maps to a unique user-facing string resource`() {
        val resourceIds = CaptureErrorReason.entries.map { it.toUserMessageRes() }
        val distinctResourceIds = resourceIds.toSet()
        assertEquals(
            "Every CaptureErrorReason must map to a unique string resource",
            CaptureErrorReason.entries.size,
            distinctResourceIds.size,
        )
    }
}
