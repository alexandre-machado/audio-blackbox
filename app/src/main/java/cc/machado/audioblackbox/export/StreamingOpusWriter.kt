package cc.machado.audioblackbox.export

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import cc.machado.audioblackbox.audio.AudioConfig
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object OpusEncoding {
    fun outputRate(config: AudioConfig): Int = if (config.sampleRateHz == 16_000) 16_000 else 48_000
    fun bitrate(config: AudioConfig): Int = when (config.sampleRateHz) {
        16_000 -> 24_000
        32_000 -> 32_000
        44_100 -> 96_000
        else -> error("Unsupported Opus source rate: ${config.sampleRateHz}")
    }
    fun format(config: AudioConfig): MediaFormat =
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, outputRate(config), config.channelCount)
            .apply { setInteger(MediaFormat.KEY_BIT_RATE, bitrate(config)) }
    fun encoderName(config: AudioConfig): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format(config))
    fun available(config: AudioConfig): Boolean = runCatching { encoderName(config) != null }.getOrDefault(false)
}

/** One serialized export-worker session; the caller owns the descriptor and aborts on failure.
 * Ogg is offered for playback only after finish. Closing cancels without claiming success.
 */
class StreamingOpusWriter(descriptor: FileDescriptor, private val config: AudioConfig) : StreamingAudioWriter {
    private val lock = Any()
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private val rate = OpusEncoding.outputRate(config)
    private val silence = ByteArray(4096)
    private val resampler = if (rate != config.sampleRateHz) {
        OpusResampler(config.sampleRateHz, config.channelCount) { bytes, count -> feed(bytes, 0, count) }
    } else null
    private var fedBytes = 0L
    private var sourceBytes = 0L
    private var track = -1
    private var muxerStarted = false
    private var finished = false
    private var closed = false

    init {
        val name = OpusEncoding.encoderName(config) ?: throw IOException("Opus unavailable on this device.")
        val created = MediaCodec.createByCodecName(name)
        var createdMuxer: MediaMuxer? = null
        try {
            created.configure(OpusEncoding.format(config), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
            createdMuxer = MediaMuxer(descriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            codec = created
            muxer = createdMuxer
        } catch (t: Throwable) {
            runCatching { created.stop() }
            runCatching { created.release() }
            runCatching { createdMuxer?.release() }
            throw t
        }
    }

    override val totalBytesWritten: Long get() = synchronized(lock) { sourceBytes }
    override val isSessionFinished: Boolean get() = synchronized(lock) { finished }
    override val isSessionClosed: Boolean get() = synchronized(lock) { closed }

    override fun write(pcmData: ByteArray, offset: Int, length: Int) = synchronized(lock) {
        check(!closed && !finished)
        require(offset >= 0 && length >= 0 && offset <= pcmData.size - length)
        if (resampler == null) {
            require(length % config.bytesPerFrame == 0)
            feed(pcmData, offset, length)
        } else resampler.write(pcmData, offset, length)
        sourceBytes += length
    }

    private fun feed(bytes: ByteArray, offset: Int, length: Int) {
        var position = offset
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (position < offset + length) {
            checkDeadline(deadline)
            drain(false, deadline)
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val input = checkNotNull(codec.getInputBuffer(index)).apply { clear() }
                val available = minOf(input.remaining(), offset + length - position)
                val count = available - available % config.bytesPerFrame
                check(count > 0) { "Opus input buffer cannot hold a PCM frame" }
                input.put(bytes, position, count)
                codec.queueInputBuffer(index, 0, count, timestamp(), 0)
                fedBytes += count
                position += count
            }
        }
        drain(false, deadline)
    }

    private fun timestamp(): Long = fedBytes * 1_000_000L / (rate * config.bytesPerFrame)

    private fun drain(untilEos: Boolean, deadline: Long) {
        while (true) {
            checkDeadline(deadline)
            when (val index = codec.dequeueOutputBuffer(info, if (untilEos) 10_000L else 0L)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted)
                    // Preserve all codec-supplied CSD (OpusHead, pre-skip and seek preroll).
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return
                else -> if (index >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            check(muxerStarted)
                            muxer.writeSampleData(track, checkNotNull(codec.getOutputBuffer(index)), info)
                        }
                    } finally { codec.releaseOutputBuffer(index, false) }
                    if (eos) return
                }
            }
        }
    }

    override fun writeGap(gapDurationMillis: Long) = synchronized(lock) {
        require(gapDurationMillis >= 0)
        val bytes = gapDurationMillis * config.bytesPerSecond / 1000L
        var remaining = bytes - bytes % config.bytesPerFrame
        while (remaining > 0) {
            val count = minOf(remaining, silence.size.toLong()).toInt()
            write(silence, 0, count)
            remaining -= count
        }
    }

    override fun finish() = synchronized(lock) {
        if (finished) return
        check(!closed)
        try {
            resampler?.finish()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (true) {
                checkDeadline(deadline)
                drain(false, deadline)
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, timestamp(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
            }
            drain(true, deadline)
            check(muxerStarted) { "Opus encoder produced no track" }
            muxer.stop()
            muxerStarted = false
            codec.stop()
            finished = true
        } finally { close() }
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        Unit
    }

    private fun checkDeadline(deadline: Long) {
        if (System.nanoTime() >= deadline) throw IOException("Opus encoder exceeded deadline")
    }

    companion object {
        const val MIME_TYPE = "audio/ogg"
        const val FILE_EXTENSION = "ogg"
        fun accepts(mime: String, name: String): Boolean =
            mime.lowercase() in setOf("audio/ogg", "application/ogg", "audio/opus", "audio/x-ogg") ||
                name.endsWith(".ogg", true) || name.endsWith(".opus", true)
    }
}
