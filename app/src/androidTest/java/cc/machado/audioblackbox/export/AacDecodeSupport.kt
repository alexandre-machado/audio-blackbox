package cc.machado.audioblackbox.export

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File

/** What a real player/`MediaExtractor` would read back out of an encoded `.m4a` file -- used by
 * [AacRoundTripTest]/[AacGapOffsetTest] to assert on the *container's declared* values, not the
 * [cc.machado.audioblackbox.audio.AudioConfig] that produced them (issue #32, same rigor
 * `WavRoundTripTest` already applies to the WAV path). */
internal data class DecodedAac(
    val pcm: ByteArray,
    val sampleRateHz: Int,
    val channelCount: Int,
    val containerDurationUs: Long,
)

/**
 * Decodes a `.m4a` file back to raw PCM via `MediaExtractor` + `MediaCodec`, exactly as a real
 * player would (issue #32) -- the JVM tier cannot do this at all, since both classes are
 * Android-only, which is exactly why this lives in the instrumented tier instead.
 */
internal object AacDecodeSupport {

    fun decode(file: File): DecodedAac {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(i)
            val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = candidate
                break
            }
        }
        val trackFormat = checkNotNull(format) { "no audio track found in ${file.absolutePath}" }
        extractor.selectTrack(trackIndex)

        val mime = checkNotNull(trackFormat.getString(MediaFormat.KEY_MIME))
        val codec = MediaCodec.createDecoderByType(mime)
        val out = ByteArrayOutputStream()
        var inputDone = false
        var outputDone = false
        val bufferInfo = MediaCodec.BufferInfo()
        val deadlineNanos = System.nanoTime() + DECODE_DEADLINE_MILLIS * 1_000_000L
        try {
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            while (!outputDone) {
                check(System.nanoTime() <= deadlineNanos) { "AAC decode exceeded ${DECODE_DEADLINE_MILLIS}ms deadline" }
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = requireNotNull(codec.getInputBuffer(inputIndex))
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputIndex >= 0) {
                    val outputBuffer = requireNotNull(codec.getOutputBuffer(outputIndex))
                    if (bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.get(chunk)
                        out.write(chunk)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }

        val durationUs = trackFormat.getLong(MediaFormat.KEY_DURATION)
        val sampleRateHz = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        extractor.release()
        return DecodedAac(out.toByteArray(), sampleRateHz, channelCount, durationUs)
    }

    private const val TIMEOUT_US = 10_000L
    private const val DECODE_DEADLINE_MILLIS = 60_000L
}
