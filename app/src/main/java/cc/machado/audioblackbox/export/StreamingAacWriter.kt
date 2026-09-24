package cc.machado.audioblackbox.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.PauseGap
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.IOException

/**
 * Common streaming audio writer interface for incremental PCM chunks and live gap injection (issue #54).
 */
interface StreamingAudioWriter : Closeable, AutoCloseable {
    val totalBytesWritten: Long
    val isSessionFinished: Boolean
    val isSessionClosed: Boolean

    fun write(pcmData: ByteArray, offset: Int = 0, length: Int = pcmData.size)
    fun writePcmChunk(pcmData: ByteArray, offset: Int = 0, length: Int = pcmData.size) { write(pcmData, offset, length) }
    fun writeGap(gapDurationMillis: Long)
    fun writeGap(gap: PauseGap) { writeGap(gap.durationMillis) }
    fun writeSilence(durationMillis: Long) { writeGap(durationMillis) }
    fun finish()
}

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
class StreamingAacWriter private constructor(
    val outputFile: File?,
    val fileDescriptor: FileDescriptor?,
    val config: AudioConfig,
    val bitRateBps: Int,
) : StreamingAudioWriter, Closeable, AutoCloseable {

    constructor(
        outputFile: File,
        config: AudioConfig,
        bitRateBps: Int = BIT_RATE_PER_CHANNEL_BPS * config.channelCount,
    ) : this(outputFile = outputFile, fileDescriptor = null, config = config, bitRateBps = bitRateBps)

    constructor(
        fileDescriptor: FileDescriptor,
        config: AudioConfig,
        bitRateBps: Int = BIT_RATE_PER_CHANNEL_BPS * config.channelCount,
    ) : this(outputFile = null, fileDescriptor = fileDescriptor, config = config, bitRateBps = bitRateBps)

    constructor(
        target: StreamingExportTarget,
        config: AudioConfig,
        bitRateBps: Int = BIT_RATE_PER_CHANNEL_BPS * config.channelCount,
    ) : this(outputFile = null, fileDescriptor = target.fileDescriptor, config = config, bitRateBps = bitRateBps)

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
    private var recoveredFromAlreadyStoppedMuxer = false

    // Issue #378: every timestamp handed to the muxer goes through this, see its class doc.
    private val timestamps = MuxerTimestampSanitizer(config.sampleRateHz)

    // Issue #378 diagnostics. None of these change behaviour; they exist so that a finish()
    // failure on a device nobody can attach a debugger to still says what the muxer was fed.
    private var csd0PresentAtAddTrack: Boolean? = null
    private var eosFlaggedDataSamples = 0
    private var eosMarkerWritten = false
    private var eosMarkerError: String? = null
    private var finalDrainSamples = 0
    private var inFinalDrain = false

    /** Total PCM bytes (audio + injected silence) fed into the encoder so far. */
    override val totalBytesWritten: Long
        get() = synchronized(lock) { totalBytesFed }

    /** Whether [finish] has completed successfully. */
    override val isSessionFinished: Boolean
        get() = synchronized(lock) { isFinished }

    /** Whether this writer has been closed / released. */
    override val isSessionClosed: Boolean
        get() = synchronized(lock) { isClosed }

    /**
     * Whether `muxer.stop()` threw during [finish] and [finish] still completed because an
     * independent re-read verified the output is a complete, decodable recording (issues #347,
     * #378; see [MuxerStopFailurePolicy]). Exposed so a caller (e.g. `ForwardRecordingEngine`) can
     * log the occurrence for audit purposes even though the session did not fail.
     */
    val recoveredFromMuxerAlreadyStopped: Boolean
        get() = synchronized(lock) { recoveredFromAlreadyStoppedMuxer }

    /** How many encoder timestamps had to be rewritten before reaching the muxer (issue #378). */
    val timestampCorrections: Int
        get() = synchronized(lock) { timestamps.corrections }

    /** Description of the first rewritten timestamp, or null if none was (issue #378). */
    val firstTimestampCorrection: String?
        get() = synchronized(lock) { timestamps.firstCorrection }

    /**
     * Test-only seam (issue #378): when set, every encoded sample that comes out of the encoder
     * while [finish] is draining it after end-of-stream gets this function applied to its
     * presentation timestamp before anything else sees it. Lets an emulator test reproduce an
     * encoder that stamps its final frames earlier than the ones before them, which is the input
     * that makes `MPEG4Writer` mark the track malformed. Nothing in production sets it.
     */
    internal var finalDrainCodecPtsOverrideForTest: ((Long) -> Long)? = null

    /** Test-only (issue #378): samples written to the muxer during [finish]'s post-EOS drain, so a
     * test can prove the override above actually had a sample to act on. */
    internal val finalDrainSamplesWrittenForTest: Int
        get() = synchronized(lock) { finalDrainSamples }

    /** What the muxer was fed, for failure messages and logs (issue #378). */
    fun diagnostics(): String = synchronized(lock) {
        "[muxer input: samples=${timestamps.samples}, firstPtsUs=${timestamps.firstPtsUs}, " +
            "lastPtsUs=${timestamps.lastPtsUs}, ptsCorrections=${timestamps.corrections}" +
            (timestamps.firstCorrection?.let { " (first: $it)" } ?: "") +
            ", eosFlaggedDataSamples=$eosFlaggedDataSamples, eosMarkerWritten=$eosMarkerWritten" +
            (eosMarkerError?.let { ", eosMarkerError=$it" } ?: "") +
            ", csd0PresentAtAddTrack=$csd0PresentAtAddTrack, finalDrainSamples=$finalDrainSamples" +
            ", pcmBytesFed=$totalBytesFed, codec=${runCatching { codec.name }.getOrDefault("?")}]"
    }

    /**
     * Test-only seam (issue #347): when `true`, [finish] calls the real `muxer.stop()` itself,
     * once, immediately before its own explicit stop attempt, so the explicit call throws on a
     * file that is already fully finalized. That exercises the recovery path's acceptance side
     * (an independent re-read proves the file complete). Nothing in production sets this.
     *
     * Issue #378 note: #347 assumed the native muxer auto-stops when it sees
     * `BUFFER_FLAG_END_OF_STREAM` on a data buffer. AOSP's `MPEG4Writer` does not do that (it only
     * acts on EOS for zero-length buffers); the S25 failure matches a malformed track, which is what
     * [finalDrainCodecPtsOverrideForTest] reproduces. This seam does not model that case.
     */
    internal var forceMuxerAlreadyStoppedBeforeExplicitStopForTest: Boolean = false

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
            val muxerInstance = if (fileDescriptor != null) {
                MediaMuxer(fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                MediaMuxer(requireNotNull(outputFile).absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }
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
    override fun write(pcmData: ByteArray, offset: Int, length: Int) {
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
    override fun writePcmChunk(pcmData: ByteArray, offset: Int, length: Int) {
        write(pcmData, offset, length)
    }

    /**
     * Injects wall-clock silence frames for the specified [gapDurationMillis] to preserve timeline
     * alignment when an interruption occurs.
     */
    override fun writeGap(gapDurationMillis: Long) {
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
    override fun writeGap(gap: PauseGap) {
        writeGap(gap.durationMillis)
    }

    /**
     * Injects wall-clock silence frames for [durationMillis].
     */
    override fun writeSilence(durationMillis: Long) {
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
                    val outputFormat = codec.outputFormat
                    // AOSP's MPEG4Writer only takes AAC codec-specific data from the track format
                    // (csd-0 -> ESDS), never from a CODEC_CONFIG sample, and marks the track
                    // malformed at stop() without it. Recorded for diagnostics (issue #378).
                    csd0PresentAtAddTrack = outputFormat.containsKey("csd-0")
                    muxerTrackIndex = muxer.addTrack(outputFormat)
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
                    val isEndOfStream = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        check(muxerStarted) { "encoder produced sample data before the muxer's track was added" }
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        if (isEndOfStream) eosFlaggedDataSamples++
                        // Issue #347 observed that the S25's encoder sets BUFFER_FLAG_END_OF_STREAM
                        // on the buffer holding its last real frame. MediaMuxer only gives that flag
                        // meaning on an empty marker buffer, so it is stripped from real data here
                        // and sent on its own marker below. `isEndOfStream` (captured above) still
                        // drives this loop's exit.
                        //
                        // Issue #378: the timestamp is not passed through blindly either. A sample
                        // whose pts does not advance makes MPEG4Writer mark the whole track
                        // malformed, which only surfaces at stop() as "muxer would have stopped
                        // already" and leaves a file with an empty sample table. See
                        // MuxerTimestampSanitizer for why rewriting it is exact for AAC-LC.
                        var codecPtsUs = bufferInfo.presentationTimeUs
                        if (inFinalDrain) {
                            finalDrainCodecPtsOverrideForTest?.let { codecPtsUs = it(codecPtsUs) }
                        }
                        val ptsUs = timestamps.next(codecPtsUs, isEndOfStream)
                        bufferInfo.set(
                            bufferInfo.offset,
                            bufferInfo.size,
                            ptsUs,
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv(),
                        )
                        muxer.writeSampleData(muxerTrackIndex, outputBuffer, bufferInfo)
                        if (inFinalDrain) finalDrainSamples++
                    }
                    if (isEndOfStream && muxerStarted && timestamps.samples > 0) {
                        // Empty end-of-stream marker, sent whether the encoder put EOS on its last
                        // real frame (the S25) or on a separate empty buffer (the emulator's
                        // software encoder), so both take the same muxer path. Its timestamp is one
                        // frame after the last sample, which MPEG4Writer turns into that sample's
                        // duration. A failure here is recorded, not swallowed: it means the track
                        // was already stopped, and stop() will then fail with this in its message.
                        val markerInfo = MediaCodec.BufferInfo()
                        markerInfo.set(0, 0, timestamps.endOfStreamMarkerPtsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        try {
                            muxer.writeSampleData(muxerTrackIndex, java.nio.ByteBuffer.allocate(0), markerInfo)
                            eosMarkerWritten = true
                        } catch (e: IllegalStateException) {
                            eosMarkerError = "${e.javaClass.simpleName}: ${e.message}"
                        } catch (e: IllegalArgumentException) {
                            eosMarkerError = "${e.javaClass.simpleName}: ${e.message}"
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (isEndOfStream) {
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
    override fun finish() {
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
                        var queuedSize = 0
                        if (totalBytesFed == 0L) {
                            val inputBuffer = requireNotNull(codec.getInputBuffer(inputIndex))
                            inputBuffer.clear()
                            val silenceBytes = config.bytesPerFrame * 1024
                            queuedSize = minOf(inputBuffer.remaining(), silenceBytes)
                            inputBuffer.put(ByteArray(queuedSize))
                            totalBytesFed += queuedSize
                        }
                        val presentationTimeUs = (totalBytesFed * MICROS_PER_SECOND) / config.bytesPerSecond
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            queuedSize,
                            presentationTimeUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        eosQueued = true
                    } else {
                        drainOutput(endOfStream = false, deadlineNanos = deadlineNanos)
                    }
                }

                inFinalDrain = true
                try {
                    drainOutput(endOfStream = true, deadlineNanos = deadlineNanos)
                } finally {
                    inFinalDrain = false
                }

                if (forceMuxerAlreadyStoppedBeforeExplicitStopForTest && muxerStarted) {
                    muxer.stop()
                }

                if (muxerStarted) {
                    try {
                        muxer.stop()
                    } catch (e: IllegalStateException) {
                        // Issues #347/#357/#378: this message covers every non-OK status from the
                        // native stop(), including a malformed track whose moov was written with
                        // an empty sample table. Only an independent re-read that finds a
                        // readable, complete audio track may turn this into success.
                        muxerStarted = false
                        val minimumDurationUs = if (timestamps.samples > 0) {
                            timestamps.lastPtsUs - timestamps.firstPtsUs
                        } else {
                            0L
                        }
                        val failure = MuxerStopFailurePolicy.resolve(
                            stopError = e,
                            probe = Mp4OutputProbe.probe(outputFile, fileDescriptor),
                            minimumDurationUs = minimumDurationUs,
                            diagnostics = diagnostics(),
                        )
                        if (failure != null) throw failure
                        recoveredFromAlreadyStoppedMuxer = true
                    }
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
