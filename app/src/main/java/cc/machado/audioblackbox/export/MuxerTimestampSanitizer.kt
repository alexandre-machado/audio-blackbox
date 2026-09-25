package cc.machado.audioblackbox.export

/**
 * Keeps the presentation timestamps handed to `MediaMuxer.writeSampleData` strictly increasing
 * and non-negative for one AAC-LC track (issue #378).
 *
 * ## Why this exists
 * AOSP's `MPEG4Writer::Track::threadEntry` marks a track malformed (`mIsMalformed = true`) the
 * moment a sample's timestamp goes backwards ("do not support out of order frames") or is
 * negative, and stops reading that track. Nothing is reported at that point. The failure only
 * surfaces at `MediaMuxer.stop()`: `MPEG4Writer::reset()` gets `ERROR_MALFORMED` back from the
 * track, still writes a `moov` box, but `Track::writeStblBox()` leaves the sample table empty for
 * a malformed track (no `stsd`/`stts`/`stsz`/`stco`), and the JNI layer turns the non-OK status
 * into `IllegalStateException("Error during stop(), muxer would have stopped already")`. The
 * resulting file holds all of the encoded audio in `mdat` with no index to find it.
 *
 * **This was not the S25's failure, and it stays as defence in depth.** Tier 2 on PR #415 ran the
 * S25's encoder (`c2.android.aac.encoder`) and this sanitizer corrected nothing there
 * (`ptsCorrections=0`); the encoder already repairs overlapping input timestamps itself. The
 * S25's `stop()` failure was `ERROR_IO` (-1004), not `ERROR_MALFORMED` (-1007): MPEG4Writer's
 * `ftruncate` hit EIO on a MediaStore descriptor opened before the early commit renamed the file
 * (see [MediaStoreSink.openStreaming]). The JNI reports every non-OK `stop()` with the same
 * "muxer would have stopped already" text, which is how the two looked alike. The malformed-track
 * shape is still real on AOSP's muxer (`BareMuxerStopFailureTest` builds it on the device), so an
 * encoder that does regress timestamps is still covered.
 *
 * A malformation well before the end would make a following `writeSampleData` throw instead ("writeSampleData returned an error"), not `stop()`. The only
 * write whose failure `StreamingAacWriter` used to swallow is the empty end-of-stream marker that
 * follows the last real sample, so a `stop()`-time failure points at the tail of the stream (the
 * last sample or two: a write can race the track thread's malformed check and still return OK,
 * moving the visible failure one write later), where the S25's encoder emits the frame with
 * `BUFFER_FLAG_END_OF_STREAM` set on it (issue #347).
 *
 * ## Why rewriting the timestamp is correct, not a cover-up
 * Every AAC-LC access unit decodes to exactly [samplesPerFrame] samples, so the time between two
 * consecutive frames is fixed by the format, not by what the encoder chose to stamp. When the
 * encoder's timestamp is usable (strictly after the previous one) it is passed through
 * unchanged. When it is not, the only timestamp the next frame can legitimately have is the
 * previous one plus one frame duration, which is what this returns. No audio is dropped or
 * reordered; only the label on the frame changes.
 *
 * Every rewrite is counted and the first one is described in [firstCorrection], so the device
 * can report whether this ever fired (see `ForwardRecordingEngine`'s `MUXER_TIMESTAMP_CORRECTED`
 * audit entry) rather than the fix silently papering over an encoder quirk.
 */
internal class MuxerTimestampSanitizer(
    sampleRateHz: Int,
    val samplesPerFrame: Int = AAC_LC_SAMPLES_PER_FRAME,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }
        require(samplesPerFrame > 0) { "samplesPerFrame must be positive, was $samplesPerFrame" }
    }

    /** One frame's duration in microseconds, rounded up so a rewritten timestamp can never land
     * on the same muxer tick as the frame before it. */
    val frameDurationUs: Long = (samplesPerFrame * MICROS_PER_SECOND + sampleRateHz - 1) / sampleRateHz

    /** Timestamp returned by the most recent [next] call, or -1 before the first sample. */
    var lastPtsUs: Long = NO_SAMPLE
        private set

    /** Timestamp returned by the first [next] call, or -1 before the first sample. */
    var firstPtsUs: Long = NO_SAMPLE
        private set

    /** Number of samples whose encoder timestamp had to be rewritten. */
    var corrections: Int = 0
        private set

    /** Human-readable description of the first rewrite, or null if none happened. */
    var firstCorrection: String? = null
        private set

    /** Number of samples passed through [next]. */
    var samples: Int = 0
        private set

    /**
     * Returns the timestamp to hand to the muxer for a sample the encoder stamped [codecPtsUs].
     * [isEndOfStream] only affects the wording of [firstCorrection].
     */
    fun next(codecPtsUs: Long, isEndOfStream: Boolean = false): Long {
        val previous = lastPtsUs
        val out = when {
            previous == NO_SAMPLE -> maxOf(codecPtsUs, 0L)
            codecPtsUs > previous -> codecPtsUs
            else -> previous + frameDurationUs
        }
        if (out != codecPtsUs) {
            corrections++
            if (firstCorrection == null) {
                firstCorrection = "sample #$samples${if (isEndOfStream) " (EOS-flagged)" else ""}: " +
                    "encoder pts ${codecPtsUs}us after previous ${previous}us, written as ${out}us"
            }
        }
        if (previous == NO_SAMPLE) firstPtsUs = out
        lastPtsUs = out
        samples++
        return out
    }

    /** Timestamp for the empty end-of-stream marker: one frame after the last sample, so the
     * muxer records the last frame's real duration instead of zero. */
    fun endOfStreamMarkerPtsUs(): Long {
        check(lastPtsUs != NO_SAMPLE) { "no sample has been written yet" }
        return lastPtsUs + frameDurationUs
    }

    companion object {
        const val AAC_LC_SAMPLES_PER_FRAME = 1024
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val NO_SAMPLE = -1L
    }
}
