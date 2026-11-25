package cc.machado.audioblackbox.export

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class OpusResamplerTest {
    // Oracle: byte-chunk boundaries must not reset filter history, phase, or stereo framing.
    @Test fun arbitraryByteBoundariesPreserveSamplesAndChannels() {
        for (rate in listOf(32_000, 44_100)) {
            val input = fixture(rate, 2)
            val whole = convert(rate, 2, input, input.size)
            for (chunk in listOf(1, 3, 127, 4096)) {
                assertArrayEquals("rate=$rate chunk=$chunk", whole, convert(rate, 2, input, chunk))
            }
            assertEquals(48_000 * 4, whole.size)
            // Independently sampled reference tones, excluding endpoint extension transients.
            for (frame in 100 until 47_900) for (channel in 0..1) {
                val expected = (10_000 * sin(2 * Math.PI * (440 + channel * 440) * frame / 48_000)).toInt()
                val index = frame * 4 + channel * 2
                val actual = ((whole[index].toInt() and 255) or (whole[index + 1].toInt() shl 8)).toShort().toInt()
                assertTrue("rate=$rate frame=$frame channel=$channel actual=$actual expected=$expected",
                    abs(actual - expected) <= 8)
            }
        }
    }

    // Oracle: finish emits retained lookahead exactly once, preserving DC and duration.
    @Test fun tailIsDrainedExactlyOnceAndMemoryIsBounded() {
        var size = 0L
        var largestChunk = 0
        val resampler = OpusResampler(32_000, 1) { bytes, count ->
            size += count
            largestChunk = maxOf(largestChunk, count)
            for (i in 0 until count step 2) assertEquals(1234,
                ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toInt())
        }
        val input = ByteArray(64_000) { if (it % 2 == 0) 0xd2.toByte() else 0x04 }
        val buffersBefore = retainedArrays(resampler)
        repeat(100) { resampler.write(input) }
        assertTrue(size < 9_600_000)
        resampler.finish()
        assertEquals(9_600_000L, size)
        resampler.finish()
        assertEquals(9_600_000L, size)
        assertTrue(largestChunk <= 4096)
        // Oracle: processing 100 seconds cannot grow or replace the retained sample buffers.
        val buffersAfter = retainedArrays(resampler)
        assertEquals(buffersBefore.size, buffersAfter.size)
        buffersBefore.zip(buffersAfter).forEach { (before, after) -> assertSame(before, after) }
        assertTrue(buffersAfter.sumOf { java.lang.reflect.Array.getLength(it) } < 8192)
    }

    private fun retainedArrays(resampler: OpusResampler): List<Any> {
        val result = mutableListOf<Any>()
        fun visit(value: Any?) {
            if (value == null || !value.javaClass.isArray) return
            result += value
            if (value is Array<*>) value.forEach { visit(it) }
        }
        OpusResampler::class.java.declaredFields.forEach { field ->
            field.isAccessible = true
            visit(field.get(resampler))
        }
        return result
    }

    private fun fixture(rate: Int, channels: Int): ByteArray = ByteArray(rate * channels * 2).also { bytes ->
        for (frame in 0 until rate) for (channel in 0 until channels) {
            val sample = (10_000 * sin(2 * Math.PI * (440 + channel * 440) * frame / rate)).toInt()
            val index = (frame * channels + channel) * 2
            bytes[index] = sample.toByte()
            bytes[index + 1] = (sample shr 8).toByte()
        }
    }

    private fun convert(rate: Int, channels: Int, input: ByteArray, chunk: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val resampler = OpusResampler(rate, channels) { bytes, count -> output.write(bytes, 0, count) }
        var offset = 0
        while (offset < input.size) {
            val count = minOf(chunk, input.size - offset)
            resampler.write(input, offset, count)
            offset += count
        }
        resampler.finish()
        return output.toByteArray()
    }
}
