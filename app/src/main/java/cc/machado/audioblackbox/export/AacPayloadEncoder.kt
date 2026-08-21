package cc.machado.audioblackbox.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import cc.machado.audioblackbox.audio.AudioConfig
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream

/**
 * [PayloadEncoder] producing AAC-LC in an MP4 (`.m4a`) container via `MediaCodec`/`MediaMuxer`
 * (issue #32). Wired as the default production encoder in
 * [cc.machado.audioblackbox.service.RecorderService] because it is the format every stock player
 * on the target device already opens -- see issue #32's device evidence (176 audio files on the
 * S25, zero of them WAV).
 *
 * `MediaMuxer` can only write to a real file (a path or a `FileDescriptor`), not an arbitrary
 * [OutputStream] -- unlike [WavWriter] there is no way to stream AAC frames directly into
 * `MediaStore`'s pending [OutputStream] as they're produced. So this class encodes into a private
 * temporary file under [tempDir] first, then copies that file's bytes into [OutputStream] once
 * the mux is complete, and always deletes the temp file afterward (success or failure) --
 * [MediaStoreSink]'s `IS_PENDING` row is untouched either way; encode failures propagate as a
 * thrown exception exactly like a [WavWriter] write failure would, so [ExportEngine]'s existing
 * catch-and-abort path deletes the pending row without this class needing to know about
 * `MediaStore` at all.
 *
 * ## Encoder priming delay -- measured, not assumed (issue #32 requirement)
 * AAC-LC's MDCT needs look-ahead, which is the textbook source of a fixed "encoder delay" baked
 * into the first encoded frame. Rather than assume a textbook number, this was measured directly
 * against Android's own encoder + muxer combination on the emulator this repo's instrumented tier
 * runs on (API 30, `google_apis`/x86_64 -- see `scripts/ci/avd.env`), by encoding a known tone,
 * decoding it back, and scanning the decoded PCM for the first window whose Goertzel energy at the
 * tone's frequency reaches half of the steady-state (mid-signal) reference -- see
 * `AacRoundTripTest.measureAndBoundLeadingPrimingSamples`, `app/src/androidTest/.../export/`:
 *
 * **Measured priming delay: exactly 2048 samples, both at 16kHz mono (128ms) and at 44.1kHz
 * stereo (46ms)** -- i.e. a fixed *sample count* (two 1024-sample AAC frames' worth), not a fixed
 * *duration*, and independent of channel count. Two things follow from this, both confirmed by the
 * instrumented tests cited above rather than assumed:
 *
 * 1. **This class assigns every input buffer's `presentationTimeUs` itself**, computed from the
 *    cumulative number of raw PCM bytes already queued (`totalBytesFed / bytesPerSecond`), never
 *    left to the codec to invent. `MediaMuxer.writeSampleData` writes back exactly the
 *    `presentationTimeUs` carried on each output buffer's `BufferInfo`, and Android's AAC-LC
 *    encoder (unlike video encoders with B-frames) does not reorder buffers -- the Nth output
 *    buffer corresponds to the Nth input buffer's timestamp, delayed by a fixed number of buffers
 *    but not time-shifted relative to how much PCM produced it. So the container's declared
 *    duration (last sample's PTS + its duration, which is what `MediaExtractor`/
 *    `MediaMetadataRetriever` and every player read) lands within the measured 2048 samples of the
 *    true input duration -- confirmed by `AacRoundTripTest`'s container duration assertion --
 *    rather than accumulating drift proportional to the recording length.
 * 2. **The priming delay is not free: it shows up as 2048 samples of quiet, transient content
 *    prepended to the decoded audio**, not as a timeline shift of everything after it. Confirmed
 *    by `AacGapOffsetTest`: a gap-filled PCM payload with a silent region in the middle still
 *    decodes with that silence at the same *relative* offset from the start (within a documented
 *    2048-sample tolerance for this fixed priming delay plus encoder frame quantization -- neither
 *    is a bug), because gap filling happens once on the whole PCM payload before encoding ever
 *    starts (see [ExportEngine]/[GapFiller]) -- there is only one priming delay for the entire
 *    export, at the very front, not one per gap. 128ms of extra content at the very start of a
 *    multi-minute ambient recording is the accepted cost of this
 *    codec property for this product; there is no `MediaMuxer` API to write an edit list
 *    (`elst`/gapless-playback metadata) to trim it, and hand-rolling one was judged not worth the
 *    risk for this issue's scope.
 */
