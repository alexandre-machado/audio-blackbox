package cc.machado.audioblackbox.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #378: when `MediaMuxer.stop()` throws, [MuxerStopFailurePolicy] is the only thing that may
 * turn it into a success.
 *
 * ## Oracle
 * The rule from the issue: never report success for a file that is not a complete recording, and
 * never silently lose audio. So the policy must return null (success) only for a probe that found
 * an audio track, read a real sample at both ends, landed the tail seek on the last frame, and
 * declares every written frame including the last one's duration. Every other probe result must
 * produce an exception that keeps the original `stop()` error as its cause and carries both the
 * probe's reason and the writer's diagnostics.
 */
class MuxerStopFailurePolicyTest {

    private val stopError = IllegalStateException("Error during stop(), muxer would have stopped already")
    private val diagnostics = "[muxer input: samples=3]"

    // 44.1 kHz AAC-LC: one frame is 1024 samples, 23220 us rounded up.
    private val frameUs = 23_220L

    // 216 frames written: pts of the last minus pts of the first.
    private val spanUs = 215 * frameUs

    // What a complete file declares: the span plus the last frame's own duration.
    private val completeDurationUs = spanUs + frameUs

    private fun indexed(
        mime: String = "audio/mp4a-latm",
        durationUs: Long = completeDurationUs,
        firstSampleBytes: Int = 371,
        lastSampleBytes: Int = 371,
        lastSampleTimeUs: Long = completeDurationUs - frameUs,
    ) = OutputProbeResult.Indexed(mime, durationUs, firstSampleBytes, lastSampleBytes, lastSampleTimeUs)

    private fun resolve(probe: OutputProbeResult) =
        MuxerStopFailurePolicy.resolve(stopError, probe, spanUs, frameUs, diagnostics)

    @Test
    fun malformedTrack_probeFindsNoTrack_isAFailure() {
        // What AOSP leaves behind for a malformed track: a moov whose stbl is empty, which the
        // extractor drops, so the container reports no usable track.
        val failure = resolve(OutputProbeResult.NotIndexed("container has no usable track"))

        assertNotNull("an unindexed file must never be reported as success", failure)
        assertSame(stopError, failure!!.cause)
        assertTrue(failure.message!!, failure.message!!.contains("container has no usable track"))
        assertTrue(failure.message!!, failure.message!!.contains(diagnostics))
    }

    @Test
    fun completeFile_isRecovered() {
        assertNull(resolve(indexed()))
    }

    @Test
    fun completeFile_withTickRoundingShortfall_isStillRecovered() {
        // The muxer rounds each sample to its timescale; a few us short must not fail a good file.
        assertNull(resolve(indexed(durationUs = completeDurationUs - 50)))
    }

    @Test
    fun indexMissingOnlyTheLastFrame_isAFailure() {
        // @rev PR #415 low 2: the old threshold (span only) accepted an index missing exactly the
        // last sample, because it ignored the last frame's own duration.
        val failure = resolve(indexed(durationUs = spanUs, lastSampleTimeUs = spanUs - frameUs))

        assertNotNull(failure)
        assertTrue(failure!!.message!!, failure.message!!.contains("of audio was written"))
    }

    @Test
    fun indexCoveringLessAudioThanWritten_isAFailure() {
        val failure = resolve(indexed(durationUs = 2_000_000L, lastSampleTimeUs = 2_000_000L - frameUs))

        assertNotNull(failure)
        assertTrue(failure!!.message!!, failure.message!!.contains("declares 2000000us"))
    }

    @Test
    fun tailSeekLandingFarFromTheEnd_isAFailure() {
        // @rev PR #415 low 2: a readable "last" sample means nothing if the seek landed elsewhere.
        val failure = resolve(indexed(lastSampleTimeUs = 0L))

        assertNotNull(failure)
        assertTrue(failure!!.message!!, failure.message!!.contains("not on the last frame"))
    }

    @Test
    fun noReadableSample_isAFailure() {
        assertNotNull(resolve(indexed(firstSampleBytes = -1)))
    }

    @Test
    fun unreadableTail_isAFailure() {
        // An index whose end points past the data on disk: the first sample reads, the end does not.
        val failure = resolve(indexed(lastSampleBytes = -1))

        assertNotNull(failure)
        assertTrue(failure!!.message!!, failure.message!!.contains("truncated"))
    }

    @Test
    fun undeclaredDuration_isAFailure() {
        assertNotNull(resolve(indexed(durationUs = -1L)))
    }

    @Test
    fun nonAudioTrack_isAFailure() {
        assertNotNull(resolve(indexed(mime = "video/avc")))
    }

    @Test
    fun timestampCorrectionAuditReason_isNotShownAsAnError() {
        // The success-path audit entry must not light up the dashboard's error card (issue #346).
        assertEquals(ErrorLogSeverity.AUDIT, severityForReason("MUXER_TIMESTAMP_CORRECTED"))
    }
}
