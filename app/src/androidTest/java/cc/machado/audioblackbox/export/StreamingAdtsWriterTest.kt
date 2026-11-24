package cc.machado.audioblackbox.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.machado.audioblackbox.audio.QualityPreset
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamingAdtsWriterTest {
    /** Oracle: real MediaStore bytes decode to the supplied tone BEFORE finish, then the same
     * file grows and decodes after finish. A muxer-only implementation fails the first decode.
     * Synchronous writes provide the frame boundary; no sleeps or timing-based synchronization.
     */
    @Test fun liveFileDecodesBeforeAndAfterStopForEveryPreset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sink = MediaStoreSink(context)
        for (preset in QualityPreset.entries) {
            val config = preset.config(5)
            val name = "blackbox_${UUID.randomUUID()}_forward.aac"
            val target = sink.openStreaming(name, StreamingAdtsWriter.MIME_TYPE)
            val snapshot = File.createTempFile("live-adts-", ".aac", context.cacheDir)
            try {
                StreamingAdtsWriter(target, config).use { writer ->
                    val pcm = ToneGenerator.tone(
                        frequencyHz = 1000.0, sampleRateHz = preset.sampleRateHz,
                        durationMillis = 2000L, channelCount = preset.channelCount,
                    )
                    writer.write(pcm)
                    assertFalse(writer.isSessionFinished)
                    val before = context.contentResolver.openInputStream(target.uri)!!.use { it.readBytes() }
                    assertTrue("live file must already contain frames", before.isNotEmpty())
                    snapshot.writeBytes(before)
                    val mid = AacDecodeSupport.decode(snapshot)
                    assertTone(mid, preset)
                    assertTrue("at least one second decoded before stop", mid.pcm.size >= config.bytesPerSecond)
                    val liveRow = sink.queryRecordings().single { it.uri == target.uri }
                    assertEquals(name, liveRow.displayName)
                    // Exercise MIME recognition independently of accepts()'s filename fallback.
                    // Provider alias spelling is not the contract; recognition + decoding is.
                    assertTrue(StreamingAdtsWriter.accepts(liveRow.mimeType, ""))
                    assertTrue(liveRow.displayName.endsWith(".aac"))

                    writer.write(pcm)
                    writer.finish()
                    target.finish()
                    target.refinalizeMetadata()
                    val after = context.contentResolver.openInputStream(target.uri)!!.use { it.readBytes() }
                    assertTrue("same file grows after more PCM and EOS", after.size > before.size)
                    assertArrayEquals("previous frames are never rewritten", before, after.copyOf(before.size))
                    snapshot.writeBytes(after)
                    val completed = AacDecodeSupport.decode(snapshot)
                    assertTone(completed, preset)
                    assertTrue("additional audio survives finalization", completed.pcm.size > mid.pcm.size)
                    val finalRow = sink.queryRecordings().single { it.uri == target.uri }
                    assertTrue(StreamingAdtsWriter.accepts(finalRow.mimeType, ""))
                    assertTrue(finalRow.displayName.endsWith(".aac"))
                    assertEquals(after.size.toLong(), finalRow.sizeBytes)
                    assertTrue("refinalized duration", finalRow.durationMillis > 0)
                    val decodedMillis = completed.pcm.size * 1000L / config.bytesPerSecond
                    assertTrue("duration ${finalRow.durationMillis} must match decoded $decodedMillis ms",
                        kotlin.math.abs(finalRow.durationMillis - decodedMillis) <= 150L)
                }
            } finally {
                target.close()
                sink.delete(target.uri)
                snapshot.delete()
            }
        }
    }

    private fun assertTone(decoded: DecodedAac, preset: QualityPreset) {
        assertEquals(preset.sampleRateHz, decoded.sampleRateHz)
        assertEquals(preset.channelCount, decoded.channelCount)
        val tone = GoertzelDetector.energyAt(decoded.pcm, 1000.0, preset.sampleRateHz, preset.channelCount)
        val offTone = GoertzelDetector.energyAt(decoded.pcm, 500.0, preset.sampleRateHz, preset.channelCount)
        assertTrue("decoded tone energy", tone > 500.0)
        assertTrue("decoded tone dominates off-frequency energy", tone > offTone * 5)
    }
}
