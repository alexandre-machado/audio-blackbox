package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.GoertzelDetector
import cc.machado.audioblackbox.audio.RingBuffer
import cc.machado.audioblackbox.audio.ToneGenerator
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Content-level coverage of the multi-format retro-export path (issue #322).
 *
 * ## This test passed before the issue #322 fix, on purpose
 * It is not a regression test for that defect -- it is the experiment that *eliminated* the
 * export machinery as its cause, kept because the path it covers had no content-level assertion
 * before. [BoundedExportMultiFormatTest] checks segment counts, byte totals and WAV header fields;
 * none of those can distinguish correct audio from audio that is merely correctly *counted*, which
 * is precisely the blind spot issue #322 lived in.
 *
 * The specific case reproduced here is the one the device owner actually performed: audio recorded
 * at a lower preset, then a switch up to HIGH_FIDELITY, then a Save. So the older half must be
 * upsampled *and* mono-to-stereo converted, the boundary falls mid-window, and the requested
 * duration is shorter than what is buffered, so `dropLeadingDuration` trims into the first
 * segment. Both halves are asserted, not just one: a converter that silently dropped or
 * misaligned the older segment would still produce a plausible file whose newer half sounds fine.
 */
class BoundedExportToneRoundTripTest {

    private val voice = AudioConfig(sampleRateHz = 16_000, channelCount = 1, bufferDurationMinutes = 1)
    private val hiFi = AudioConfig(sampleRateHz = 44_100, channelCount = 2, bufferDurationMinutes = 1)

    @Test
    fun `a trimmed export across a mid-window boundary keeps both halves at their own frequency`() {
        val ring = RingBuffer(capacityBytes = 700_000, initialConfig = voice)
        ring.write(ToneGenerator.tone(LOW_HZ, 16_000, 2_000, channelCount = 1))
        ring.setFormat(hiFi)
        ring.write(ToneGenerator.tone(HIGH_HZ, 44_100, 2_000, channelCount = 2))
        assertEquals("4s of audio must be buffered across the boundary", 4_000L, ring.bufferedDurationMillis())

        val sink = TestInMemorySink()
        val exporter = ExportEngine(
            config = hiFi,
            readSinceProvider = { cursor, maxBytes -> ring.readSince(cursor, maxBytes) },
            writeCursorProvider = { ring.writeCursor() },
            oldestCursorProvider = { ring.oldestCursor() },
            estimateTimestampProvider = { ring.estimateTimestamp(it) },
            gapsProvider = { emptyList() },
            sink = sink,
            payloadEncoder = WavPayloadEncoder,
            segmentsProvider = { ring.activeSegments() },
        )

        // 3s of the 4s buffered, so 1s is trimmed off the front of the *first* segment.
        val result = exporter.export(durationMillis = 3_000L, minutesLabel = 3)
        assertTrue("export should succeed, got $result", result is ExportState.Success)

        val file = sink.writtenBytes ?: error("sink was never committed")
        val header = ByteBuffer.wrap(file, 0, WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.position(WAV_FMT_CHANNELS_OFFSET)
        assertEquals("the file must declare the newest preset's channel count", 2, header.short.toInt())
        assertEquals("the file must declare the newest preset's sample rate", 44_100, header.int)

        val pcm = file.copyOfRange(WAV_HEADER_BYTES, file.size)

        // The trim has to remove exactly the 1s of excess, no more and no less: the frequency
        // assertions below sample fixed offsets, so without this they would still pass if
        // `dropLeadingDuration` trimmed the wrong amount and shifted the boundary. Tolerance is one
        // target frame (the resampler's fractional-phase remainder at the boundary).
        val expectedBytes = 3L * hiFi.bytesPerSecond
        assertTrue(
            "exported payload is ${pcm.size} bytes, expected ~$expectedBytes for the requested 3s " +
                "-- a mis-sized leading trim moves the format boundary within the file",
            abs(pcm.size.toLong() - expectedBytes) <= hiFi.bytesPerFrame,
        )

        val candidates = listOf(LOW_HZ, HIGH_HZ)

        // Sampled just inside each half, clear of the boundary, so a frame of smoothing either
        // side of it cannot decide the result.
        val upsampledHalf = slice(pcm, 50, 950)
        val nativeHalf = slice(pcm, 1_050, 2_950)

        assertEquals(
            "the older, upsampled and mono-to-stereo converted half must still read as ${LOW_HZ}Hz " +
                "(on-target ${GoertzelDetector.energyAt(upsampledHalf, LOW_HZ, 44_100, 2)}, " +
                "off-target ${GoertzelDetector.energyAt(upsampledHalf, HIGH_HZ, 44_100, 2)})",
            LOW_HZ,
            GoertzelDetector.dominantFrequency(upsampledHalf, candidates, 44_100, channelCount = 2),
            0.0,
        )
        assertEquals(
            "the newer, natively-formatted half must still read as ${HIGH_HZ}Hz",
            HIGH_HZ,
            GoertzelDetector.dominantFrequency(nativeHalf, candidates, 44_100, channelCount = 2),
            0.0,
        )
    }

    /** The `[fromMs, toMs)` window of [pcm], snapped down to whole target frames. */
    private fun slice(pcm: ByteArray, fromMs: Int, toMs: Int): ByteArray {
        fun offsetOf(millis: Int): Int {
            val raw = (millis.toLong() * hiFi.bytesPerSecond / 1_000L).toInt()
            return (raw - raw % hiFi.bytesPerFrame).coerceIn(0, pcm.size)
        }
        return pcm.copyOfRange(offsetOf(fromMs), offsetOf(toMs))
    }

    private companion object {
        const val LOW_HZ = 400.0
        const val HIGH_HZ = 1_200.0
        const val WAV_HEADER_BYTES = 44
        const val WAV_FMT_CHANNELS_OFFSET = 22
    }
}
