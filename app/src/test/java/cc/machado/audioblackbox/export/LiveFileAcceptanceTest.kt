package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.ui.gallery.GalleryViewModel
import java.util.Date
import org.junit.Assert.*
import org.junit.Test

class LiveFileAcceptanceTest {
    /** Oracle: the live engine's actual generated name is AAC, accepted by the repository filter,
     * and its timestamp survives gallery parsing rather than falling back to row insertion time.
     */
    @Test fun liveNameAcceptedByGallery() {
        val date = Date(1_700_000_000_000L)
        val name = ForwardRecordingEngine.generateDisplayName(date)
        assertTrue(name.endsWith("_forward.aac"))
        assertEquals("audio/aac", StreamingAdtsWriter.MIME_TYPE)
        assertTrue(MediaStoreSink.matchesRecordingPrefix(name))
        assertEquals(date.time, GalleryViewModel.parseCapturedAtMillis(name))
    }
}
