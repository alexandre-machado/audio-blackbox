package cc.machado.audioblackbox.export

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.machado.audioblackbox.audio.AudioConfig
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AAC round-trip with a known signal (issue #32 mandatory test, carrying over `WavRoundTripTest`'s
 * approach to the new format): generates a known tone, encodes it with the production
 * [AacPayloadEncoder], decodes the written `.m4a` back with [AacDecodeSupport] (real
 * `MediaExtractor`/`MediaCodec`, exactly what a player uses), and confirms with [GoertzelDetector]
 * that it is still that tone at the *container's declared* sample rate/channel count -- not the
 * [AudioConfig] used to encode it, the same distinction `WavRoundTripTest` draws for the WAV
 * header. `MediaCodec`/`MediaMuxer` cannot be meaningfully exercised on the JVM tier, which is why
 * this lives here instead (`docs/testing/tiers.md`).
 *
 * Test method names are `camelCase`, not this repo's usual backtick-quoted sentence style: this
 * source set gets DEX'd for the instrumented APK, and a space in a method name is rejected before
 * DEX version 040 (confirmed empirically -- `dexBuilderDebugAndroidTest` fails the whole tier with
 * exactly that error if a spaced name sneaks in here).
 */
@RunWith(AndroidJUnit4::class)
class AacRoundTripTest {

    private val cacheDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test
    fun tone1kHzAt16kHzMono_roundTripsThroughAacPayloadEncoder() {
        assertRoundTrip(sampleRateHz = 16_000, channelCount = 1, toneHz = 1000.0, durationMillis = 2_000)
    }

    @Test
    fun tone1kHzAt44100HzStereo_roundTripsThroughAacPayloadEncoder() {
        assertRoundTrip(sampleRateHz = 44_100, channelCount = 2, toneHz = 1000.0, durationMillis = 2_000)
    }

    private fun assertRoundTrip(sampleRateHz: Int, channelCount: Int, toneHz: Double, durationMillis: Long) {
        val config = AudioConfig(sampleRateHz = sampleRateHz, channelCount = channelCount)
        val originalPcm = ToneGenerator.tone(
            frequencyHz = toneHz,
            sampleRateHz = sampleRateHz,
            durationMillis = durationMillis,
            channelCount = channelCount,
        )

        val outFile = File.createTempFile("aac_roundtrip_", ".m4a", cacheDir)
        try {
            FileOutputStream(outFile).use { out ->
                AacPayloadEncoder(cacheDir).encode(config, originalPcm, out, isCancelled = { false })
            }

            val decoded = AacDecodeSupport.decode(outFile)

            // Container metadata (issue #32 acceptance criterion): sample rate and channel count
            // must match capture config exactly -- a real player trusts these declared fields, not
            // whatever config produced the file.
            assertEquals(sampleRateHz, decoded.sampleRateHz)
            assertEquals(channelCount, decoded.channelCount)

            // Declared duration must be close to the true input duration. Not exact: AAC-LC
            // encodes in fixed 1024-sample frames and has a one-frame encoder look-ahead (see
            // AacPayloadEncoder's class doc on the measured priming delay) -- a real player would
            // see the same rounding. One frame's worth of tolerance (1024 samples), doubled for
            // margin, is the documented bound, not a loosened-until-it-passes number.
            val requestedDurationUs = durationMillis * 1000L
            val frameToleranceUs = (2 * 1024 * 1_000_000L) / sampleRateHz
            assertTrue(
                "declared duration ${decoded.containerDurationUs}us too far from requested " +
                    "${requestedDurationUs}us (tolerance ${frameToleranceUs}us)",
                Math.abs(decoded.containerDurationUs - requestedDurationUs) <= frameToleranceUs,
            )

            // Dominant-frequency check on a window trimmed away from both ends, exactly like
            // WavRoundTripTest's rationale but adapted for lossy encoding: the very first/last
            // AAC frame can carry priming/flush artifacts (see AacPayloadEncoder's class doc), so
            // asserting on the steady-state middle of the signal is what proves the *tone itself*
            // survived encoding, without that assertion being sensitive to the exact number of
            // priming samples (which AacGapOffsetTest measures/bounds separately).
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

            measureAndBoundLeadingPrimingSamples(decoded.pcm, sampleRateHz, channelCount, toneHz, energyAtTone)
        } finally {
            outFile.delete()
        }
    }

    /**
     * Measures how many leading samples of the decoded stream are *not* yet recognizable as the
     * injected tone -- i.e. the AAC-LC encoder's priming/look-ahead artifact AacPayloadEncoder's
     * class doc describes -- rather than assuming a textbook "1024 samples" figure. Scans forward
     * in small windows until a window's tone energy first reaches half of [steadyStateEnergy]
     * (the true mid-signal reference computed by the caller), logs the measured value (visible via
     * `adb logcat -s AacRoundTripTest`, and via this run's own logs) for documentation, and asserts
     * it stays within one frame's tolerance doubled (2048 samples) -- the same bound
     * [AacGapOffsetTest] relies on for gap-offset accuracy, so a regression that suddenly widens
     * the priming delay fails here first, at its most direct measurement, not only as a confusing
     * gap-offset mismatch elsewhere.
     */
    private fun measureAndBoundLeadingPrimingSamples(
        pcm: ByteArray,
        sampleRateHz: Int,
        channelCount: Int,
        toneHz: Double,
        steadyStateEnergy: Double,
    ) {
        val windowSamples = 64
        val bytesPerFrame = 2 * channelCount
        val totalSamples = pcm.size / bytesPerFrame
        var primingSamples = totalSamples // default if the tone is never found (would already fail the assertion above)
        var sample = 0
        while (sample + windowSamples <= totalSamples) {
            val startByte = sample * bytesPerFrame
            val endByte = (sample + windowSamples) * bytesPerFrame
            val energy = GoertzelDetector.energyAt(pcm.copyOfRange(startByte, endByte), toneHz, sampleRateHz, channelCount)
            if (energy >= steadyStateEnergy / 2.0) {
                primingSamples = sample
                break
            }
            sample += windowSamples
        }
        Log.i(
            "AacRoundTripTest",
            "measured leading priming samples at ${sampleRateHz}Hz/${channelCount}ch: $primingSamples " +
                "(${(primingSamples * 1000L) / sampleRateHz}ms)",
        )
        assertTrue(
            "measured priming delay ($primingSamples samples) exceeds the documented 2048-sample bound",
            primingSamples <= 2048,
        )
    }
}
