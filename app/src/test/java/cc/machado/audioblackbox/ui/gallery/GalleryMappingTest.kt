package cc.machado.audioblackbox.ui.gallery

import cc.machado.audioblackbox.export.RecordingRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * The oracle issue #7 requires: [android.net.Uri]s are mocked (final Android framework class,
 * mockable via mockito-core's inline mock maker without Robolectric -- same convention as
 * [cc.machado.audioblackbox.service.AudioFocusTrackerTest]) purely as opaque identities, since
 * nothing here needs a real content:// uri to resolve.
 */
class GalleryMappingTest {

    // ---- parseCapturedAtMillis ----

    @Test
    fun `parseCapturedAtMillis reads the timestamp out of a well-formed filename`() {
        val millis = GalleryViewModel.parseCapturedAtMillis("blackbox_2026-08-21_11-39-07_5min.m4a")
        assertNotNullAndMatches(millis, year = 2026, month = 8, day = 21, hour = 11, minute = 39, second = 7)
    }

    @Test
    fun `parseCapturedAtMillis reads a legacy wav filename the same way`() {
        val millis = GalleryViewModel.parseCapturedAtMillis("blackbox_2025-01-05_23-10-00_30min.wav")
        assertNotNullAndMatches(millis, year = 2025, month = 1, day = 5, hour = 23, minute = 10, second = 0)
    }

    @Test
    fun `parseCapturedAtMillis returns null for a name that does not match the pattern`() {
        assertNull(GalleryViewModel.parseCapturedAtMillis("not_a_blackbox_file.m4a"))
        assertNull(GalleryViewModel.parseCapturedAtMillis("blackbox_totally-wrong-shape.m4a"))
    }

