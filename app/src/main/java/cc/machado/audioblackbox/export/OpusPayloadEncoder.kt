package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/** Bounded lookback encode; the existing ExportEngine transaction owns commit/floor/abort. */
class OpusPayloadEncoder(private val tempDir: File) : PayloadEncoder {
    override val mimeType = StreamingOpusWriter.MIME_TYPE
    override val fileExtension = StreamingOpusWriter.FILE_EXTENSION

    override fun encode(config: AudioConfig, totalPayloadBytes: Long, chunks: PayloadChunkSource,
        out: OutputStream, isCancelled: () -> Boolean) {
        val file = File.createTempFile("blackbox-opus-", ".ogg", tempDir)
        try {
            RandomAccessFile(file, "rw").use { output ->
                StreamingOpusWriter(output.fd, config).use { writer ->
                    var count = 0L
                    while (true) {
                        if (isCancelled()) return
                        val chunk = chunks.nextChunk() ?: break
                        writer.write(chunk)
                        count += chunk.size
                    }
                    check(count == totalPayloadBytes) { "Incomplete Opus PCM payload" }
                    if (isCancelled()) return
                    writer.finish()
                }
            }
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (isCancelled()) return
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
            }
        } finally { file.delete() }
    }
}
