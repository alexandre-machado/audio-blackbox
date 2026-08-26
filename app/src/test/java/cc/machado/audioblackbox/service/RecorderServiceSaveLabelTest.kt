package cc.machado.audioblackbox.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The mandatory regression test issue #121 requires: [RecorderService.resolveSavedMinutes] is the
 * single oracle both [RecorderService.handleSave] (the exported file's `_Nmin` name) and
 * [RecorderNotification.build] (the notification's "Save last N min" action label) now use to
 * decide what "N" means, replacing the 5/15/30-minute selector that used to make this trivially
 * true (an option could only ever be tapped once the buffer held that much audio in full).
 *
 * Pinned here against real arithmetic, not a re-derivation of the production formula: each
 * expected value is computed independently (buffered milliseconds divided by 60,000, floored) so
 * a regression that silently swaps in [handleSave]'s `requestedMinutes`/capacity instead of the
 * actually-buffered amount -- exactly the "N min promised, less delivered" bug class this issue
 * closes -- fails this test.
 */
class RecorderServiceSaveLabelTest {

    @Test
    fun `an empty buffer resolves to 0 saved minutes, never the configured capacity`() {
        assertEquals(0, RecorderService.resolveSavedMinutes(bufferedMillis = 0L, capacityMinutes = 30))
    }

    @Test
    fun `a partially-filled buffer resolves to its own floored minute count, not the configured capacity`() {
        // 4:32 buffered, of a 30 min configured capacity -- must resolve to 4, never 30.
        val bufferedMillis = 4 * 60_000L + 32_000L
        assertEquals(
            "a partial buffer must be labeled by what is actually buffered -- labeling it with " +
                "the configured capacity instead would name a file _30min.m4a that actually " +
                "holds 4:32 of audio",
            4,
            RecorderService.resolveSavedMinutes(bufferedMillis, capacityMinutes = 30),
        )
    }

    @Test
    fun `a partial buffer that has not yet reached one full minute resolves to 0, not 1`() {
        // 45 seconds buffered must floor to 0 min, not round up to 1 -- rounding up would still
        // be a promise ("1 min") the file does not keep.
        assertEquals(0, RecorderService.resolveSavedMinutes(bufferedMillis = 45_000L, capacityMinutes = 30))
    }

    @Test
    fun `a full buffer resolves to exactly the configured capacity`() {
        assertEquals(30, RecorderService.resolveSavedMinutes(bufferedMillis = 30 * 60_000L, capacityMinutes = 30))
    }

    @Test
    fun `a buffered reading above capacity is clamped down, never reported as more than what was requested`() {
        // Defensive: a momentarily stale reading (e.g. racing a capacity shrink) must not resolve
        // to more minutes than the export itself will ever actually request.
        assertEquals(30, RecorderService.resolveSavedMinutes(bufferedMillis = 45 * 60_000L, capacityMinutes = 30))
    }
}
