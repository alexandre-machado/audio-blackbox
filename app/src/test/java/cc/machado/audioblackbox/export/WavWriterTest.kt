package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-level assertions on [WavWriter]'s 44-byte header, for two different configs
 * (16 kHz mono, 44.1 kHz stereo) so a header that is only correct for the default config cannot
 * pass silently -- exactly the bug class this test exists to catch (issue #5).
 *
 * Every assertion here reads a specific byte offset/field and checks its literal value, so any
 * hardcoded/wrong-config header (e.g. always emitting 16000/1/16 regardless of the config passed
 * in) fails at the field that's wrong, not just "some byte differs".
 */
class WavWriterTest {

    @Test
    fun `header is exactly 44 bytes for 16kHz mono`() {
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        val payload = ByteArray(1000)
        assertHeaderCorrect(config, payload)
    }

    @Test
    fun `header is exactly 44 bytes for 44100Hz stereo`() {
        val config = AudioConfig(sampleRateHz = 44_100, channelCount = 2)
        val payload = ByteArray(2048)
        assertHeaderCorrect(config, payload)
    }

    @Test
    fun `chunk sizes track a different payload length than the first test used`() {
        // Same config as the mono case above, different payload size -- proves ChunkSize/
        // Subchunk2Size are derived from the actual payload, not a size baked in alongside the
        // config. A broken implementation that hardcodes chunk sizes for "the first payload it
        // saw" would fail only here, not in the header-shape tests above.
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        val payload = ByteArray(4321)
        assertHeaderCorrect(config, payload)
    }

    private fun assertHeaderCorrect(config: AudioConfig, payload: ByteArray) {
        val out = ByteArrayOutputStream()
        WavWriter.write(out, config, payload)
        val bytes = out.toByteArray()

        assertEquals(WavWriter.HEADER_SIZE_BYTES + payload.size, bytes.size)

        val header = bytes.copyOfRange(0, WavWriter.HEADER_SIZE_BYTES)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("RIFF", ascii(header, 0, 4))
        val riffChunkSize = buffer.getInt(4)
        assertEquals(36 + payload.size, riffChunkSize)
        assertEquals("WAVE", ascii(header, 8, 4))

        assertEquals("fmt ", ascii(header, 12, 4))
        assertEquals(16, buffer.getInt(16)) // Subchunk1Size for PCM
        assertEquals(1, buffer.getShort(20).toInt()) // AudioFormat = PCM
        assertEquals(config.channelCount, buffer.getShort(22).toInt())
        assertEquals(config.sampleRateHz, buffer.getInt(24))
        assertEquals(config.bytesPerSecond, buffer.getInt(28)) // ByteRate
        assertEquals(config.bytesPerFrame, buffer.getShort(32).toInt()) // BlockAlign
        assertEquals(config.encoding.bitsPerSample, buffer.getShort(34).toInt())

        assertEquals("data", ascii(header, 36, 4))
        val dataChunkSize = buffer.getInt(40)
        assertEquals(payload.size, dataChunkSize)

        // Payload itself is untouched/appended verbatim right after the header.
        assertTrue(bytes.copyOfRange(44, bytes.size).contentEquals(payload))
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)
}
