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
 * The rule from the issue: never report success for a file that is not decodable, and never
 * silently lose audio. So the policy must return null (success) only for a probe that found an
 * audio track, read a real sample, and declares at least the audio that was written; every other
 * probe result must produce an exception that keeps the original `stop()` error as its cause
 * and carries both the probe's reason and the writer's diagnostics.
 */
class MuxerStopFailurePolicyTest {

    private val stopError = IllegalStateException("Error during stop(), muxer would have stopped already")
    private val diagnostics = "[muxer input: samples=3]"

    @Test
    fun malformedTrack_probeFindsNoTrack_isAFailure() {
        // What AOSP leaves behind for a malformed track: a moov whose stbl is empty, which the
        // extractor drops, so the container reports no usable track.
        val failure = MuxerStopFailurePolicy.resolve(
            stopError,
            OutputProbeResult.NotDecodable("container has no usable track"),
            minimumDurationUs = 1_000_000L,
            diagnostics = diagnostics,
        )

        assertNotNull("an undecodable file must never be reported as success", failure)
        assertSame(stopError, failure!!.cause)
        assertTrue(failure.message!!, failure.message!!.contains("container has no usable track"))
        assertTrue(failure.message!!, failure.message!!.contains(diagnostics))
    }

    @Test
    fun completeDecodableFile_isRecovered() {
        val failure = MuxerStopFailurePolicy.resolve(
            stopError,
            OutputProbeResult.Decodable("audio/mp4a-latm", durationUs = 5_023_220L, firstSampleBytes = 371, lastSampleBytes = 371),
            minimumDurationUs = 5_000_000L,
            diagnostics = diagnostics,
        )

        assertNull(failure)
    }

    @Test
    fun indexCoveringLessAudioThanWritten_isAFailure() {
        // A moov that indexes only part of what was written would play, but silently drop the
        // rest: that is lost audio, not a recovery.
        val failure = MuxerStopFailurePolicy.resolve(
            stopError,
            OutputProbeResult.Decodable("audio/mp4a-latm", durationUs = 2_000_000L, firstSampleBytes = 371, lastSampleBytes = 371),
            minimumDurationUs = 5_000_000L,
            diagnostics = diagnostics,
        )

        assertNotNull(failure)
        assertTrue(failure!!.message!!, failure.message!!.contains("2000000us but 5000000us"))
    }

    @Test
    fun noReadableSample_isAFailure() {
        val failure = MuxerStopFailurePolicy.resolve(
            stopError,
            OutputProbeResult.Decodable("audio/mp4a-latm", durationUs = 5_023_220L, firstSampleBytes = -1, lastSampleBytes = 371),
            minimumDurationUs = 5_000_000L,
            diagnostics = diagnostics,
        )

        assertNotNull(failure)
    }

    @Test
    fun unreadableTail_isAFailure() {
        // An index whose end points past the data on disk: the first sample reads, the end does not.
        val failure = MuxerStopFailurePolicy.resolve(
            stopError,
            OutputProbeResult.Decodable("audio/mp4a-latm", durationUs = 5_023_220L, firstSampleBytes = 371, lastSampleBytes = -1),
            minimumDurationUs = 5_000_000L,
            diagnostics = diagnostics,
        )

        assertNotNull(failure)
        assertTrue(failure!!.message!!, failure.message!!.contains("truncated"))
    }

    @Test
    fun undeclaredDuration_isAFailure() {
        val failure = MuxerStopFailurePolicy.resolve(
            stopError,
            OutputProbeResult.Decodable("audio/mp4a-latm", durationUs = -1L, firstSampleBytes = 371, lastSampleBytes = 371),
            minimumDurationUs = 0L,
            diagnostics = diagnostics,
        )

        assertNotNull(failure)
    }

    @Test
    fun nonAudioTrack_isAFailure() {
        val failure = MuxerStopFailurePolicy.resolve(
            stopError,
            OutputProbeResult.Decodable("video/avc", durationUs = 5_023_220L, firstSampleBytes = 371, lastSampleBytes = 371),
            minimumDurationUs = 5_000_000L,
            diagnostics = diagnostics,
        )

        assertNotNull(failure)
    }

    @Test
    fun timestampCorrectionAuditReason_isNotShownAsAnError() {
        // The success-path audit entry must not light up the dashboard's error card (issue #346).
        assertEquals(ErrorLogSeverity.AUDIT, severityForReason("MUXER_TIMESTAMP_CORRECTED"))
    }
}
