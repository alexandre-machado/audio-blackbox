package cc.machado.audioblackbox.export

import java.io.OutputStream
import java.nio.ByteBuffer

/** MPEG-4 AAC-LC, one raw data block per frame, no CRC. No buffering across frames. */
internal class AdtsFramer(sampleRateHz: Int, private val channels: Int) {
    private val frame = ByteArray(8191)
    private val frequencyIndex = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
    ).indexOf(sampleRateHz)

    init {
        require(frequencyIndex >= 0) { "Unsupported ADTS sample rate: $sampleRateHz" }
        require(channels in 1..2) { "Unsupported ADTS channel configuration: $channels" }
    }

    fun write(output: OutputStream, payload: ByteBuffer) {
        val frameLength = 7 + payload.remaining()
        require(frameLength in 8..8191) { "Invalid ADTS frame length: $frameLength" }
        frame[0] = 0xff.toByte()
        frame[1] = 0xf1.toByte()
        frame[2] = ((1 shl 6) or (frequencyIndex shl 2) or (channels shr 2)).toByte()
        frame[3] = (((channels and 3) shl 6) or (frameLength shr 11)).toByte()
        frame[4] = (frameLength shr 3).toByte()
        frame[5] = (((frameLength and 7) shl 5) or 0x1f).toByte()
        frame[6] = 0xfc.toByte()
        payload.get(frame, 7, frameLength - 7)
        output.write(frame, 0, frameLength)
        output.flush()
    }
}
