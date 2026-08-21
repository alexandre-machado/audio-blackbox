package cc.machado.audioblackbox.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR #61 review (`@rev`/`@sec` finding): SQL `LIKE`'s `_` is itself a single-character wildcard,
 * so an unescaped `"blackbox_%"` pattern also matches a name that only shares seven of those eight
 * characters, e.g. `"blackboxZ..."`. [MediaStoreSink.queryRecordings] now escapes that `_` in its
 * `LIKE` selection (untestable at this tier without Android/a real `ContentResolver`), but
 * [MediaStoreSink.matchesRecordingPrefix] is the plain-Kotlin equivalent check applied to every
 * row regardless -- this is the oracle that actually proves the escape's intent: a name differing
 * only in the wildcard-sensitive position must be excluded, not just any old file.
 */
class MediaStoreSinkFilterTest {

    @Test
    fun `accepts a genuine blackbox_ prefixed name`() {
        assertTrue(MediaStoreSink.matchesRecordingPrefix("blackbox_2026-08-21_11-39-07_5min.m4a"))
    }

    @Test
    fun `rejects a name that only differs in the underscore -- the exact LIKE wildcard leak`() {
        // "_" is SQL LIKE's own single-character wildcard -- an unescaped "blackbox_%" pattern
        // would also match this name. The fix must reject it at the literal-character level.
        assertFalse(MediaStoreSink.matchesRecordingPrefix("blackboxZ2026-08-21_11-39-07_5min.m4a"))
    }

    @Test
    fun `rejects an unrelated file entirely`() {
        assertFalse(MediaStoreSink.matchesRecordingPrefix("random_song.mp3"))
    }

    @Test
    fun `rejects a name that merely contains the prefix but does not start with it`() {
        assertFalse(MediaStoreSink.matchesRecordingPrefix("not_blackbox_2026-08-21_11-39-07_5min.m4a"))
    }
}