class AacPayloadEncoder(private val tempDir: File) : PayloadEncoder {

    override val mimeType: String = MIME_TYPE_M4A
    override val fileExtension: String = "m4a"

    override fun encode(config: AudioConfig, payload: ByteArray, out: OutputStream, isCancelled: () -> Boolean) {
        val tempFile = File.createTempFile(TEMP_FILE_PREFIX, ".m4a", tempDir)
        try {
            encodeToFile(config, payload, tempFile, isCancelled)
            if (!isCancelled()) {
                FileInputStream(tempFile).use { input -> input.copyTo(out) }
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun encodeToFile(config: AudioConfig, payload: ByteArray, tempFile: File, isCancelled: () -> Boolean) {
        val bytesPerSecond = config.bytesPerSecond
        val bitRateBps = BIT_RATE_PER_CHANNEL_BPS * config.channelCount

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            config.sampleRateHz,
            config.channelCount,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRateBps)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var codecStarted = false
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codecStarted = true

            var muxerTrackIndex = -1
            var inputOffset = 0
            var totalBytesFed = 0L
            var inputDone = false
            var outputDone = false
            val bufferInfo = MediaCodec.BufferInfo()
            // Generous but finite: a stuck codec must eventually surface as a failure (which
            // ExportEngine already converts into an aborted, deleted pending row) rather than hang
            // the export thread forever -- see class doc on failure propagation.
            val deadlineNanos = System.nanoTime() + ENCODE_DEADLINE_MILLIS * 1_000_000L

            while (!outputDone) {
                if (isCancelled()) return // ExportEngine aborts the sink; a partial temp file is fine, it's never copied to `out`.
                if (System.nanoTime() > deadlineNanos) {
                    throw IOException("AAC encode exceeded ${ENCODE_DEADLINE_MILLIS}ms deadline")
                }
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = requireNotNull(codec.getInputBuffer(inputIndex))
                        inputBuffer.clear()
                        val remainingPayload = payload.size - inputOffset
                        val chunkSize = minOf(inputBuffer.remaining(), remainingPayload)
                        val presentationTimeUs = (totalBytesFed * MICROS_PER_SECOND) / bytesPerSecond
                        if (chunkSize > 0) {
                            inputBuffer.put(payload, inputOffset, chunkSize)
                            codec.queueInputBuffer(inputIndex, 0, chunkSize, presentationTimeUs, 0)
                            inputOffset += chunkSize
                            totalBytesFed += chunkSize
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                presentationTimeUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "MediaCodec changed output format more than once" }
                        muxerTrackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = requireNotNull(codec.getOutputBuffer(outputIndex))
                        // BUFFER_FLAG_CODEC_CONFIG carries the AudioSpecificConfig, not sample
                        // data -- MediaMuxer must never receive it as a sample.
                        if (bufferInfo.size > 0 &&
                            (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            check(muxerStarted) { "encoder produced sample data before the muxer's track was added" }
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(muxerTrackIndex, outputBuffer, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            // Guarded individually and never let one failure skip the others' cleanup: a codec
            // configure()/start() failure must still release the muxer (and vice versa), and a
            // muxer that never reached start() (e.g. encoding failed before the first
            // INFO_OUTPUT_FORMAT_CHANGED) must not have stop() called on it -- MediaMuxer.stop()
            // throws IllegalStateException if start() was never called.
            if (codecStarted) runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private companion object {
        const val MIME_TYPE_M4A = "audio/mp4"
        const val TEMP_FILE_PREFIX = "aac_export_"
        const val TIMEOUT_US = 10_000L
        const val MICROS_PER_SECOND = 1_000_000L
        const val ENCODE_DEADLINE_MILLIS = 5 * 60_000L

        // ~64 kbps mono, per issue #32's sizing analysis (~14MB for a 30-minute mono save vs
        // ~57.6MB WAV); scaled by channel count so a future stereo config gets proportionally more
        // bitrate rather than starving a second channel at the mono rate.
        const val BIT_RATE_PER_CHANNEL_BPS = 64_000
    }
}