    private fun assertNotNullAndMatches(
        millis: Long?,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ) {
        requireNotNull(millis)
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        assertEquals(year, calendar.get(java.util.Calendar.YEAR))
        assertEquals(month - 1, calendar.get(java.util.Calendar.MONTH))
        assertEquals(day, calendar.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(hour, calendar.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(minute, calendar.get(java.util.Calendar.MINUTE))
        assertEquals(second, calendar.get(java.util.Calendar.SECOND))
    }

    // ---- mapRowsToItems: the mandatory multi-location/multi-format case ----

    @Test
    fun `mapRowsToItems sorts rows from all three locations and both formats into one newest-first list`() {
        // A test with only .m4a in Recordings-Blackbox cannot catch this feature's main bug class
        // (issue #7's explicit testing bar) -- so this fixture deliberately mixes all three
        // locations this app has ever written to and both file formats, and relies on nothing but
        // the DISPLAY_NAME prefix mapRowsToItems/queryRecordings actually filter on, never a
        // relative path.
        val oldestLegacyWav = RecordingRow(
            uri = mock(),
            displayName = "blackbox_2025-01-05_23-10-00_30min.wav", // legacy Music/Recordings/
            mimeType = "audio/wav",
            sizeBytes = 158_000_000L,
            durationMillis = 1_800_000L,
            dateAddedMillis = 1_735_000_000_000L,
        )
        val middleApi29FallbackM4a = RecordingRow(
            uri = mock(),
            displayName = "blackbox_2026-03-10_08-00-00_15min.m4a", // Music/Blackbox/ (API 29-30)
            mimeType = "audio/mp4",
            sizeBytes = 7_000_000L,
            durationMillis = 900_000L,
            dateAddedMillis = 1_741_593_600_000L,
        )
        val newestApi31M4a = RecordingRow(
            uri = mock(),
            displayName = "blackbox_2026-08-21_11-39-07_5min.m4a", // Recordings/Blackbox/ (API 31+)
            mimeType = "audio/mp4",
            sizeBytes = 2_420_830L,
            durationMillis = 300_160L,
            dateAddedMillis = 1_755_776_347_000L,
        )

        // Deliberately fed out of chronological order, so a correct sort is what makes the test
        // pass, not the input order happening to already be right.
        val items = GalleryViewModel.mapRowsToItems(
            listOf(middleApi29FallbackM4a, oldestLegacyWav, newestApi31M4a),
        )

        assertEquals(3, items.size)
        assertEquals("blackbox_2026-08-21_11-39-07_5min.m4a", items[0].displayName)
        assertEquals("audio/mp4", items[0].mimeType)
        assertEquals("blackbox_2026-03-10_08-00-00_15min.m4a", items[1].displayName)
        assertEquals("blackbox_2025-01-05_23-10-00_30min.wav", items[2].displayName)
        assertEquals("audio/wav", items[2].mimeType)

        // Each item's own mime type survives the mapping unchanged -- this is what the share
        // action later reads per-file, never a single hardcoded constant.
        assertTrue(items.all { it.mimeType == "audio/mp4" || it.mimeType == "audio/wav" })
    }

    @Test
    fun `mapRowsToItems falls back to dateAdded for a row whose name does not carry a parseable timestamp`() {
        val row = RecordingRow(
            uri = mock(),
            displayName = "blackbox_odd_name.m4a",
            mimeType = "audio/mp4",
            sizeBytes = 100L,
            durationMillis = 1_000L,
            dateAddedMillis = 42_000L,
        )

        val items = GalleryViewModel.mapRowsToItems(listOf(row))

        assertEquals(42_000L, items.single().capturedAtMillis)
    }

    @Test
    fun `mapRowsToItems on an empty query result is an empty list, not an error`() {
        assertEquals(emptyList<RecordingItem>(), GalleryViewModel.mapRowsToItems(emptyList()))
    }

    // ---- buildUiState: empty state, loading, and the single-player-invariant derivation ----

    @Test
    fun `buildUiState reports loading while the recordings list is still null`() {
        val state = GalleryViewModel.buildUiState(
            recordings = null,
            playback = PlaybackState.Idle,
            positionMillis = 0L,
            pendingDelete = null,
        )
        assertTrue(state.isLoading)
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun `buildUiState reports the empty state -- distinct from loading -- once the query returned nothing`() {
        val state = GalleryViewModel.buildUiState(
            recordings = emptyList(),
            playback = PlaybackState.Idle,
            positionMillis = 0L,
            pendingDelete = null,
        )
        assertEquals(false, state.isLoading)
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun `buildUiState marks only the item matching the shared playback uri as Playing -- the others as Stopped`() {
        val itemA = recordingItem("A")
        val itemB = recordingItem("B")

        // Starting B's playback is represented as a single PlaybackState.Playing(B) -- there is no
        // code path in this data model that can produce two items reporting Playing at once, since
        // both items derive their own state from this one shared value.
        val state = GalleryViewModel.buildUiState(
            recordings = listOf(itemA, itemB),
            playback = PlaybackState.Playing(itemB.uri),
            positionMillis = 12_000L,
            pendingDelete = null,
        )

        val stateA = state.items.single { it.recording.displayName == "A" }.playback
        val stateB = state.items.single { it.recording.displayName == "B" }.playback

        assertEquals(ItemPlaybackState.Stopped, stateA)
        assertEquals(ItemPlaybackState.Playing(12_000L, itemB.durationMillis), stateB)
    }

    @Test
    fun `buildUiState marks the matching item Paused, not Playing, when playback is Paused`() {
        val item = recordingItem("A")
        val state = GalleryViewModel.buildUiState(
            recordings = listOf(item),
            playback = PlaybackState.Paused(item.uri),
            positionMillis = 5_000L,
            pendingDelete = null,
        )
        assertEquals(ItemPlaybackState.Paused(5_000L, item.durationMillis), state.items.single().playback)
    }

    @Test
    fun `buildUiState carries the pending delete target through unchanged`() {
        val item = recordingItem("A")
        val state = GalleryViewModel.buildUiState(
            recordings = listOf(item),
            playback = PlaybackState.Idle,
            positionMillis = 0L,
            pendingDelete = item,
        )
        assertEquals(item, state.pendingDelete)
    }

    private fun recordingItem(name: String) = RecordingItem(
        uri = mock(),
        displayName = name,
        mimeType = "audio/mp4",
        sizeBytes = 1_000L,
        durationMillis = 60_000L,
        capturedAtMillis = 0L,
    )
}
