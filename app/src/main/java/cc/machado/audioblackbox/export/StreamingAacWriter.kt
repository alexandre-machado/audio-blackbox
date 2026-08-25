package cc.machado.audioblackbox.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.PauseGap
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Long-lived streaming AAC encoder producing AAC-LC in an MP4 (`.m4a`) container via `MediaCodec`
 * and `MediaMuxer` (issue #52).
 *
 * Unlike [AacPayloadEncoder] (which performs a bounded encode on a complete, pre-assembled PCM
 * byte array), this class manages a live, open encode session that accepts incremental PCM chunks
 * as they are drained (e.g. from `RingBuffer.readSince`), supports live gap injection (wall-clock
 * silence insertion on interruption resume), and allows clean finalization at an arbitrary point
 * to produce a valid, decodable standard `.m4a` file.
 *
 * ## Presentation Timestamps & Gap Injection
 * Presentation timestamps (`presentationTimeUs`) are strictly computed from cumulative PCM bytes
 * fed ([totalBytesFed] * 1_000_000L / `config.bytesPerSecond`).
 * When interruptions occur (e.g. telephony or mic preemption), [writeGap] inserts frame-aligned
 * zero PCM bytes for the exact wall-clock duration of the pause. This advances [totalBytesFed] by
 * the exact gap size, ensuring timeline continuity and preventing timestamp drift across multiple
 * interruptions.
 *
 * ## Resource Discipline
 * `MediaCodec` and `MediaMuxer` instances are system-limited resources. This class guarantees that
 * both codec and muxer are safely released on every exit path (successful [finish], [close],
 * exception during construction, exception during write, or cancellation) without leaking hardware
 * codec instances.
 */
class StreamingAacWriter(
    val outputFile: File,
    val config: AudioConfig,
    val bitRateBps: Int = BIT_RATE_PER_CHANNEL_BPS * config.channelCount,
) : Closeable, AutoCloseable {

    private val lock = Any()

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var codecStarted = false
    private var muxerStarted = false
    private var muxerTrackIndex = -1

    private val bufferInfo = MediaCodec.BufferInfo()
    private val zeroBuffer = ByteArray(ZERO_BUFFER_SIZE)

    private var totalBytesFed = 0L
    private var isFinished = false
    private var isClosed = false

    /** Total PCM bytes (audio + injected silence) fed into the encoder so far. */
    val totalBytesWritten: Long
        get() = synchronized(lock) { totalBytesFed }

    /** Whether [finish] has completed successfully. */
    val isSessionFinished: Boolean
        get() = synchronized(lock) { isFinished }

    /** Whether this writer has been closed / released. */
    val isSessionClosed: Boolean
        get() = synchronized(lock) { isClosed }

    init {
        require(config.sampleRateHz > 0) { "sampleRateHz must be positive, was ${config.sampleRateHz}" }
        require(config.channelCount > 0) { "channelCount must be positive, was ${config.channelCount}" }
        require(bitRateBps > 0) { "bitRateBps must be positive, was $bitRateBps" }

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            config.sampleRateHz,
            config.channelCount,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRateBps)
        }

        // Safeguarded resource creation: if MediaMuxer construction throws (e.g. unwritable file,
        // disk full) or if codec configuration/start fails, any allocated resource is released
        // immediately rather than leaked.
        val createdCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var createdMuxer: MediaMuxer? = null
        var startedCodec = false
        try {
            val muxerInstance = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            createdMuxer = muxerInstance

            createdCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            createdCodec.start()
            startedCodec = true

            this.codec = createdCodec
            this.muxer = muxerInstance
            this.codecStarted = true
        } catch (t: Throwable) {
            if (startedCodec) runCatching { createdCodec.stop() }
            runCatching { createdCodec.release() }
            runCatching { createdMuxer?.release() }
            throw t
        }
    }

    /**
     * Writes an incremental chunk of PCM audio data into the live AAC encode stream.
     *
     * @param pcmData Raw PCM audio byte array matching [config].
     * @param offset Starting offset in [pcmData].
     * @param length Number of bytes to write.
     */
    fun write(pcmData: ByteArray, offset: Int = 0, length: Int = pcmData.size) {
        require(offset >= 0) { "offset must not be negative, was $offset" }
        require(length >= 0) { "length must not be negative, was $length" }
        require(offset + length <= pcmData.size) {
            "offset ($offset) + length ($length) exceeds pcmData size (${pcmData.size})"
        }
        if (length == 0) return

        synchronized(lock) {
            check(!isClosed) { "Cannot write to a closed StreamingAacWriter" }
            check(!isFinished) { "Cannot write to a finished StreamingAacWriter" }

            var currentOffset = offset
            var remaining = length
            val deadlineNanos = System.nanoTime() + OP_TIMEOUT_MILLIS * 1_000_000L

            while (remaining > 0) {
                if (System.nanoTime() > deadlineNanos) {
                    throw IOException("AAC encode write exceeded deadline of ${OP_TIMEOUT_MILLIS}ms")
                }
                drainOutput(endOfStream = false, deadlineNanos = deadlineNanos)

                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = requireNotNull(codec.getInputBuffer(inputIndex))
                    inputBuffer.clear()
                    val chunkSize = minOf(inputBuffer.remaining(), remaining)
                    inputBuffer.put(pcmData, currentOffset, chunkSize)
                    val presentationTimeUs = (totalBytesFed * MICROS_PER_SECOND) / config.bytesPerSecond
                    codec.queueInputBuffer(inputIndex, 0, chunkSize, presentationTimeUs, 0)
                    currentOffset += chunkSize
                    remaining -= chunkSize
                    totalBytesFed += chunkSize
                }
            }
            drainOutput(endOfStream = false, deadlineNanos = deadlineNanos)
        }
    }

    /**
     * Convenience alias for [write] to accept incremental PCM chunks.
     */
    fun writePcmChunk(pcmData: ByteArray, offset: Int = 0, length: Int = pcmData.size) {
        write(pcmData, offset, length)
    }

    /**
     * Injects wall-clock silence frames for the specified [gapDurationMillis] to preserve timeline
     * alignment when an interruption occurs.
     */
    fun writeGap(gapDurationMillis: Long) {
        require(gapDurationMillis >= 0) { "gapDurationMillis must not be negative, was $gapDurationMillis" }
        if (gapDurationMillis == 0L) return

        synchronized(lock) {
            check(!isClosed) { "Cannot write gap to a closed StreamingAacWriter" }
            check(!isFinished) { "Cannot write gap to a finished StreamingAacWriter" }

            val bytesPerSecond = config.bytesPerSecond
            val bytesPerFrame = config.bytesPerFrame
            val rawSilenceBytes = (gapDurationMillis * bytesPerSecond) / 1000L
            val alignedSilenceBytes = (rawSilenceBytes - (rawSilenceBytes % bytesPerFrame)).toInt()
            if (alignedSilenceBytes > 0) {
                writeSilenceBytes(alignedSilenceBytes)
            }
        }
    }

    /**
     * Injects wall-clock silence frames for a [PauseGap].
     */
    fun writeGap(gap: PauseGap) {
        writeGap(gap.durationMillis)
    }

    /**
     * Injects wall-clock silence frames for [durationMillis].
     */
    fun writeSilence(durationMillis: Long) {
        writeGap(durationMillis)
    }

    private fun writeSilenceBytes(totalBytes: Int) {
        var remaining = totalBytes
        while (remaining > 0) {
            val chunkSize = minOf(remaining, ZERO_BUFFER_SIZE)
            write(zeroBuffer, 0, chunkSize)
            remaining -= chunkSize
        }
    }

    private fun drainOutput(endOfStream: Boolean, deadlineNanos: Long) {
        while (true) {
            if (System.nanoTime() > deadlineNanos) {
                throw IOException("AAC encode drain exceeded deadline")
            }
            val timeout = if (endOfStream) TIMEOUT_US else 0L
            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeout)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "MediaCodec changed output format more than once" }
                    muxerTrackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (endOfStream) {
                        continue
                    } else {
                        return
                    }
                }
                else -> if (outputIndex >= 0) {
                    val outputBuffer = requireNotNull(codec.getOutputBuffer(outputIndex))
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
                        return
                    }
                }
            }
        }
    }

    /**
     * Finalizes the live AAC session at the current point, flushes the encoder, stops the muxer,
     * writes container headers (`moov` atom), and releases all resources.
     *
     * Once finalized, [outputFile] is a valid, decodable, standard `.m4a` file.
     */
    fun finish() {
        synchronized(lock) {
            if (isFinished) return
            check(!isClosed) { "StreamingAacWriter is already closed" }

            try {
                val deadlineNanos = System.nanoTime() + FINISH_DEADLINE_MILLIS * 1_000_000L
                var eosQueued = false
                while (!eosQueued) {
                    if (System.nanoTime() > deadlineNanos) {
                        throw IOException("AAC encode finish exceeded deadline while queuing EOS")
                    }
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val presentationTimeUs = (totalBytesFed * MICROS_PER_SECOND) / config.bytesPerSecond
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            presentationTimeUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        eosQueued = true
                    } else {
                        drainOutput(endOfStream = false, deadlineNanos = deadlineNanos)
                    }
                }

                drainOutput(endOfStream = true, deadlineNanos = deadlineNanos)

                if (muxerStarted) {
                    muxer.stop()
                    muxerStarted = false
                }
                if (codecStarted) {
                    codec.stop()
                    codecStarted = false
                }
                isFinished = true
            } finally {
                releaseResources()
            }
        }
    }

    /**
     * Releases codec and muxer resources. If called before [finish], safely stops and releases
     * resources without leaving lingering native handles. Calling [close] multiple times is a no-op.
     */
    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            try {
                if (!isFinished) {
                    if (codecStarted) {
                        runCatching { codec.stop() }
                        codecStarted = false
                    }
                    if (muxerStarted) {
                        runCatching { muxer.stop() }
                        muxerStarted = false
                    }
                }
            } finally {
                releaseResources()
            }
        }
    }

    private fun releaseResources() {
        isClosed = true
        if (codecStarted) {
            runCatching { codec.stop() }
            codecStarted = false
        }
        runCatching { codec.release() }
        if (muxerStarted) {
            runCatching { muxer.stop() }
            muxerStarted = false
        }
        runCatching { muxer.release() }
    }

    companion object {
        const val MIME_TYPE_M4A = "audio/mp4"
        const val FILE_EXTENSION = "m4a"

        private const val TIMEOUT_US = 10_000L
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val OP_TIMEOUT_MILLIS = 30_000L
        private const val FINISH_DEADLINE_MILLIS = 60_000L
        private const val ZERO_BUFFER_SIZE = 4096

        /** Default ~64 kbps per audio channel for AAC-LC. */
        const val BIT_RATE_PER_CHANNEL_BPS = 64_000
    }
}

/** Alias for [StreamingAacWriter]. */
typealias StreamingAacEncoder = StreamingAacWriter
