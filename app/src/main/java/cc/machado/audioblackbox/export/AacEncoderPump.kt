package cc.machado.audioblackbox.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import cc.machado.audioblackbox.audio.AudioConfig
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer

/** Shared AAC-LC encoder pump. Called only on a writer's serialized drain thread.
 * Container callbacks borrow output buffers until they return; CSD is never a data frame.
 */
internal class AacEncoderPump(
    private val config: AudioConfig,
    bitRateBps: Int,
    private val onFormat: (MediaFormat) -> Unit,
    private val onSample: (ByteBuffer, MediaCodec.BufferInfo, Boolean) -> Unit,
    private val onEnd: () -> Unit = {},
) : Closeable {
    private val codec: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()
    var totalBytesFed = 0L
        private set
    private var closed = false
    private var stopped = false
    val name: String

    init {
        require(config.sampleRateHz > 0 && config.channelCount > 0 && bitRateBps > 0)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
            config.sampleRateHz, config.channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRateBps)
        }
        val created = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
            codec = created
            name = runCatching { created.name }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching { created.codecInfo.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "?"
        } catch (t: Throwable) {
            runCatching { created.stop() }
            runCatching { created.release() }
            throw t
        }
    }

    fun write(pcmData: ByteArray, offset: Int, length: Int) {
        check(!closed)
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

    fun finish() {
        check(!closed)
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
        drainOutput(true, deadlineNanos)
    }

    private fun drainOutput(endOfStream: Boolean, deadlineNanos: Long) {
        while (true) {
            if (System.nanoTime() > deadlineNanos) throw IOException("AAC encode drain exceeded deadline")
            when (val index = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0L)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                else -> if (index >= 0) {
                    val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    try {
                        if (bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val buffer = requireNotNull(codec.getOutputBuffer(index))
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            onSample(buffer, bufferInfo, eos)
                        }
                        if (eos) onEnd()
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                    if (eos) return
                }
            }
        }
    }

    /** Successful finalization reports codec stop failures; cancellation still releases best-effort. */
    fun stop() {
        if (stopped) return
        codec.stop()
        stopped = true
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { stop() }
        runCatching { codec.release() }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val OP_TIMEOUT_MILLIS = 30_000L
        private const val FINISH_DEADLINE_MILLIS = 60_000L
    }
}

