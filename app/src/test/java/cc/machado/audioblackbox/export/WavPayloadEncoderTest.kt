package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WavPayloadEncoder] adapter tests (issue #32): proves the [PayloadEncoder] wiring around
 * [WavWriter] byte-for-byte matches calling [WavWriter.write] directly (so [WavWriterTest]/
 * [WavRoundTripTest]'s existing coverage of the actual header/byte layout still applies through
 * this seam), plus the identifiers ([PayloadEncoder.mimeType]/[PayloadEncoder.fileExtension]) and
 * cancellation behavior [ExportEngine] now depends on instead of its own inlined chunk loop.
 */
class WavPayloadEncoderTest {

    private val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)

    @Test
    fun `declares the wav MIME type and extension`() {
        assertEquals("audio/wav", WavPayloadEncoder.mimeType)
        assertEquals("wav", WavPayloadEncoder.fileExtension)
    }

    @Test
    fun `encode output is byte-identical to WavWriter write`() {
        val payload = ByteArray(2000) { (it % 256).toByte() }

        val viaEncoder = ByteArrayOutputStream()
        WavPayloadEncoder.encode(config, payload, viaEncoder, isCancelled = { false })

        val viaWriter = ByteArrayOutputStream()
        WavWriter.write(viaWriter, config, payload)

        assertArrayEquals(viaWriter.toByteArray(), viaEncoder.toByteArray())
    }

    @Test
    fun `isCancelled stops the write early, leaving a partial (never-committed) output`() {
        val payload = ByteArray(200_000) { 1 } // spans multiple 64KB chunks
        val out = ByteArrayOutputStream()
        var cancelAfterFirstChunk = false

        WavPayloadEncoder.encode(config, payload, out, isCancelled = {
            val cancel = cancelAfterFirstChunk
            cancelAfterFirstChunk = true
            cancel
        })

        // Header (44 bytes) plus exactly one 64KB chunk, then stopped -- proves the cancellation
        // check runs between chunks, not only once at the very start or the very end.
        assertTrue(
            "expected a partial write shorter than the full payload",
            out.size() < WavWriter.HEADER_SIZE_BYTES + payload.size,
        )
        assertTrue("expected at least the header to have been written", out.size() >= WavWriter.HEADER_SIZE_BYTES)
    }
}
