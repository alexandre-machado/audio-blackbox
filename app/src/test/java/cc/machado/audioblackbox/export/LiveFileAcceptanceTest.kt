package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.ui.gallery.GalleryViewModel
import java.util.Date
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock

class LiveFileAcceptanceTest {
    /** Oracle: the live engine's actual generated name is AAC, accepted by the repository filter,
     * and its timestamp survives gallery parsing rather than falling back to row insertion time.
     */
    @Test fun liveNameAcceptedByGallery() {
        val date = Date(1_700_000_000_000L)
        val name = ForwardRecordingEngine.generateDisplayName(date)
        assertTrue(name.endsWith("_forward.aac"))
        assertEquals("audio/aac-adts", StreamingAdtsWriter.MIME_TYPE)
        assertTrue(MediaStoreSink.matchesRecordingPrefix(name))
        assertEquals(date.time, GalleryViewModel.parseCapturedAtMillis(name))
    }

    /** Oracle: the format display accepts the provider MIME without needing an .aac suffix,
     * retains the legacy alias/extension fallback, and does not mislabel MP4 or WAV as ADTS. */
    @Test fun acceptsProviderMimeAndLegacyAlias() {
        assertTrue(StreamingAdtsWriter.accepts("audio/aac-adts", "blackbox_recording"))
        assertTrue(StreamingAdtsWriter.accepts("audio/aac", "blackbox_recording"))
        assertTrue(StreamingAdtsWriter.accepts("application/octet-stream", "blackbox_recording.AAC"))
        assertFalse(StreamingAdtsWriter.accepts("audio/mp4", "blackbox_recording.m4a"))
        assertFalse(StreamingAdtsWriter.accepts("audio/wav", "blackbox_recording.wav"))
    }

    /** Oracle: gallery mapping preserves the provider's MIME and measured duration exactly,
     * including unknown duration (zero); neither filtering ADTS nor inventing a duration is valid. */
    @Test fun galleryPreservesAdtsMimeAndMeasuredDuration() {
        for (duration in listOf(0L, 3072L)) {
            val row = RecordingRow(mock(), "blackbox_recording.aac", "audio/aac-adts", 12345L,
                duration, 1700000000000L)
            val item = GalleryViewModel.mapRowsToItems(listOf(row)).single()
            assertEquals(row.uri, item.uri)
            assertEquals("audio/aac-adts", item.mimeType)
            assertEquals(duration, item.durationMillis)
            assertEquals(12345L, item.sizeBytes)
        }
    }
}
