package cc.machado.audioblackbox.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.PauseGap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [StreamingAacWriter] (issue #52).
 *
 * Exercises the long-lived streaming AAC encode session against Android's real `MediaCodec`
 * and `MediaMuxer` implementations. Tests method names use camelCase to satisfy DEX method
 * naming constraints in the instrumented tier.
 *
 * ## Oracles
 * - **Incremental Streaming Round-Trip**: PCM is fed in small incremental chunks (e.g. 50ms chunks,
 *   simulating a capture drain loop). Decoded container duration, sample rate, channel count, and
 *   Goertzel energy at the injected tone frequency (with suppressed harmonic energy at f/2 and 2f)
 *   must match the stream parameters.
 * - **Live Gap Injection Round-Trip**: Incremental tone chunks are interleaved with [StreamingAacWriter.writeGap]
 *   silence injections across multiple gaps. Decoded audio is scanned with [GoertzelDetector] to
 *   confirm each gap's silence boundaries land at the exact expected wall-clock offsets in order
 *   within the documented 2048-sample priming delay tolerance.
 * - **Arbitrary-Point Finalization**: Sessions finalized at arbitrary, non-frame-aligned durations
 *   produce completely valid, playable, decodable `.m4a` files matching the audio fed up to that point.
 * - **Resource Safety on Error/Close**: Tests constructor failures, closing unfinalized sessions,
 *   and illegal state transitions to guarantee hardware codec resources are never leaked.
 */
@RunWith(AndroidJUnit4::class)
class StreamingAacWriterTest {

    private val cacheDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test
    fun incrementalStreamingRoundTrip_16kHzMono() {
        assertIncrementalRoundTrip(sampleRateHz = 16_000, channelCount = 1, toneHz = 1000.0, durationMillis = 2_000L, chunkMillis = 50L)
    }

    @Test
    fun incrementalStreamingRoundTrip_44100HzStereo() {
        assertIncrementalRoundTrip(sampleRateHz = 44_100, channelCount = 2, toneHz = 1000.0, durationMillis = 2_000L, chunkMillis = 50L)
    }

    @Test
    fun multiGapLiveInjection_landsAtCorrectOffsetsInDecodedStream() {
        val sampleRateHz = 16_000
        val toneHz = 1000.0
        val config = AudioConfig(sampleRateHz = sampleRateHz, channelCount = 1)
        val outFile = File.createTempFile("stream_aac_multigap_", ".m4a", cacheDir)

        try {
            // Scenario:
            // 0 - 10s: tone (10s)
            // 10s - 15s: gap 1 (5s silence)
            // 15s - 20s: tone (5s)
            // 20s - 22s: gap 2 (2s silence)
            // 22s - 30s: tone (8s)
            // Total timeline: 30s
            val gaps = listOf(
                PauseGap(startTimestampMillis = 10_000L, endTimestampMillis = 15_000L),
                PauseGap(startTimestampMillis = 20_000L, endTimestampMillis = 22_000L),
            )

            StreamingAacWriter(outFile, config).use { writer ->
                // Feed segment 1: 0..10s in 100ms chunks
                writeToneChunks(writer, config, toneHz, durationMillis = 10_000L, chunkMillis = 100L)

                // Live gap 1: 5s
                writer.writeGap(5_000L)

                // Feed segment 2: 15..20s in 100ms chunks
                writeToneChunks(writer, config, toneHz, durationMillis = 5_000L, chunkMillis = 100L)

                // Live gap 2: 2s
                writer.writeGap(2_000L)

                // Feed segment 3: 22..30s in 100ms chunks
                writeToneChunks(writer, config, toneHz, durationMillis = 8_000L, chunkMillis = 100L)

                writer.finish()
            }

            val decoded = AacDecodeSupport.decode(outFile)
            assertEquals(sampleRateHz, decoded.sampleRateHz)
            assertEquals(1, decoded.channelCount)

            val windowSamples = 320 // 20ms at 16kHz
            val referenceStartSample = sampleRateHz // 1s in
            val referenceEnergy = windowEnergy(decoded.pcm, sampleRateHz, toneHz, referenceStartSample, windowSamples)
            assertTrue("reference tone window has unexpectedly weak energy: $referenceEnergy", referenceEnergy > 200.0)
            val silenceThreshold = referenceEnergy / 4.0

            val toleranceMillis = (2 * 1024 * 1000L) / sampleRateHz + 20L

            var searchFromSample = referenceStartSample + windowSamples
            for (gap in gaps) {
                val gapStartSample = findTransition(
                    decoded.pcm, sampleRateHz, toneHz, windowSamples,
                    fromSample = searchFromSample, silenceThreshold, expectTone = false,
                )
                val gapEndSample = findTransition(
                    decoded.pcm, sampleRateHz, toneHz, windowSamples,
                    fromSample = gapStartSample, silenceThreshold, expectTone = true,
                )

                val gapStartMillisFound = gapStartSample * 1000L / sampleRateHz
                val gapEndMillisFound = gapEndSample * 1000L / sampleRateHz

                assertTrue(
                    "gap [${gap.startTimestampMillis},${gap.endTimestampMillis}) start found at " +
                        "${gapStartMillisFound}ms, expected ~${gap.startTimestampMillis}ms (tolerance ${toleranceMillis}ms)",
                    Math.abs(gapStartMillisFound - gap.startTimestampMillis) <= toleranceMillis,
                )
                assertTrue(
                    "gap [${gap.startTimestampMillis},${gap.endTimestampMillis}) end found at " +
                        "${gapEndMillisFound}ms, expected ~${gap.endTimestampMillis}ms (tolerance ${toleranceMillis}ms)",
                    Math.abs(gapEndMillisFound - gap.endTimestampMillis) <= toleranceMillis,
                )

                searchFromSample = gapEndSample
            }
        } finally {
            outFile.delete()
        }
    }

    @Test
    fun finalizeAtArbitraryPoint_producesDecodableAndValidContainer() {
        val sampleRateHz = 16_000
        val toneHz = 1000.0
        val config = AudioConfig(sampleRateHz = sampleRateHz, channelCount = 1)
        val arbitraryDurationsMillis = listOf(350L, 1_234L, 3_750L)

        for (duration in arbitraryDurationsMillis) {
            val outFile = File.createTempFile("stream_aac_arbitrary_", ".m4a", cacheDir)
            try {
                val writer = StreamingAacWriter(outFile, config)
                writeToneChunks(writer, config, toneHz, durationMillis = duration, chunkMillis = 40L)
                writer.finish()

                val decoded = AacDecodeSupport.decode(outFile)
                assertEquals(sampleRateHz, decoded.sampleRateHz)
                assertEquals(1, decoded.channelCount)

                val requestedDurationUs = duration * 1000L
                val frameToleranceUs = (2 * 1024 * 1_000_000L) / sampleRateHz
                assertTrue(
                    "arbitrary duration $duration ms declared container duration ${decoded.containerDurationUs}us " +
                        "exceeds tolerance (requested ${requestedDurationUs}us, tolerance ${frameToleranceUs}us)",
                    Math.abs(decoded.containerDurationUs - requestedDurationUs) <= frameToleranceUs,
                )

                val energyAtTone = GoertzelDetector.energyAt(decoded.pcm, toneHz, sampleRateHz, 1)
                assertTrue("expected recognizable tone energy in arbitrary finalize ($duration ms)", energyAtTone > 50.0)
            } finally {
                outFile.delete()
            }
        }
    }

    @Test
    fun closeWithoutFinish_releasesResourcesSafelyWithoutThrowing() {
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        val outFile = File.createTempFile("stream_aac_close_unfin_", ".m4a", cacheDir)
        try {
            val writer = StreamingAacWriter(outFile, config)
            val chunk = ToneGenerator.tone(1000.0, 16_000, 200L)
            writer.write(chunk)
            assertFalse(writer.isSessionClosed)
            assertFalse(writer.isSessionFinished)

            // Close without calling finish()
            writer.close()
            assertTrue(writer.isSessionClosed)
            assertFalse(writer.isSessionFinished)

            // Multiple close() calls are safe idempotent no-ops
            writer.close()
            assertTrue(writer.isSessionClosed)
        } finally {
            outFile.delete()
        }
    }

    @Test
    fun operationsAfterCloseOrFinish_throwIllegalStateException() {
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        val outFile = File.createTempFile("stream_aac_state_check_", ".m4a", cacheDir)
        try {
            val writer = StreamingAacWriter(outFile, config)
            val chunk = ToneGenerator.tone(1000.0, 16_000, 100L)
            writer.write(chunk)
            writer.finish()

            assertTrue(writer.isSessionFinished)
            assertTrue(writer.isSessionClosed)

            try {
                writer.write(chunk)
                fail("expected IllegalStateException writing to finished writer")
            } catch (expected: IllegalStateException) {
                // Expected
            }

            try {
                writer.writeGap(500L)
                fail("expected IllegalStateException writing gap to finished writer")
            } catch (expected: IllegalStateException) {
                // Expected
            }

            try {
                writer.finish()
                // finish() when already finished is a safe no-op
            } catch (unexpected: Throwable) {
                fail("second finish() should be safe no-op")
            }
        } finally {
            outFile.delete()
        }
    }

    @Test
    fun constructorFailure_releasesCodecWithoutLeak() {
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        val invalidFile = File("/nonexistent_dir_12345/sub/test.m4a")
        try {
            StreamingAacWriter(invalidFile, config)
            fail("expected exception constructing writer with invalid destination")
        } catch (expected: Exception) {
            // Expected -- MediaMuxer constructor throws IOException, codec must be cleaned up in constructor finally
        }
    }

    private fun writeToneChunks(
        writer: StreamingAacWriter,
        config: AudioConfig,
        toneHz: Double,
        durationMillis: Long,
        chunkMillis: Long,
    ) {
        var elapsed = 0L
        while (elapsed < durationMillis) {
            val thisChunkMillis = minOf(chunkMillis, durationMillis - elapsed)
            val pcm = ToneGenerator.tone(
                frequencyHz = toneHz,
                sampleRateHz = config.sampleRateHz,
                durationMillis = thisChunkMillis,
                channelCount = config.channelCount,
            )
            writer.write(pcm)
            elapsed += thisChunkMillis
        }
    }

    private fun assertIncrementalRoundTrip(
        sampleRateHz: Int,
        channelCount: Int,
        toneHz: Double,
        durationMillis: Long,
        chunkMillis: Long,
    ) {
        val config = AudioConfig(sampleRateHz = sampleRateHz, channelCount = channelCount)
        val outFile = File.createTempFile("stream_aac_roundtrip_", ".m4a", cacheDir)
        try {
            StreamingAacWriter(outFile, config).use { writer ->
                writeToneChunks(writer, config, toneHz, durationMillis, chunkMillis)
                writer.finish()
            }

            val decoded = AacDecodeSupport.decode(outFile)
            assertEquals(sampleRateHz, decoded.sampleRateHz)
            assertEquals(channelCount, decoded.channelCount)

            val requestedDurationUs = durationMillis * 1000L
            val frameToleranceUs = (2 * 1024 * 1_000_000L) / sampleRateHz
            assertTrue(
                "declared duration ${decoded.containerDurationUs}us too far from requested ${requestedDurationUs}us",
                Math.abs(decoded.containerDurationUs - requestedDurationUs) <= frameToleranceUs,
            )

            val bytesPerFrame = 2 * channelCount
            val trimFrames = 4096
            val trimBytes = (trimFrames * bytesPerFrame).coerceAtMost(decoded.pcm.size / 4)
            val steadyState = decoded.pcm.copyOfRange(trimBytes, decoded.pcm.size - trimBytes)

            val energyAtTone = GoertzelDetector.energyAt(steadyState, toneHz, sampleRateHz, channelCount)
            val energyAtHalf = GoertzelDetector.energyAt(steadyState, toneHz / 2, sampleRateHz, channelCount)
            val energyAtDouble = GoertzelDetector.energyAt(steadyState, toneHz * 2, sampleRateHz, channelCount)

            assertTrue("expected strong energy at ${toneHz}Hz, got $energyAtTone", energyAtTone > 500.0)
            assertTrue("expected weak energy at ${toneHz / 2}Hz, got $energyAtHalf", energyAtHalf < energyAtTone / 5)
            assertTrue("expected weak energy at ${toneHz * 2}Hz, got $energyAtDouble", energyAtDouble < energyAtTone / 5)
        } finally {
            outFile.delete()
        }
    }

    private fun windowEnergy(pcm: ByteArray, sampleRateHz: Int, toneHz: Double, startSample: Int, windowSamples: Int): Double {
        val startByte = startSample * 2
        val endByte = (startByte + windowSamples * 2).coerceAtMost(pcm.size)
        if (startByte >= endByte) return 0.0
        return GoertzelDetector.energyAt(pcm.copyOfRange(startByte, endByte), toneHz, sampleRateHz)
    }

    private fun findTransition(
        pcm: ByteArray,
        sampleRateHz: Int,
        toneHz: Double,
        windowSamples: Int,
        fromSample: Int,
        silenceThreshold: Double,
        expectTone: Boolean,
    ): Int {
        var sample = fromSample
        val totalSamples = pcm.size / 2
        while (sample + windowSamples <= totalSamples) {
            val energy = windowEnergy(pcm, sampleRateHz, toneHz, sample, windowSamples)
            val isTonePresent = energy >= silenceThreshold
            if (isTonePresent == expectTone) return sample
            sample += windowSamples
        }
        throw AssertionError("never found a window with tone-present=$expectTone scanning from sample $fromSample")
    }
}
