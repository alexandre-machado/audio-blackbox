package cc.machado.audioblackbox.export

import android.media.MediaCodec
import android.media.MediaMuxer
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.PauseGap
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor

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

    private val encoder: AacEncoderPump
    private val muxer: MediaMuxer
    private var muxerStarted = false
    private var muxerTrackIndex = -1

    private val zeroBuffer = ByteArray(ZERO_BUFFER_SIZE)

    private val totalBytesFed: Long get() = encoder.totalBytesFed
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
    private var overriddenSamples = 0
    private var codecName: String = UNKNOWN_CODEC_NAME

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
     * #378; see [MuxerStopFailurePolicy]). Exposed so an MP4 caller can
     * log the occurrence for audit purposes even though the session did not fail.
     */
    val recoveredFromMuxerAlreadyStopped: Boolean
        get() = synchronized(lock) { recoveredFromAlreadyStoppedMuxer }

    /** How many encoder timestamps had to be rewritten before reaching the muxer (issue #378). */
    /** Encoded AAC frames handed to the muxer as data samples (the end-of-stream marker excluded). */
    internal val muxedFrameCount: Int
        get() = synchronized(lock) { timestamps.samples }

    val timestampCorrections: Int
        get() = synchronized(lock) { timestamps.corrections }

    /**
     * The encoder's component name (e.g. `c2.android.aac.encoder`), captured once the codec is
     * configured and started. It identifies the S25's encoder in the `MUXER_TIMESTAMP_CORRECTED`
     * audit entry (issue #378). Captured at construction, not read from the codec later, because
     * `diagnostics()` is also called after [finish] has released the codec.
     */
    val encoderName: String
        get() = synchronized(lock) { codecName }

    /** Description of the first rewritten timestamp, or null if none was (issue #378). */
    val firstTimestampCorrection: String?
        get() = synchronized(lock) { timestamps.firstCorrection }

    /**
     * Test-only seam (issue #378): while set, every encoded sample the encoder hands out gets this
     * function applied to its presentation timestamp before anything else sees it. A test sets it
     * part-way through a session to reproduce an encoder whose later frames are stamped earlier
     * than the ones before them, the input that makes `MPEG4Writer` mark the track malformed.
     * Nothing in production sets it.
     *
     * Deliberately not tied to "frames after end-of-stream": CI's software encoder emits no frame
     * at all after EOS (observed in run 36063434655: `finalDrainSamples=0`), so such a seam would
     * have nothing to act on there.
     */
    internal var codecPtsOverrideForTest: ((Long) -> Long)? = null

    /** Test-only (issue #378): samples whose timestamp [codecPtsOverrideForTest] rewrote, so a test
     * can prove the override actually had a sample to act on. */
    internal val overriddenSamplesForTest: Int
        get() = synchronized(lock) { overriddenSamples }

    /** What the muxer was fed, for failure messages and logs (issue #378). */
    fun diagnostics(): String = synchronized(lock) {
        "[muxer input: samples=${timestamps.samples}, firstPtsUs=${timestamps.firstPtsUs}, " +
            "lastPtsUs=${timestamps.lastPtsUs}, ptsCorrections=${timestamps.corrections}" +
            (timestamps.firstCorrection?.let { " (first: $it)" } ?: "") +
            ", eosFlaggedDataSamples=$eosFlaggedDataSamples, eosMarkerWritten=$eosMarkerWritten" +
            (eosMarkerError?.let { ", eosMarkerError=$it" } ?: "") +
            ", csd0PresentAtAddTrack=$csd0PresentAtAddTrack, finalDrainSamples=$finalDrainSamples" +
            ", pcmBytesFed=$totalBytesFed, codec=$codecName]"
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
     * [codecPtsOverrideForTest] reproduces. This seam does not model that case.
     */
    internal var forceMuxerAlreadyStoppedBeforeExplicitStopForTest: Boolean = false

    init {
        require(config.sampleRateHz > 0) { "sampleRateHz must be positive, was ${config.sampleRateHz}" }
        require(config.channelCount > 0) { "channelCount must be positive, was ${config.channelCount}" }
        require(bitRateBps > 0) { "bitRateBps must be positive, was $bitRateBps" }

        val createdMuxer = if (fileDescriptor != null) {
            MediaMuxer(fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            MediaMuxer(requireNotNull(outputFile).absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
        muxer = createdMuxer
        try {
            encoder = AacEncoderPump(config, bitRateBps,
                onFormat = { format ->
                    check(!muxerStarted) { "MediaCodec changed output format more than once" }
                    val outputFormat = format
                    // AOSP's MPEG4Writer only takes AAC codec-specific data from the track format
                    // (csd-0 -> ESDS), never from a CODEC_CONFIG sample, and marks the track
                    // malformed at stop() without it. Recorded for diagnostics (issue #378).
                    csd0PresentAtAddTrack = outputFormat.containsKey("csd-0")
                    muxerTrackIndex = muxer.addTrack(outputFormat)
                    muxer.start()
                    muxerStarted = true
                },
                onSample = { outputBuffer, bufferInfo, isEndOfStream ->
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
                    codecPtsOverrideForTest?.let {
                        codecPtsUs = it(codecPtsUs)
                        overriddenSamples++
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
                },
                onEnd = {
                    if (muxerStarted && timestamps.samples > 0) {
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
                },
            )
            codecName = encoder.name
        } catch (t: Throwable) {
            runCatching { createdMuxer.release() }
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

            try {
                encoder.write(pcmData, offset, length)
            } catch (t: Throwable) {
                close()
                throw t
            }
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
                inFinalDrain = true
                try {
                    encoder.finish()
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
                        val writtenSpanUs = if (timestamps.samples > 0) {
                            timestamps.lastPtsUs - timestamps.firstPtsUs
                        } else {
                            0L
                        }
                        val failure = MuxerStopFailurePolicy.resolve(
                            stopError = e,
                            probe = Mp4OutputProbe.probe(outputFile, fileDescriptor),
                            writtenSpanUs = writtenSpanUs,
                            frameDurationUs = timestamps.frameDurationUs,
                            diagnostics = diagnostics(),
                        )
                        if (failure != null) throw failure
                        recoveredFromAlreadyStoppedMuxer = true
                    }
                    muxerStarted = false
                }
                encoder.stop()
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
        if (isClosed) return
        isClosed = true
        encoder.close()
        if (muxerStarted) {
            runCatching { muxer.stop() }
            muxerStarted = false
        }
        runCatching { muxer.release() }
    }

    companion object {
        const val MIME_TYPE_M4A = "audio/mp4"
        const val FILE_EXTENSION = "m4a"

        private const val ZERO_BUFFER_SIZE = 4096

        /** Placeholder for [encoderName] when the platform reports no codec name at all. */
        internal const val UNKNOWN_CODEC_NAME = "?"

        /** Default ~64 kbps per audio channel for AAC-LC. */
        const val BIT_RATE_PER_CHANNEL_BPS = 64_000
    }
}

/** Alias for [StreamingAacWriter]. */
typealias StreamingAacEncoder = StreamingAacWriter
