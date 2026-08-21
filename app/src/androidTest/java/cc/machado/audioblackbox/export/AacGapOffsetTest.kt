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
 *
 * [twoGapsBothLandAtTheCorrectOffset] exists specifically because a single-gap test cannot catch
 * PR #28's critical defect class (audio mis-spliced only after the *second* interruption, with
 * total length staying correct) -- `@techlead` adjudication on PR #37, finding 2. It reuses
 * [assertGapOffsets]'s shared machinery so both cases are checked against the exact same tolerance
 * derivation, not a loosened one for the harder case.
 */
@RunWith(AndroidJUnit4::class)
class AacGapOffsetTest {

    private val cacheDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    private val sampleRateHz = 16_000
    private val toneHz = 1000.0
    private val config = AudioConfig(sampleRateHz = sampleRateHz, channelCount = 1)

    @Test
    fun singleGap_hasSilenceAtTheCorrectOffsetAfterEncodeAndDecode() {
        // 6s requested window; a 1s interruption happened 2s in -- 5s of real audio was actually
        // captured (matches GapFillerTest's own scenario shape).
        assertGapOffsets(
            requestedMillis = 6_000L,
            rawAudioMillis = 5_000L,
            gaps = listOf(PauseGap(2_000L, 3_000L)),
        )
    }

    @Test
    fun twoGapsBothLandAtTheCorrectOffset() {
        // Same scenario shape as GapFillerTest's own two-gap PCM-level test (0-23s raw audio, a
        // 30s requested window, gap1 [10s,15s) 5s + gap2 [20s,22s) 2s) -- carrying that
        // already-proven PCM-level case through the AAC encode/decode round-trip is exactly what
        // this test adds over the single-gap case: it can catch a mis-splice that only appears
        // after the second interruption while total length stays correct, which a single gap
        // structurally cannot (`@techlead` adjudication on PR #37, finding 2).
        assertGapOffsets(
            requestedMillis = 30_000L,
            rawAudioMillis = 23_000L,
            gaps = listOf(
                PauseGap(startTimestampMillis = 10_000L, endTimestampMillis = 15_000L),
                PauseGap(startTimestampMillis = 20_000L, endTimestampMillis = 22_000L),
            ),
        )
    }

    private fun assertGapOffsets(requestedMillis: Long, rawAudioMillis: Long, gaps: List<PauseGap>) {
        val rawTone = ToneGenerator.tone(toneHz, sampleRateHz, rawAudioMillis)
        val snapshot = AudioSnapshot(rawTone, startTimestampMillis = 0L)

        val gapFilledPcm = GapFiller.fill(snapshot, gaps, config, requestedMillis)

        val outFile = File.createTempFile("aac_gap_offset_", ".m4a", cacheDir)
        try {
            FileOutputStream(outFile).use { out ->
                AacPayloadEncoder(cacheDir).encode(config, gapFilledPcm, out, isCancelled = { false })
            }

            val decoded = AacDecodeSupport.decode(outFile)

            // Reference tone energy from a window guaranteed to be real audio (well before the
            // first gap, and well past the start of the file) -- calibrates "what does
            // tone-present energy look like after lossy encoding" instead of hardcoding a magic
            // threshold. Deliberately not sampled right at the start of the decoded stream: doing
            // so originally measured 0.0 energy here, empirically confirming AacPayloadEncoder's
            // class doc -- the AAC-LC encoder's priming delay does show up as content-free samples
            // at the very front of the decoded output, not merely a metadata/PTS artifact.
            val windowSamples = 320 // 20ms at 16kHz, same granularity CaptureContinuesDuringSnapshotTest uses
            val referenceStartSample = sampleRateHz // 1s in -- clear of any start-of-file artifact
            check(gaps.first().startTimestampMillis > 1_000L) {
                "test setup invariant: reference window must sit before every gap"
            }
            val referenceEnergy = windowEnergy(decoded.pcm, sampleRateHz, toneHz, referenceStartSample, windowSamples)
            assertTrue("reference tone window has unexpectedly weak energy: $referenceEnergy", referenceEnergy > 200.0)
            val silenceThreshold = referenceEnergy / 4.0

            // Tolerance: the measured 2048-sample encoder priming delay (see AacPayloadEncoder's
            // class doc), doubled headroom folded in already, plus the scan's own 20ms window
            // granularity -- documented and fixed, not loosened per gap. `@techlead` adjudication
            // on PR #37 explicitly rules this fixed (not position-proportional) tolerance
            // acceptable as-is and asked that a second gap needing a *wider* bound be reported
            // rather than accommodated by loosening it -- see the class doc.
            val toleranceMillis = (2 * 1024 * 1000L) / sampleRateHz + 20L

            // Scans forward from the reference window, not from sample 0: the leading samples are
            // where the encoder's priming-delay artifact lives (see the reference-window comment
            // above), so starting the scan there would immediately -- and wrongly -- report the
            // first gap as starting at the very front of the file.
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
                        "${gapStartMillisFound}ms, expected ~${gap.startTimestampMillis}ms " +
                        "(tolerance ${toleranceMillis}ms)",
                    Math.abs(gapStartMillisFound - gap.startTimestampMillis) <= toleranceMillis,
                )
                assertTrue(
                    "gap [${gap.startTimestampMillis},${gap.endTimestampMillis}) end found at " +
                        "${gapEndMillisFound}ms, expected ~${gap.endTimestampMillis}ms " +
                        "(tolerance ${toleranceMillis}ms)",
                    Math.abs(gapEndMillisFound - gap.endTimestampMillis) <= toleranceMillis,
                )

                // Next gap's search must start after this one's end -- proves ordering as well as
                // each individual offset, and is what would surface a mis-splice that only appears
                // from the second gap onward (see this class's doc / PR #28's original defect).
                searchFromSample = gapEndSample
            }
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
