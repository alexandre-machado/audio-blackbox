package cc.machado.audioblackbox.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.machado.audioblackbox.audio.AudioConfig
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class StreamingAacWriterCapacityTest {
    private val cacheDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test
    fun writeLargeFile_doesNotCorrupt() {
        val config = AudioConfig(sampleRateHz = 44_100, channelCount = 1)
        val outFile = File.createTempFile("large_aac_", ".m4a", cacheDir)
        try {
            val writer = StreamingAacWriter(outFile, config)
            val chunk = ByteArray(4096)
            // 90 minutes = 5400 seconds. At 44100 Hz * 2 bytes = 88200 bytes/sec
            // Total bytes = 476,280,000. Chunks of 4096 bytes = 116,280 chunks.
            // Let's write 117,000 chunks!
            for (i in 0 until 117000) {
                writer.write(chunk)
            }
            writer.finish()
            
            assertTrue(outFile.length() > 0)
            // verify moov exists by decoding
            val decoded = AacDecodeSupport.decode(outFile)
            assertTrue(decoded.pcm.isNotEmpty())
        } finally {
            outFile.delete()
        }
    }
}
