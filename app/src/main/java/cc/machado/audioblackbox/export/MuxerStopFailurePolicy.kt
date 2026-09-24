package cc.machado.audioblackbox.export

import java.io.IOException

/**
 * What an independent re-read of a finished `.m4a` found (issue #378). Produced on-device by
 * [Mp4OutputProbe]; kept free of Android types so [MuxerStopFailurePolicy] can be tested on the JVM.
 */
internal sealed interface OutputProbeResult {
    /**
     * The container has an audio track whose sample table can actually be walked.
     *
     * @property durationUs The track duration the container declares, or -1 if it declares none.
     * @property firstSampleBytes Size of the first sample read back through the sample table.
     * @property lastSampleBytes Size of the sample read after seeking to the declared end.
     */
    data class Decodable(
        val mime: String,
        val durationUs: Long,
        val firstSampleBytes: Int,
        val lastSampleBytes: Int,
    ) : OutputProbeResult

    /** Anything else, with the reason, so a failure log says why rather than just "invalid". */
    data class NotDecodable(val reason: String) : OutputProbeResult
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
 * audio track, reads a real sample through the index, and declares at least as much audio as was
 * written ([minimumDurationUs]). Anything short of that is a failure, reported with the probe's
 * reason and the writer's own diagnostics so the log is actionable without a device attached.
 */
internal object MuxerStopFailurePolicy {

    /**
     * @return null when the output was verified decodable and complete (the caller records the
     * recovery and carries on), otherwise the exception to throw. The original `stop()` error is
     * always kept as the cause.
     */
    fun resolve(
        stopError: IllegalStateException,
        probe: OutputProbeResult,
        minimumDurationUs: Long,
        diagnostics: String,
    ): IOException? {
        val reason = when (probe) {
            is OutputProbeResult.NotDecodable -> probe.reason
            is OutputProbeResult.Decodable -> when {
                !probe.mime.startsWith("audio/") -> "first track is ${probe.mime}, not audio"
                probe.firstSampleBytes <= 0 -> "sample table yielded no readable sample"
                probe.lastSampleBytes <= 0 -> "last indexed sample is not readable (truncated data)"
                probe.durationUs < 0 -> "container declares no duration"
                probe.durationUs < minimumDurationUs ->
                    "container declares ${probe.durationUs}us but ${minimumDurationUs}us of audio was written"
                else -> return null
            }
        }
        return IOException(
            "MediaMuxer.stop() failed (${stopError.message}) and the output is not a complete, " +
                "decodable recording: $reason. $diagnostics",
            stopError,
        )
    }
}
