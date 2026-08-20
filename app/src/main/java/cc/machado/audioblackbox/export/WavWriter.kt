package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import java.io.OutputStream

/**
 * Writes a canonical 44-byte RIFF/WAVE PCM header into a plain [OutputStream]. Pure JVM -- no
 * Android dependency -- so it is unit-testable without a device (issue #5).
 *
 * Every field is derived from [AudioConfig] and the actual payload length passed in, never
 * hardcoded: a 44.1 kHz stereo capture must produce a header that matches that config, not a
 * copy of the 16 kHz mono default's numbers. Both chunk sizes ([RIFF] `ChunkSize` and `data`
 * `Subchunk2Size`) are computed from [payloadBytes], the real size of the PCM about to follow --
 * never assumed from the buffer's capacity -- so a header written before the payload is fully
 * sized would be a bug this class cannot hide.
 */
object WavWriter {

    /** Size in bytes of the header this class writes -- 12 (RIFF chunk descriptor) + 24 (`fmt `
     * subchunk) + 8 (`data` subchunk header, sample bytes excluded). */
    const val HEADER_SIZE_BYTES = 44

    private const val PCM_SUBCHUNK1_SIZE = 16
    private const val AUDIO_FORMAT_PCM = 1

    /** Writes just the 44-byte header, sized for [payloadBytes] of PCM data at [config]. Callers
     * that stream the payload separately (e.g. in chunks) call this once, first. */
    fun writeHeader(out: OutputStream, config: AudioConfig, payloadBytes: Int) {
        require(payloadBytes >= 0) { "payloadBytes must not be negative, was $payloadBytes" }

        val byteRate = config.bytesPerSecond
        val blockAlign = config.bytesPerFrame
        val bitsPerSample = config.encoding.bitsPerSample
        val riffChunkSize = HEADER_SIZE_BYTES - 8 + payloadBytes // 36 + payloadBytes

        out.writeAscii("RIFF")
        out.writeLeInt(riffChunkSize)
        out.writeAscii("WAVE")

        out.writeAscii("fmt ")
        out.writeLeInt(PCM_SUBCHUNK1_SIZE)
        out.writeLeShort(AUDIO_FORMAT_PCM)
        out.writeLeShort(config.channelCount)
        out.writeLeInt(config.sampleRateHz)
        out.writeLeInt(byteRate)
        out.writeLeShort(blockAlign)
        out.writeLeShort(bitsPerSample)

        out.writeAscii("data")
        out.writeLeInt(payloadBytes)
    }

    /** Writes the header followed by [payload] in one call -- the common case for tests and any
     * caller that already has the full payload in memory. */
    fun write(out: OutputStream, config: AudioConfig, payload: ByteArray) {
        writeHeader(out, config, payload.size)
        out.write(payload)
    }

    private fun OutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))

    private fun OutputStream.writeLeInt(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun OutputStream.writeLeShort(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }
}
