package cc.machado.audioblackbox.export

import java.io.IOException

/**
 * What an independent re-read of a finished `.m4a` found (issue #378). Produced on-device by
 * [Mp4OutputProbe]; kept free of Android types so [MuxerStopFailurePolicy] can be tested on the JVM.
 *
 * "Indexed" rather than "decodable": the probe walks the container's sample table and reads
 * sample bytes back through it, but it does not run a decoder over them.
 */
internal sealed interface OutputProbeResult {
    /**
     * The container has a track whose sample table can actually be walked.
     *
     * @property durationUs The track duration the container declares, or -1 if it declares none.
     * @property firstSampleBytes Size of the first sample read back through the sample table.
     * @property lastSampleBytes Size of the sample read after seeking to the declared end.
     * @property lastSampleTimeUs Where that seek actually landed (the extractor's sample time), or
     *   -1 if it landed nowhere.
     */
    data class Indexed(
        val mime: String,
        val durationUs: Long,
        val firstSampleBytes: Int,
        val lastSampleBytes: Int,
        val lastSampleTimeUs: Long,
    ) : OutputProbeResult

    /** Anything else, with the reason, so a failure log says why rather than just "invalid". */
    data class NotIndexed(val reason: String) : OutputProbeResult
}

/**
 * Decides what `StreamingAacWriter.finish()` does when `MediaMuxer.stop()` throws (issue #378).
 *
 * `stop()`'s message ("muxer would have stopped already") is the JNI layer's text for *any* non-OK
 * status from the native `MediaMuxer::stop()`, so it says nothing about whether the file is usable.
 * Two very different outcomes hide behind it:
 * - the `moov` box was written and only a later step failed (e.g. `release()`'s `ftruncate`/`close`),
 *   in which case the file is fine;
 * - the track was marked malformed, in which case AOSP still writes a `moov` but with an empty
 *   sample table, and the file is not playable even though it has a `moov` and is the right size.
 *
 * So the only acceptable basis for reporting success is an independent re-read that finds an
 * audio track, reads a real sample through the index at both ends, lands the tail seek on the
 * last frame, and declares every frame that was written, including the last frame's own
 * duration. Anything short of that is a failure, reported with the probe's reason and the
 * writer's own diagnostics so the log is actionable without a device attached.
 */
internal object MuxerStopFailurePolicy {

    /**
     * @param writtenSpanUs `lastPts - firstPts` of the samples handed to the muxer, i.e. the
     *   written audio minus the last frame's own duration.
     * @param frameDurationUs One encoded frame's duration.
     * @return null when the output was verified complete (the caller records the recovery and
     *   carries on), otherwise the exception to throw. The original `stop()` error is always kept
     *   as the cause.
     */
    fun resolve(
        stopError: IllegalStateException,
        probe: OutputProbeResult,
        writtenSpanUs: Long,
        frameDurationUs: Long,
        diagnostics: String,
    ): IOException? {
        // The declared duration should be the span plus the last frame. A quarter frame of slack
        // absorbs the muxer's per-sample tick rounding while still failing an index that is
        // missing even one whole frame.
        val minimumDurationUs = writtenSpanUs + frameDurationUs - frameDurationUs / 4
        val reason = when (probe) {
            is OutputProbeResult.NotIndexed -> probe.reason
            is OutputProbeResult.Indexed -> when {
                !probe.mime.startsWith("audio/") -> "first track is ${probe.mime}, not audio"
                probe.firstSampleBytes <= 0 -> "sample table yielded no readable sample"
                probe.lastSampleBytes <= 0 -> "last indexed sample is not readable (truncated data)"
                probe.durationUs < 0 -> "container declares no duration"
                probe.durationUs < minimumDurationUs ->
                    "container declares ${probe.durationUs}us but at least ${minimumDurationUs}us of audio was written"
                // A few frames of slack for edit-list/priming offsets in the extractor's timeline;
                // a seek that missed the end lands whole seconds away, not frames.
                probe.lastSampleTimeUs < probe.durationUs - TAIL_SEEK_SLACK_FRAMES * frameDurationUs ->
                    "seek to the declared end (${probe.durationUs}us) landed at ${probe.lastSampleTimeUs}us, " +
                        "not on the last frame"
                else -> return null
            }
        }
        return IOException(
            "MediaMuxer.stop() failed (${stopError.message}) and the output is not a complete " +
                "recording: $reason. $diagnostics",
            stopError,
        )
    }

    private const val TAIL_SEEK_SLACK_FRAMES = 4
}
