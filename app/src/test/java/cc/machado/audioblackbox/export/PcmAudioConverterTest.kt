package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmAudioConverterTest {

    private val voiceConfig = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
    private val balancedConfig = AudioConfig(sampleRateHz = 32_000, channelCount = 1)
    private val hiFiConfig = AudioConfig(sampleRateHz = 44_100, channelCount = 2)
    private val stereo16kConfig = AudioConfig(sampleRateHz = 16_000, channelCount = 2)

    @Test
    fun `same format conversion returns exact same payload`() {
        val converter = PcmAudioConverter(voiceConfig, voiceConfig)
        val input = byteArrayOf(1, 2, 3, 4, 5, 6)
        val output = converter.convert(input)
        assertArrayEquals(input, output)
    }

    @Test
    fun `mono to stereo duplicates channel samples`() {
        val converter = PcmAudioConverter(voiceConfig, stereo16kConfig)
        val samples = shortArrayOf(100, -200, 300)
        val input = pcmBytes(samples)

        val output = converter.convert(input)
        val outBuffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(samples.size * 2 * 2, output.size)
        for (expected in samples) {
            val left = outBuffer.short
            val right = outBuffer.short
            assertEquals(expected, left)
            assertEquals(expected, right)
        }
    }

    @Test
    fun `stereo to mono averages left and right channels`() {
        val converter = PcmAudioConverter(stereo16kConfig, voiceConfig)
        val stereoFrames = arrayOf(
            Pair(100.toShort(), 200.toShort()),
            Pair((-100).toShort(), (-300).toShort()),
            Pair(1000.toShort(), (-1000).toShort()),
        )
        val inputBytes = ByteArray(stereoFrames.size * 4)
        val inBuffer = ByteBuffer.wrap(inputBytes).order(ByteOrder.LITTLE_ENDIAN)
        for ((l, r) in stereoFrames) {
            inBuffer.putShort(l)
            inBuffer.putShort(r)
        }

        val output = converter.convert(inputBytes)
        val outBuffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(stereoFrames.size * 2, output.size)
        assertEquals(150.toShort(), outBuffer.short)
        assertEquals((-200).toShort(), outBuffer.short)
        assertEquals(0.toShort(), outBuffer.short)
    }

    @Test
    fun `upsampling 16kHz to 32kHz doubles the sample count with linear interpolation`() {
        val converter = PcmAudioConverter(voiceConfig, balancedConfig)
        val samples = shortArrayOf(0, 1000, 2000, 3000)
        val input = pcmBytes(samples)

        val output = converter.convert(input)
        val outBuffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)

        val outSamples = ShortArray(output.size / 2) { outBuffer.short }
        assertTrue("Output sample count should be approximately double input", outSamples.size in 5..8)
        assertEquals(0.toShort(), outSamples[0])
        assertTrue("Interpolated point should be halfway between 0 and 1000", abs(outSamples[1] - 500) <= 2)
        assertTrue("Next sample should be ~1000", abs(outSamples[2] - 1000) <= 2)
    }

    @Test
    fun `downsampling 32kHz to 16kHz halves the sample count`() {
        val converter = PcmAudioConverter(balancedConfig, voiceConfig)
        val samples = shortArrayOf(0, 500, 1000, 1500, 2000, 2500)
        val input = pcmBytes(samples)

        val output = converter.convert(input)
        val outBuffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)

        val outSamples = ShortArray(output.size / 2) { outBuffer.short }
        assertTrue("Output sample count should be half input", outSamples.size in 2..4)
        assertEquals(0.toShort(), outSamples[0])
        assertEquals(1000.toShort(), outSamples[1])
    }

    @Test
    fun `fractional conversion 16kHz mono to 44_1kHz stereo preserves time duration`() {
        val converter = PcmAudioConverter(voiceConfig, hiFiConfig)
        // 16000 samples = exactly 1.0 second of 16kHz mono audio
        val samples = ShortArray(16_000) { i -> (i % 1000).toShort() }
        val input = pcmBytes(samples)

        val output = converter.convert(input)
        val framesOut = output.size / hiFiConfig.bytesPerFrame

        // 1.0 second of 44.1kHz audio has ~44100 frames
        assertTrue("44.1kHz output should have ~44100 frames, got $framesOut", abs(framesOut - 44_100) <= 50)
    }

    @Test
    fun `streaming across multiple chunks produces contiguous output matching chunk-by-chunk`() {
        val totalSamples = 3200 // 200 ms of 16kHz
        val samples = ShortArray(totalSamples) { i -> ((i * 10) % 20000).toShort() }
        val fullInput = pcmBytes(samples)

        val converterChunked = PcmAudioConverter(voiceConfig, hiFiConfig)
        val chunkSize = 640 // 40 ms chunks
        val outChunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < fullInput.size) {
            val len = minOf(chunkSize, fullInput.size - offset)
            val chunk = fullInput.copyOfRange(offset, offset + len)
            outChunks += converterChunked.convert(chunk)
            offset += len
        }
        val combinedOutput = outChunks.reduce { acc, bytes -> acc + bytes }

        val totalFrames = combinedOutput.size / hiFiConfig.bytesPerFrame
        val expectedFrames = (totalSamples * 44_100) / 16_000
        assertTrue("Combined frames $totalFrames should be within 50 of expected $expectedFrames", abs(totalFrames - expectedFrames) <= 50)
    }

    private fun pcmBytes(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buffer.putShort(s)
        return bytes
    }
}
