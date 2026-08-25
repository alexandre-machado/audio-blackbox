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

    override fun encode(
        config: AudioConfig,
        totalPayloadBytes: Long,
        chunks: PayloadChunkSource,
        out: OutputStream,
        isCancelled: () -> Boolean,
    ) {
        require(totalPayloadBytes in 0..Int.MAX_VALUE.toLong()) {
            "totalPayloadBytes must fit a WAV data subchunk (Int), was $totalPayloadBytes"
        }
        WavWriter.writeHeader(out, config, totalPayloadBytes.toInt())
        while (true) {
            if (isCancelled()) return
            val chunk = chunks.nextChunk() ?: break
            out.write(chunk)
        }
    }
}
