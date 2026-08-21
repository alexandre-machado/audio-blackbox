package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import java.io.OutputStream

/**
 * [PayloadEncoder] wrapping [WavWriter] -- lossless, uncompressed, pure JVM. Not wired into
 * [cc.machado.audioblackbox.service.RecorderService]'s production [ExportEngine] instance (that
 * default is [AacPayloadEncoder] as of issue #32); kept ready for a future user-facing "lossless"
 * setting (`.m4a` default, `.wav` opt-in), per issue #32's stated resolution of the
 * compressed-vs-lossless tension. [WavWriter] and its byte-exact header tests remain untouched.
 */
object WavPayloadEncoder : PayloadEncoder {
    override val mimeType: String = "audio/wav"
    override val fileExtension: String = "wav"

    override fun encode(config: AudioConfig, payload: ByteArray, out: OutputStream, isCancelled: () -> Boolean) {
        WavWriter.writeHeader(out, config, payload.size)
        var offset = 0
        while (offset < payload.size) {
            if (isCancelled()) return
            val length = minOf(CHUNK_BYTES, payload.size - offset)
            out.write(payload, offset, length)
            offset += length
        }
    }

    private const val CHUNK_BYTES = 64 * 1024
}
