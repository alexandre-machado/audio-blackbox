package cc.machado.audioblackbox.export

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** RFC 7845 provider. Called only by the export/drain worker; does not own [output].
 * Keep one page, normally 40 ms (or 16 packets). Emit it before the next packet,
 * retaining the final page for EOS without seeking. A single long packet may exceed
 * 40 ms (Opus permits 120 ms). Headers are emitted immediately and independently.
 * The 40 ms grouping gives the first page two usual 20 ms packets, leaving useful
 * audio beyond the encoder's usual 312-sample pre-skip even in a one-page prefix.
 * Granules count decoded 48 kHz samples INCLUDING encoder delay, starting at zero:
 * pre-skip is subtracted by the decoder, never added again to the packet durations.
 */
internal class OggOpusWriter(
    private val output: OutputStream,
    channels: Int,
    inputSampleRate: Int,
    preSkip: Int,
    serial: Int = Random.nextInt(),
) {
    private val pages = OggPageWriter(output, serial)
    private var samples = 0L
    private var pageSamples = 0
    private var packets = 0
    private var finished = false

    init {
        require(channels in 1..2 && preSkip in 0..65535 && inputSampleRate > 0)
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray(Charsets.US_ASCII)).put(1).put(channels.toByte())
            .putShort(preSkip.toShort()).putInt(inputSampleRate).putShort(0).put(0).array()
        pages.packet(head, 0)
        pages.flush(2)
        val vendor = "audio-blackbox".toByteArray(Charsets.UTF_8)
        val tags = ByteBuffer.allocate(16 + vendor.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusTags".toByteArray(Charsets.US_ASCII)).putInt(vendor.size).put(vendor)
            .putInt(0).array()
        pages.packet(tags, 0)
        pages.flush()
    }

    fun writePacket(packet: ByteArray) {
        check(!finished)
        val duration = OpusPacket.samples(packet)
        if (pageSamples >= 1920 || packets >= 16) {
            pages.flush()
            pageSamples = 0
            packets = 0
        }
        samples += duration
        pages.packet(packet, samples)
        pageSamples += duration
        packets++
    }

    fun finish() {
        if (finished) return
        pages.flush(4)
        output.flush()
        finished = true
    }
}

/** RFC 3533 framing, including the zero terminator for exact multiples of 255.
 * One fixed maximum-size page buffer; emit each page in one OutputStream.write call.
 */
internal class OggPageWriter(private val output: OutputStream, private val serial: Int) {
    private val page = ByteArray(27 + 255 + 65025)
    private var segments = 0
    private var bytes = 0
    private var sequence = 0
    private var continued = false
    private var granule = -1L

    fun packet(packet: ByteArray, endGranule: Long) {
        var offset = 0
        do {
            if (segments == 255) {
                flush()
                continued = offset > 0
            }
            val size = minOf(255, packet.size - offset)
            page[27 + segments++] = size.toByte()
            packet.copyInto(page, 282 + bytes, offset, offset + size)
            bytes += size
            offset += size
            if (size < 255) {
                granule = endGranule
                break
            }
        } while (true)
    }

    fun flush(flags: Int = 0) {
        if (segments == 0 && flags == 0) return
        page.fill(0, 0, 27)
        "OggS".toByteArray(Charsets.US_ASCII).copyInto(page)
        page[5] = (flags or if (continued) 1 else 0).toByte()
        val header = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
        header.putLong(6, granule).putInt(14, serial).putInt(18, sequence++)
        page[26] = segments.toByte()
        page.copyInto(page, 27 + segments, 282, 282 + bytes)
        val length = 27 + segments + bytes
        header.putInt(22, crc(page, length))
        output.write(page, 0, length)
        segments = 0
        bytes = 0
        granule = -1
        continued = false
    }

    companion object {
        fun crc(bytes: ByteArray, length: Int = bytes.size): Int {
            var crc = 0
            for (i in 0 until length) {
                crc = crc xor ((bytes[i].toInt() and 255) shl 24)
                repeat(8) { crc = (crc shl 1) xor if (crc < 0) 0x04C11DB7 else 0 }
            }
            return crc
        }
    }
}

internal object OpusPacket {
    /** RFC 6716 section 3.1: SILK, hybrid and CELT frame sizes, in 48 kHz samples. */
    fun samples(packet: ByteArray): Int {
        require(packet.isNotEmpty()) { "Empty Opus packet" }
        val toc = packet[0].toInt() and 255
        val config = toc ushr 3
        val frame = when {
            config >= 16 -> 120 shl (config and 3)
            config >= 12 -> 480 shl (config and 1)
            config and 3 == 3 -> 2880
            else -> 480 shl (config and 3)
        }
        val count = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> {
                require(packet.size >= 2)
                packet[1].toInt() and 63
            }
        }
        require(count > 0 && frame * count <= 5760) { "Invalid Opus duration" }
        return frame * count
    }

    /** Android CSD-1 stores codec delay in native-endian nanoseconds. */
    fun preSkip(head: ByteBuffer?, delay: ByteBuffer?): Int {
        val h = head?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN)
        if (h != null && h.remaining() >= 19) {
            val signature = ByteArray(8).also { h.get(it) }
            if (signature.contentEquals("OpusHead".toByteArray(Charsets.US_ASCII))) {
                h.get() // version
                h.get() // channels
                return h.short.toInt() and 65535
            }
        }
        val d = requireNotNull(delay) { "Opus encoder omitted delay CSD" }
            .duplicate().order(ByteOrder.nativeOrder())
        require(d.remaining() >= 8)
        val nanos = d.long
        require(nanos in 0..1_365_312_500L)
        return (nanos * 48_000 / 1_000_000_000L).toInt()
    }
}
