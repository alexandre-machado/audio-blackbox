package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.FormatSegment
import cc.machado.audioblackbox.audio.RingBuffer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedExportMultiFormatTest {

    private val voiceConfig = AudioConfig(sampleRateHz = 16_000, channelCount = 1) // 32,000 B/s
    private val balancedConfig = AudioConfig(sampleRateHz = 32_000, channelCount = 1) // 64,000 B/s
    private val hiFiConfig = AudioConfig(sampleRateHz = 44_100, channelCount = 2) // 176,400 B/s

    @Test
    fun `BoundedExportPlanner computes accurate output bytes for multi-format segments`() {
        // 1.0s of voice (32,000 bytes) + 1.0s of balanced (64,000 bytes) = 2.0s total audio
        val segments = listOf(
            FormatSegment(startOffset = 0L, config = voiceConfig),
            FormatSegment(startOffset = 32_000L, config = balancedConfig),
        )

        // Target format is balanced (64,000 B/s). Output for 2.0s should be exactly 128,000 bytes
        val plan = BoundedExportPlanner.plan(
            startCursor = 0L,
            rawLength = 96_000L,
            windowStart = 1_000_000L,
            gaps = emptyList(),
            segments = segments,
            targetConfig = balancedConfig,
            targetDurationMillis = 2000L,
        )

        assertEquals(2, plan.segments.size)
        assertEquals(128_000L, plan.totalOutputBytes)
    }

    @Test
    fun `BoundedExportReader converts heterogeneous chunks to target format streaming`() {
        val ringBuffer = RingBuffer(capacityBytes = 200_000, initialConfig = voiceConfig)

        // Write 1.0s of 16kHz mono (samples 100, 200, 300, ...)
        val voiceSamples = ShortArray(16_000) { i -> (i % 5000).toShort() }
        val voiceBytes = pcmBytes(voiceSamples)
        ringBuffer.write(voiceBytes)

        // Switch to 32kHz mono and write 1.0s
        ringBuffer.setFormat(balancedConfig)
        val balancedSamples = ShortArray(32_000) { i -> (i % 5000).toShort() }
        val balancedBytes = pcmBytes(balancedSamples)
        ringBuffer.write(balancedBytes)

        val plan = BoundedExportPlanner.plan(
            startCursor = 0L,
            rawLength = ringBuffer.writeCursor(),
            windowStart = 1_000_000L,
            gaps = emptyList(),
            segments = ringBuffer.activeSegments(),
            targetConfig = balancedConfig,
            targetDurationMillis = 2000L,
        )

        val reader = BoundedExportReader(
            plan = plan,
            readSinceProvider = { cursor, maxBytes -> ringBuffer.readSince(cursor, maxBytes) },
            chunkSizeBytes = 4096,
        )

        val chunks = mutableListOf<ByteArray>()
        while (true) {
            val chunk = reader.nextChunk() ?: break
            chunks += chunk
        }

        val totalDelivered = chunks.sumOf { it.size }
        // Converted total should be close to 2.0s of 32kHz mono (128,000 bytes = 64,000 samples)
        assertEquals("Total delivered bytes should match plan totalOutputBytes", plan.totalOutputBytes, totalDelivered.toLong())
    }

    @Test
    fun `ExportEngine exports heterogeneous buffer to homogeneous WAV file matching newest preset`() {
        val ringBuffer = RingBuffer(capacityBytes = 500_000, initialConfig = voiceConfig)

        // 1. Write 1.0s of voice (16kHz mono)
        ringBuffer.write(pcmBytes(ShortArray(16_000) { 1000 }))

        // 2. Switch to HiFi (44.1kHz stereo) and write 1.0s
        ringBuffer.setFormat(hiFiConfig)
        val stereoFrames = ShortArray(44_100 * 2) { 2000 }
        ringBuffer.write(pcmBytes(stereoFrames))

        val capturedSink = InMemorySink()
        val exportEngine = ExportEngine(
            config = hiFiConfig,
            readSinceProvider = { cursor, maxBytes -> ringBuffer.readSince(cursor, maxBytes) },
            writeCursorProvider = { ringBuffer.writeCursor() },
            oldestCursorProvider = { ringBuffer.oldestCursor() },
            estimateTimestampProvider = { ringBuffer.estimateTimestamp(it) },
            gapsProvider = { emptyList() },
            sink = capturedSink,
            payloadEncoder = WavPayloadEncoder,
            segmentsProvider = { ringBuffer.activeSegments() },
        )

        val result = exportEngine.export(durationMillis = 2000L, minutesLabel = 2)
        assertTrue("Export should succeed, got $result", result is ExportState.Success)

        val exportedBytes = capturedSink.writtenBytes ?: error("Sink was not written")
        assertTrue("WAV header should start with RIFF", exportedBytes.size > 44)

        // Parse WAV header
        val header = ByteBuffer.wrap(exportedBytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { header.get(it) }.toString(Charsets.US_ASCII)
        assertEquals("RIFF", riff)
        header.getInt() // chunkSize
        val wave = ByteArray(4).also { header.get(it) }.toString(Charsets.US_ASCII)
        assertEquals("WAVE", wave)

        // fmt chunk
        header.position(22)
        val channels = header.short.toInt()
        val sampleRate = header.int
        assertEquals("Target channel count should be 2 (Stereo from HiFi)", 2, channels)
        assertEquals("Target sample rate should be 44100 Hz (HiFi)", 44_100, sampleRate)
    }

    private class InMemorySink : ExportSink {
        var writtenBytes: ByteArray? = null
        private val baos = ByteArrayOutputStream()

        override fun open(displayName: String, mimeType: String): ExportTarget {
            return object : ExportTarget {
                override val outputStream = baos
                override fun commit() {
                    writtenBytes = baos.toByteArray()
                }
                override fun abort() {
                    baos.reset()
                }
            }
        }
    }

    private fun pcmBytes(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buffer.putShort(s)
        return bytes
    }
}
