package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.settings.SaveAudioFormat
import java.io.File

data class LiveEncoding(
    val format: SaveAudioFormat,
    val mimeType: String,
    val extension: String,
    val createWriter: (StreamingExportTarget, AudioConfig) -> StreamingAudioWriter,
)

/** Shared production selection seam: each engine calls its provider once per session. */
class RecordingFormatSelector(
    private val format: () -> SaveAudioFormat,
    tempDir: File,
) {
    private val aac = AacPayloadEncoder(tempDir)
    private val opus = OpusPayloadEncoder(tempDir)

    fun saveEncoder(): PayloadEncoder = when (format()) {
        SaveAudioFormat.AAC -> aac
        SaveAudioFormat.OPUS -> opus
    }

    fun liveEncoding(): LiveEncoding = when (format()) {
        SaveAudioFormat.AAC -> LiveEncoding(SaveAudioFormat.AAC, StreamingAdtsWriter.MIME_TYPE, StreamingAdtsWriter.FILE_EXTENSION,
            ::StreamingAdtsWriter)
        SaveAudioFormat.OPUS -> LiveEncoding(SaveAudioFormat.OPUS, StreamingOpusWriter.MIME_TYPE, StreamingOpusWriter.FILE_EXTENSION,
            { target, config -> StreamingOpusWriter(target.fileDescriptor, config) })
    }
}
