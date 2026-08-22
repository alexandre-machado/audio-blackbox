package cc.machado.audioblackbox.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Instrumented coverage for [MediaStoreSink.queryRecordings] against a real `ContentResolver` /
 * `MediaStore`, closing the gap `@rev` found on PR #61 (issue #62):
 * [MediaStoreSinkFilterTest] exercises [MediaStoreSink.matchesRecordingPrefix] directly and would
 * not notice if the SQL `LIKE ? ESCAPE '\'` clause in `queryRecordings()` were ever deleted --
 * this is the tier where that clause is actually load-bearing.
 *
 * ## The three locations (from [MediaStoreSink]'s own class/interface doc, not guessed)
 * - `Recordings/Blackbox/` -- current API 31+ destination.
 * - `Music/Blackbox/` -- API 29-30 fallback (`Recordings/` isn't a valid `MediaStore` root below
 *   API 31).
 * - `Music/Recordings/` -- legacy pre-issue-#33 destination, still on users' devices, never
 *   migrated.
 *
 * `queryRecordings()` deliberately filters by `DISPLAY_NAME` prefix only, never by
 * `RELATIVE_PATH` (see its KDoc) -- this is what lets one query find all three without a
 * per-location branch. [queryRecordings_unionsKnownLocations] inserts real rows in the two
 * locations this tier's emulator (API 30 -- `scripts/ci/avd.env`) can actually accept, plus a
 * best-effort attempt at the API-31-only `Recordings/` root; the same documented ceiling
 * `InterruptionSpliceTest` already works around on this tier.
 *
 * ## Teardown
 * Every row this test inserts is tracked in [insertedUris] and deleted directly via
 * `ContentResolver.delete`, in [tearDown], which JUnit still runs after a failed `@Test` -- this
 * must not go through [MediaStoreSink.delete] itself, since a bug in the code under test must
 * never leak a row into the shared emulator's `MediaStore` collection.
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreSinkQueryTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val sink by lazy { MediaStoreSink(context) }
    private val resolver get() = context.contentResolver
    private val collection: Uri
        get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    // Unique per test run so this test's own rows can never collide with a real recording already
    // sitting on this emulator instance, or a row leaked by another instrumented test in the same
    // job (e.g. InterruptionSpliceTest's real export, which is not cleaned up in its own teardown).
    private val runId = UUID.randomUUID().toString().take(8)

    private val insertedUris = mutableListOf<Uri>()

    @After
    fun tearDown() {
        for (uri in insertedUris) {
            try {
                resolver.delete(uri, null, null)
            } catch (e: SecurityException) {
                // Best effort: nothing else this teardown can do about a row it can't delete.
            }
        }
        insertedUris.clear()
    }

    /** Inserts a fully-committed (non-pending) row and opens+closes its output stream once, so the
     * underlying file is actually materialized on disk the way a real export leaves it -- not just
     * a bare row of column values. Returns `null` if the platform rejects the insert outright
     * (expected for the API-31-only `Recordings/` root on this tier's API 30 emulator). */
    private fun insertRow(displayName: String, relativePath: String, mimeType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Audio.Media.IS_PENDING, 0)
        }
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        insertedUris += uri
        try {
            resolver.openOutputStream(uri)?.use { /* zero-byte file is enough for this test */ }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            insertedUris -= uri
            return null
        }
        return uri
    }

    @Test
    fun queryRecordings_unionsKnownLocations() {
        // Oracle: this must fail if queryRecordings() were ever scoped to a single RELATIVE_PATH
        // (e.g. hardcoded to just the current API 31+ destination) -- these two rows sit in real,
        // distinct locations this app has actually written to across its history (issue #33), and
        // a single-path query would miss at least one of them.
        val musicBlackboxName = "blackbox_${runId}_music_blackbox.m4a"
        val musicBlackbox = insertRow(
            displayName = musicBlackboxName,
            relativePath = "${Environment.DIRECTORY_MUSIC}/Blackbox/",
            mimeType = "audio/mp4",
        )
        assertTrue("Music/Blackbox/ insert (API 29-30 fallback, issue #33) unexpectedly failed", musicBlackbox != null)

        val legacyName = "blackbox_${runId}_legacy.wav"
        val legacy = insertRow(
            displayName = legacyName,
            relativePath = "${Environment.DIRECTORY_MUSIC}/Recordings/",
            mimeType = "audio/wav",
        )
        assertTrue("Music/Recordings/ insert (legacy pre-#33 location) unexpectedly failed", legacy != null)

        // Recordings/ is only a valid MediaStore top-level root from API 31 -- this tier's
        // emulator runs API 30 (scripts/ci/avd.env), the same ceiling InterruptionSpliceTest
        // already documents. Attempt it anyway: if the platform accepts it here, the assertion
        // below covers the real API 31+ location for real; if rejected (expected on this tier),
        // that row is simply absent from the union check rather than failing the whole test.
        val recordingsBlackboxName = "blackbox_${runId}_recordings_blackbox.m4a"
        val recordingsBlackbox = insertRow(
            displayName = recordingsBlackboxName,
            relativePath = "Recordings/Blackbox/",
            mimeType = "audio/mp4",
        )

        val rows = sink.queryRecordings()
        val displayNames = rows.map { it.displayName }.toSet()
        assertTrue("Music/Blackbox/ row missing from queryRecordings()", musicBlackboxName in displayNames)
        assertTrue("Music/Recordings/ row missing from queryRecordings()", legacyName in displayNames)
        if (recordingsBlackbox != null) {
            assertTrue(
                "this tier's emulator accepted a Recordings/Blackbox/ insert, so " +
                    "queryRecordings() must return it too",
                recordingsBlackboxName in displayNames,
            )
        }

        // Round-trip sanity beyond mere presence: the MIME type this test asked for must come
        // back as the row's own declared MIME type, not a single hardcoded constant (issue #7 --
        // a .wav shared as audio/mp4 fails to open).
        val legacyRow = rows.first { it.displayName == legacyName }
        assertTrue("legacy .wav row's MIME type must be its own, not a hardcoded default", legacyRow.mimeType == "audio/wav")
    }

    @Test
    fun queryRecordings_excludesWildcardCollisionRow() {
        // Oracle for the ESCAPE clause (PR #61 review, `@rev`/`@sec` finding): SQL LIKE's `_` is a
        // single-character wildcard. An unescaped "blackbox_%" pattern would also match a row
        // whose 9th character is anything else -- exactly the shape a foreign app's file could
        // plausibly take -- and it must never be attributed to this app.
        //
        // Caveat, stated plainly rather than left implicit: matchesRecordingPrefix() (the per-row
        // Kotlin filter applied after the SQL query -- see queryRecordings()'s own comment) already
        // excludes this name via a literal startsWith check, independent of what the SQL layer
        // does. That defense-in-depth means this specific test cannot, by itself, distinguish an
        // escaped SQL clause from a deleted one -- removing only the SQL escape does not change
        // this test's outcome, which is exactly the finding that opened issue #62. It is kept here
        // because it is still a real, correct behavioural guarantee and an explicit acceptance
        // criterion; the test that actually bites when the SQL clause is removed is
        // queryRecordings_unionsKnownLocations below (see the PR description for the scratch-commit
        // proof).
        val wrongName = "blackboxZ${runId}_wrong.m4a"
        val ownName = "blackbox_${runId}_own.m4a"
        val relativePath = "${Environment.DIRECTORY_MUSIC}/Blackbox/"
        insertRow(displayName = wrongName, relativePath = relativePath, mimeType = "audio/mp4")
        insertRow(displayName = ownName, relativePath = relativePath, mimeType = "audio/mp4")

        val displayNames = sink.queryRecordings().map { it.displayName }.toSet()
        assertTrue("this app's own row must be present", ownName in displayNames)
        assertFalse(
            "a name differing only in the wildcard-sensitive position must not be treated as " +
                "this app's own recording",
            wrongName in displayNames,
        )
    }
}
