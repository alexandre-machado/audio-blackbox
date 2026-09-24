package cc.machado.audioblackbox.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #378 on the real muxer (`@rev` PR #415 finding 1): the production failure *artifact*,
 * built with a bare `MediaMuxer`, not through `StreamingAacWriter`.
 *
 * Real AAC samples and a real `csd-0` come from the platform encoder. They are written to a bare
 * muxer in order, except that the final sample's timestamp goes backwards. On AOSP's
 * `MPEG4Writer` that marks the track malformed, and `stop()` then throws "muxer would have
 * stopped already" after writing a moov with an empty sample table: the shape the S25 produced.
 *
 * ## Oracles
 * 1. `stop()` throws on that sequence. If this emulator's muxer does not, the test fails loudly on
 *    that precondition instead of passing without having built the artifact.
 * 2. [Mp4OutputProbe] + [MuxerStopFailurePolicy] reject that exact file.
 * 3. The same samples with every timestamp routed through [MuxerTimestampSanitizer] (plus the
 *    end-of-stream marker the writer sends) stop cleanly and are accepted.
 *
 * The probe result for the broken file is logged under [TAG] so CI output shows which rejection
 * the extractor produced (no track at all, or a track with no readable sample).
 */
@RunWith(AndroidJUnit4::class)
class BareMuxerStopFailureTest {

    private val cacheDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    private class Encoded(val format: MediaFormat, val samples: List<Pair<ByteArray, Long>>)

    @Test
    fun bareMuxer_finalSamplePtsRegression_stopThrows_probeRejects_sanitizedSequenceIsAccepted() {
        val sampleRateHz = 44_100
        val channelCount = 2
        val encoded = encodeTone(sampleRateHz, channelCount, durationMillis = 1_000L)
        assertTrue("csd-0 must be present on the encoder's output format", encoded.format.containsKey("csd-0"))
        val samples = encoded.samples
        assertTrue("need enough frames to regress over, got ${samples.size}", samples.size >= 20)

        val lastIndex = samples.lastIndex
        val regressedPtsUs = samples[lastIndex - 5].second
        assertTrue(
            "precondition: the regressed pts must be behind the previous sample",
            regressedPtsUs < samples[lastIndex - 1].second,
        )
        val stopError = IllegalStateException("placeholder for the policy's cause")

        val broken = File.createTempFile("bare_muxer_broken_", ".m4a", cacheDir)
        val sanitized = File.createTempFile("bare_muxer_sanitized_", ".m4a", cacheDir)
        try {
            // --- The production shape: final sample's pts goes backwards. ---
            val brokenMuxer = MediaMuxer(broken.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var stopFailure: IllegalStateException? = null
            try {
                val track = brokenMuxer.addTrack(encoded.format)
                brokenMuxer.start()
                for (i in 0 until lastIndex) write(brokenMuxer, track, samples[i].first, samples[i].second)
                try {
                    write(brokenMuxer, track, samples[lastIndex].first, regressedPtsUs)
                } catch (e: IllegalStateException) {
                    fail("precondition: the regressing write itself threw ($e); this is not the #378 shape, where only stop() fails")
                }
                try {
                    brokenMuxer.stop()
                } catch (e: IllegalStateException) {
                    stopFailure = e
                }
            } finally {
                brokenMuxer.release()
            }
            assertNotNull(
                "precondition: this emulator's MediaMuxer did not throw from stop() after a regressing final pts, " +
                    "so the #378 artifact could not be built here",
                stopFailure,
            )

            val brokenProbe = Mp4OutputProbe.probe(outputFile = broken, fileDescriptor = null)
            Log.i(TAG, "probe(broken, ${broken.length()} bytes) = $brokenProbe")
            val frameUs = MuxerTimestampSanitizer(sampleRateHz).frameDurationUs
            val brokenVerdict = MuxerStopFailurePolicy.resolve(
                stopError,
                brokenProbe,
                writtenSpanUs = samples[lastIndex - 1].second - samples[0].second,
                frameDurationUs = frameUs,
                diagnostics = "",
            )
            assertNotNull("the malformed-track file must be rejected, probe said $brokenProbe", brokenVerdict)

            // --- Same samples, timestamps through the sanitizer, EOS marker as the writer sends it. ---
            val sanitizer = MuxerTimestampSanitizer(sampleRateHz)
            val cleanMuxer = MediaMuxer(sanitized.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val track = cleanMuxer.addTrack(encoded.format)
                cleanMuxer.start()
                for (i in 0 until lastIndex) write(cleanMuxer, track, samples[i].first, sanitizer.next(samples[i].second))
                write(cleanMuxer, track, samples[lastIndex].first, sanitizer.next(regressedPtsUs, isEndOfStream = true))
                val marker = MediaCodec.BufferInfo()
                marker.set(0, 0, sanitizer.endOfStreamMarkerPtsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                cleanMuxer.writeSampleData(track, ByteBuffer.allocate(0), marker)
                cleanMuxer.stop() // must not throw
            } finally {
                cleanMuxer.release()
            }
            assertTrue("the regressed sample must be rewritten", sanitizer.corrections >= 1)

            val cleanProbe = Mp4OutputProbe.probe(outputFile = sanitized, fileDescriptor = null)
            Log.i(TAG, "probe(sanitized) = $cleanProbe")
            assertNull(
                "the sanitized file must be accepted, probe said $cleanProbe",
                MuxerStopFailurePolicy.resolve(
                    stopError,
                    cleanProbe,
                    writtenSpanUs = sanitizer.lastPtsUs - sanitizer.firstPtsUs,
                    frameDurationUs = frameUs,
                    diagnostics = "",
                ),
            )
        } finally {
            broken.delete()
            sanitized.delete()
        }
    }

    private fun write(muxer: MediaMuxer, track: Int, data: ByteArray, ptsUs: Long) {
        val info = MediaCodec.BufferInfo()
        info.set(0, data.size, ptsUs, 0)
        muxer.writeSampleData(track, ByteBuffer.wrap(data), info)
    }

    /** Encodes a tone with the platform AAC encoder; returns its output format and data samples. */
    private fun encodeTone(sampleRateHz: Int, channelCount: Int, durationMillis: Long): Encoded {
        val pcm = ToneGenerator.tone(1000.0, sampleRateHz, durationMillis, channelCount)
        val bytesPerSecond = sampleRateHz * channelCount * 2L
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRateHz, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000 * channelCount)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val samples = mutableListOf<Pair<ByteArray, Long>>()
        var outputFormat: MediaFormat? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var fed = 0
            var inputDone = false
            var outputDone = false
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!outputDone) {
                check(System.nanoTime() < deadline) { "encoder did not finish within 30 s" }
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val buf = requireNotNull(codec.getInputBuffer(inIndex))
                        buf.clear()
                        val n = minOf(buf.remaining(), pcm.size - fed)
                        buf.put(pcm, fed, n)
                        val pts = fed * 1_000_000L / bytesPerSecond
                        fed += n
                        val eos = fed >= pcm.size
                        codec.queueInputBuffer(inIndex, 0, n, pts, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        inputDone = eos
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    outIndex >= 0 -> {
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val out = requireNotNull(codec.getOutputBuffer(outIndex))
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            out.get(bytes)
                            samples += bytes to info.presentationTimeUs
                        }
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                }
            }
            codec.stop()
        } finally {
            codec.release()
        }
        return Encoded(requireNotNull(outputFormat) { "encoder never reported an output format" }, samples)
    }

    private companion object {
        const val TAG = "BareMuxerStopFailure"
    }
}
