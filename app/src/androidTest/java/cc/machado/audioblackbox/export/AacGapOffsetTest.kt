package cc.machado.audioblackbox.export

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.AudioSnapshot
import cc.machado.audioblackbox.audio.PauseGap
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves [GapFiller]'s silence placement survives the AAC encode/decode round-trip at the correct
 * offset (issue #32 mandatory test) -- the test that would catch an uncompensated encoder priming
 * delay silently shifting the whole timeline, per issue #32's explicit warning that this is the
 * highest-risk part of the change.
 *
 * Builds gap-filled PCM exactly the way [ExportEngine] does (via the real [GapFiller], not a
 * hand-rolled substitute), encodes it with [AacPayloadEncoder], decodes it back with
 * [AacDecodeSupport], then scans the decoded audio with [GoertzelDetector] to find where the tone
 * actually drops out and resumes -- rather than trusting byte offsets, which lossy encoding does
 * not preserve.
 */
@RunWith(AndroidJUnit4::class)
class AacGapOffsetTest {

    private val cacheDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test
    fun gapFilledExport_hasSilenceAtTheCorrectOffsetAfterEncodeAndDecode() {
        val sampleRateHz = 16_000
        val toneHz = 1000.0
        val config = AudioConfig(sampleRateHz = sampleRateHz, channelCount = 1)

        // 6s requested window; a 1s interruption happened 2s in -- 5s of real audio was actually
        // captured (matches GapFillerTest's own scenario shape).
        val requestedMillis = 6_000L
        val gapStartMillis = 2_000L
        val gapEndMillis = 3_000L

        val rawAudioMillis = requestedMillis - (gapEndMillis - gapStartMillis)
        val rawTone = ToneGenerator.tone(toneHz, sampleRateHz, rawAudioMillis)
        val snapshot = AudioSnapshot(rawTone, startTimestampMillis = 0L)
        val gaps = listOf(PauseGap(gapStartMillis, gapEndMillis))

        val gapFilledPcm = GapFiller.fill(snapshot, gaps, config, requestedMillis)

        val outFile = File.createTempFile("aac_gap_offset_", ".m4a", cacheDir)
        try {
            FileOutputStream(outFile).use { out ->
                AacPayloadEncoder(cacheDir).encode(config, gapFilledPcm, out, isCancelled = { false })
            }

            val decoded = AacDecodeSupport.decode(outFile)

            // Reference tone energy from a window guaranteed to be real audio (well before the
            // gap, and well past the start of the file) -- calibrates "what does tone-present
            // energy look like after lossy encoding" instead of hardcoding a magic threshold.
            // Deliberately not sampled right at the start of the decoded stream: doing so
            // originally measured 0.0 energy here, empirically confirming AacPayloadEncoder's
            // class doc -- the AAC-LC encoder's one-frame look-ahead does show up as content-free
            // samples at the very front of the decoded output, not merely a metadata/PTS artifact.
            val windowSamples = 320 // 20ms at 16kHz, same granularity CaptureContinuesDuringSnapshotTest uses
            val referenceStartSample = sampleRateHz // 1s in -- clear of any start-of-file artifact, still before the 2s gap
            val referenceEnergy = windowEnergy(decoded.pcm, sampleRateHz, toneHz, referenceStartSample, windowSamples)
            assertTrue("reference tone window has unexpectedly weak energy: $referenceEnergy", referenceEnergy > 200.0)
            val silenceThreshold = referenceEnergy / 4.0

            // Scans forward from the reference window, not from sample 0: the leading samples are
            // where the encoder's one-frame look-ahead artifact lives (see the reference-window
            // comment above), so starting the scan there would immediately -- and wrongly --
            // report the gap as starting at the very front of the file instead of at ~2s in.
            val gapStartSample = findTransition(
                decoded.pcm, sampleRateHz, toneHz, windowSamples,
                fromSample = referenceStartSample + windowSamples, silenceThreshold, expectTone = false,
            )
            val gapEndSample = findTransition(
                decoded.pcm, sampleRateHz, toneHz, windowSamples,
                fromSample = gapStartSample, silenceThreshold, expectTone = true,
            )

            val gapStartMillisFound = gapStartSample * 1000L / sampleRateHz
            val gapEndMillisFound = gapEndSample * 1000L / sampleRateHz

            // Tolerance: one AAC frame (1024 samples, ~64ms at 16kHz) doubled for the scan's own
            // 20ms window granularity, documented rather than loosened until green -- see
            // AacPayloadEncoder's class doc for the measured priming-delay finding this bounds.
            val toleranceMillis = (2 * 1024 * 1000L) / sampleRateHz + 20L

            assertTrue(
                "gap start found at ${gapStartMillisFound}ms, expected ~${gapStartMillis}ms " +
                    "(tolerance ${toleranceMillis}ms)",
                Math.abs(gapStartMillisFound - gapStartMillis) <= toleranceMillis,
            )
            assertTrue(
                "gap end found at ${gapEndMillisFound}ms, expected ~${gapEndMillis}ms " +
                    "(tolerance ${toleranceMillis}ms)",
                Math.abs(gapEndMillisFound - gapEndMillis) <= toleranceMillis,
            )
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

    /** Scans forward in [windowSamples]-sized steps from [fromSample] for the first window whose
     * energy crosses [silenceThreshold] in the direction [expectTone] asks for, returning that
     * window's start sample -- i.e. the boundary a real decode would show, not a byte offset
     * carried over from before encoding (which lossy encoding does not preserve). */
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
        throw AssertionError(
            "never found a window with tone-present=$expectTone scanning from sample $fromSample",
        )
    }
}
