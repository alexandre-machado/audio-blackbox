package cc.machado.audioblackbox.export

import android.os.ParcelFileDescriptor
import cc.machado.audioblackbox.audio.AudioConfig

/** Live AAC: each completed frame is readable immediately, without a container finalization.
 * Owns a duplicate descriptor; the MediaStore target retains its descriptor for final metadata.
 * All encoding and I/O happens on ForwardRecordingEngine's drain thread, never capture.
 */
class StreamingAdtsWriter(target: StreamingExportTarget, private val config: AudioConfig) : StreamingAudioWriter {
    private val lock = Any()
    private val framer = AdtsFramer(config.sampleRateHz, config.channelCount)
    private val output = ParcelFileDescriptor.AutoCloseOutputStream(
        ParcelFileDescriptor.dup(target.fileDescriptor),
    )
    private val encoder: AacEncoderPump
    private val silence = ByteArray(4096)
    private var finished = false
    private var closed = false

    init {
        try {
            encoder = AacEncoderPump(config, StreamingAacWriter.BIT_RATE_PER_CHANNEL_BPS * config.channelCount,
                onFormat = {},
                onSample = { buffer, _, _ -> framer.write(output, buffer) },
            )
        } catch (t: Throwable) {
            runCatching { output.close() }
            throw t
        }
    }

    override val totalBytesWritten: Long get() = synchronized(lock) { encoder.totalBytesFed }
    override val isSessionFinished: Boolean get() = synchronized(lock) { finished }
    override val isSessionClosed: Boolean get() = synchronized(lock) { closed }

    override fun write(pcmData: ByteArray, offset: Int, length: Int) = synchronized(lock) {
        require(offset >= 0 && length >= 0 && offset <= pcmData.size - length)
        check(!closed && !finished)
        try {
            encoder.write(pcmData, offset, length)
        } catch (t: Throwable) {
            try {
                close()
            } catch (closeError: Throwable) {
                if (closeError !== t) t.addSuppressed(closeError)
            }
            throw t
        }
    }

    override fun writeGap(gapDurationMillis: Long) = synchronized(lock) {
        require(gapDurationMillis >= 0)
        check(!closed && !finished)
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
            encoder.finish()
            output.flush()
            encoder.stop()
            finished = true
        } finally {
            close()
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        encoder.close()
        output.close()
    }

    companion object {
        // Match MediaProvider's .aac extension mapping, including after a metadata scan.
        const val MIME_TYPE = "audio/aac-adts"
        const val FILE_EXTENSION = "aac"

        fun accepts(mimeType: String, displayName: String): Boolean =
            mimeType.equals(MIME_TYPE, ignoreCase = true) ||
                mimeType.equals("audio/aac", ignoreCase = true) ||
                displayName.endsWith(".aac", ignoreCase = true)
    }
}
