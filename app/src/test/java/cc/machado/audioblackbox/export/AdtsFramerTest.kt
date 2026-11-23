package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.QualityPreset
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class AdtsFramerTest {
    /** Oracle: parse actual production bytes, independently checking the ADTS bit fields,
     * exact payload preservation, and every complete-frame prefix before finalization.
     * The payloads stand in for codec outputs; only Tier 1 can assert AAC decodability.
     */
    @Test fun everyPresetWritesCompleteReadableFrames() {
        val expectedIndices = mapOf(
            QualityPreset.VOICE to 8, QualityPreset.BALANCED to 5, QualityPreset.HIGH_FIDELITY to 4,
        )
        for (preset in QualityPreset.entries) {
            val framer = AdtsFramer(preset.sampleRateHz, preset.channelCount)
            val output = ByteArrayOutputStream()
            val payloads = listOf(17, 249, 1024, 8184).map { size ->
                ByteArray(size) { ((it * 31 + size) % 256).toByte() }
            }
            val boundaries = mutableListOf<Int>()
            for (payload in payloads) {
                // Nonzero buffer offset and a limited view detect accidental CSD/padding copies.
                val buffer = ByteBuffer.wrap(byteArrayOf(11, 22) + payload + byteArrayOf(33))
                buffer.position(2)
                buffer.limit(2 + payload.size)
                framer.write(output, buffer)
                boundaries += output.size()
            }
            val bytes = output.toByteArray()
            for ((lastFrame, boundary) in boundaries.withIndex()) {
                val prefix = bytes.copyOf(boundary)
                val decodedPayloads = parse(prefix, expectedIndices.getValue(preset), preset.channelCount)
                assertEquals(lastFrame + 1, decodedPayloads.size)
                for (i in decodedPayloads.indices) {
                    assertArrayEquals("frame payload $i for $preset", payloads[i], decodedPayloads[i])
                }
                assertArrayEquals(
                    payloads.take(lastFrame + 1).fold(byteArrayOf()) { a, b -> a + b },
                    decodedPayloads.fold(byteArrayOf()) { a, b -> a + b },
                )
            }
        }
    }

    private fun parse(bytes: ByteArray, frequencyIndex: Int, channels: Int): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        fun b(i: Int) = bytes[offset + i].toInt() and 255
        while (offset < bytes.size) {
            assertTrue("complete ADTS header", bytes.size - offset >= 7)
            assertEquals("syncword", 0xff, b(0))
            assertEquals("MPEG-4, layer zero, no CRC", 0xf1, b(1))
            assertEquals("AAC-LC profile", 1, b(2) ushr 6)
            assertEquals("sampling_frequency_index", frequencyIndex, (b(2) ushr 2) and 15)
            assertEquals("channel_configuration", channels, ((b(2) and 1) shl 2) or (b(3) ushr 6))
            val length = ((b(3) and 3) shl 11) or (b(4) shl 3) or (b(5) ushr 5)
            assertEquals("frame_length", listOf(24, 256, 1031, 8191)[frames.size].toInt(), length)
            assertTrue("frame_length includes header and payload", length > 7)
            assertTrue("frame_length stays in complete prefix", offset + length <= bytes.size)
            assertEquals("one raw data block", 0, b(6) and 3)
            frames += bytes.copyOfRange(offset + 7, offset + length)
            offset += length
        }
        assertEquals("ends at a frame boundary", bytes.size, offset)
        return frames
    }

    /** Oracle: unsupported rates fail before any incorrect frequency index can be written. */
    @Test(expected = IllegalArgumentException::class)
    fun unsupportedSampleRateFails() {
        AdtsFramer(12345, 1)
    }
}
