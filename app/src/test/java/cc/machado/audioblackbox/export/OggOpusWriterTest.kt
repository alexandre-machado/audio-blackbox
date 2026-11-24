package cc.machado.audioblackbox.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class OggOpusWriterTest {
    private fun hex(value: String) = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    /** Oracle: fixed Android marker bytes encode stereo and 6,510,417ns => 313 samples,
     * overriding the embedded 312; preroll (80ms) must never become pre-skip. */
    @Test fun codec2AndBareGoldenHeaders() {
        val bare = hex("4f 70 75 73 48 65 61 64 01 02 38 01 80 bb 00 00 00 00 00")
        val hdr = "AOPUSHDR".toByteArray() + hex("13 00 00 00 00 00 00 00") + bare
        val delay = "AOPUSDLY".toByteArray() + hex("08 00 00 00 00 00 00 00 51 57 63 00 00 00 00 00")
        val preroll = "AOPUSPRL".toByteArray() + hex("08 00 00 00 00 00 00 00 00 b4 c4 04 00 00 00 00")
        assertEquals(OpusPacket.Header(2, 312), OpusPacket.header(ByteBuffer.wrap(bare)))
        assertEquals(OpusPacket.Header(2, 313), OpusPacket.header(ByteBuffer.wrap(hdr + delay + preroll)))
        assertEquals(OpusPacket.Header(2, 313), OpusPacket.header(
            ByteBuffer.wrap(hdr), ByteBuffer.wrap(delay), ByteBuffer.wrap(preroll)))
        assertEquals(OpusPacket.Header(2, 313), OpusPacket.header(null, null, null, ByteBuffer.wrap(hdr + delay)))
        assertNull(OpusPacket.header(ByteBuffer.wrap(hdr.copyOf(20))))
        assertEquals(OpusPacket.Header(2, 312), OpusPacket.header(ByteBuffer.wrap(hdr.copyOf(20)), ByteBuffer.wrap(bare)))
    }

    /** Oracle: EOS removes 648 samples of padding; prior page granules and CRC stay valid. */
    @Test fun finalGranuleTrimsEncoderPadding() {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out, 1, 48000, 312, 1)
        repeat(5) { writer.writePacket(byteArrayOf(0x98.toByte())) }
        writer.finish(3840)
        val parsed = OggTestParser.parse(out.toByteArray())
        assertEquals(listOf(0L, 0L, 1920L, 3840L, 4152L), parsed.pages.map { it.granule })
        assertEquals(4, parsed.pages.last().flags)
    }

    /** Oracle: CRC-32/OGG (init=0, no reflection/xorout) check value for ASCII 123456789. */
    @Test fun crcKnownAnswer() {
        assertEquals(0x89A1897FL.toInt(), OggPageWriter.crc("123456789".toByteArray()))
    }

    /** Oracle: exact packet bytes, terminator laces, continuation and unknown granules. */
    @Test fun lacingEdgesAndSpanning() {
        for (size in listOf(0, 254, 255, 510, 65025, 65026, 130050)) {
            val out = ByteArrayOutputStream()
            val writer = OggPageWriter(out, 123)
            val payload = ByteArray(size) { (it * 31).toByte() }
            writer.packet(payload, 960)
            writer.flush(4)
            val parsed = OggTestParser.parse(out.toByteArray())
            assertEquals(1, parsed.packets.size)
            assertArrayEquals("size=$size", payload, parsed.packets.single())
            val laces = parsed.pages.flatMap { it.laces }
            assertEquals(List(size / 255) { 255 } + listOf(size % 255), laces)
            parsed.pages.forEachIndexed { index, page ->
                assertEquals(index, page.sequence)
                assertEquals(123, page.serial)
                assertEquals(index > 0, page.flags and 1 != 0)
                assertEquals(if (index == parsed.pages.lastIndex) 960L else -1L, page.granule)
            }
        }
    }

    /** Oracle: a full lacing table ending a packet must NOT continue the next packet. */
    @Test fun fullPageBetweenPackets() {
        val out = ByteArrayOutputStream()
        val writer = OggPageWriter(out, 7)
        writer.packet(ByteArray(64770), 120)
        writer.packet(byteArrayOf(9), 240)
        writer.flush(4)
        val parsed = OggTestParser.parse(out.toByteArray())
        assertEquals(listOf(0, 4), parsed.pages.map { it.flags })
        assertEquals(listOf(120L, 240L), parsed.pages.map { it.granule })
        assertEquals(listOf(64770, 1), parsed.packets.map { it.size })
    }

    /** Oracle: an unfinished packet does not advance a page's last completed granule. */
    @Test fun completedPacketBeforeSpanningPacket() {
        val out = ByteArrayOutputStream()
        val writer = OggPageWriter(out, 8)
        val first = byteArrayOf(1, 2, 3)
        val second = ByteArray(130050) { (it * 7).toByte() }
        writer.packet(first, 120)
        writer.packet(second, 1080)
        writer.flush(4)
        val parsed = OggTestParser.parse(out.toByteArray())
        assertEquals(listOf(120L, -1L, 1080L), parsed.pages.map { it.granule })
        assertEquals(listOf(0, 1, 5), parsed.pages.map { it.flags })
        assertArrayEquals(first, parsed.packets[0])
        assertArrayEquals(second, parsed.packets[1])
    }

    /** Oracle: the independent parser rejects payload corruption even with valid lacing. */
    @Test fun parserRejectsCorruptedPage() {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out, 1, 16000, 312, 1)
        writer.writePacket(byteArrayOf(0x98.toByte(), 1))
        writer.finish()
        val corrupt = out.toByteArray()
        corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { OggTestParser.parse(corrupt) }
    }

    /** Oracle: mandated byte layouts, isolated headers, one write/page, no premature EOS. */
    @Test fun headersAndFinishGolden() {
        val writes = mutableListOf<ByteArray>()
        val out = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                writes += b.copyOfRange(off, off + len)
                super.write(b, off, len)
            }
        }
        val writer = OggOpusWriter(out, 2, 44100, 312, 0x12345678)
        val headers = OggTestParser.parse(out.toByteArray())
        assertArrayEquals(byteArrayOf(79,112,117,115,72,101,97,100,1,2,56,1,68,-84,0,0,0,0,0), headers.packets[0])
        assertArrayEquals("OpusTags\u000e\u0000\u0000\u0000audio-blackbox\u0000\u0000\u0000\u0000".toByteArray(), headers.packets[1])
        assertEquals(listOf(2, 0), headers.pages.map { it.flags })
        assertEquals(listOf(0L, 0L), headers.pages.map { it.granule })
        repeat(3) { writer.writePacket(byteArrayOf(0x98.toByte(), it.toByte())) }
        val live = OggTestParser.parse(out.toByteArray())
        assertEquals(3, live.pages.size)
        assertEquals(1920L, live.pages.last().granule)
        assertTrue(live.pages.all { it.flags and 4 == 0 })
        writer.finish()
        writer.finish()
        val done = OggTestParser.parse(out.toByteArray())
        assertEquals(listOf(0L, 0L, 1920L, 2880L), done.pages.map { it.granule })
        assertEquals(listOf(2, 0, 0, 4), done.pages.map { it.flags })
        assertEquals(listOf(0, 1, 2, 3), done.pages.map { it.sequence })
        assertTrue(done.pages.all { it.serial == 0x12345678 })
        assertEquals(done.pages.size, writes.size)
        writes.forEach { assertEquals(1, OggTestParser.parse(it).pages.size) }
        assertThrows(IllegalStateException::class.java) { writer.writePacket(byteArrayOf(0)) }
    }

    /** Oracle: TOC durations, including delay, determine granules; PTS/20ms guesses cannot. */
    @Test fun mixedDurationsAndPreSkip() {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out, 1, 16000, 312, 42)
        val packets = listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0x88.toByte()),
            byteArrayOf(0x90.toByte()), byteArrayOf(0x98.toByte()), byteArrayOf(0x00),
            byteArrayOf(0x18), byteArrayOf(0x83.toByte(), 3))
        packets.forEach(writer::writePacket)
        writer.finish()
        val parsed = OggTestParser.parse(out.toByteArray())
        assertEquals(listOf(0L, 0L, 2280L, 5160L, 5520L), parsed.pages.map { it.granule })
        assertEquals(5208L, parsed.pages.last().granule - 312) // audible samples
        packets.zip(parsed.packets.drop(2)).forEach { (a, b) -> assertArrayEquals(a, b) }
        assertEquals(packets.size + 2, parsed.packets.size)
    }

    /** Oracle: each TOC mode/count family and invalid duration guard has a fixed expected size. */
    @Test fun tocModes() {
        val cases = listOf(0x00 to 480, 0x08 to 960, 0x10 to 1920, 0x18 to 2880,
            0x60 to 480, 0x68 to 960, 0x80 to 120, 0x88 to 240, 0x90 to 480,
            0x98 to 960, 0x81 to 240, 0x82 to 240)
        cases.forEach { (toc, expected) -> assertEquals(expected, OpusPacket.samples(byteArrayOf(toc.toByte()))) }
        assertEquals(5760, OpusPacket.samples(byteArrayOf(0x83.toByte(), 48)))
        listOf(byteArrayOf(), byteArrayOf(3), byteArrayOf(3, 0), byteArrayOf(0x83.toByte(), 49)).forEach {
            assertThrows(IllegalArgumentException::class.java) { OpusPacket.samples(it) }
        }
    }

    /** Oracle: header delay wins; CSD-1 fallback is native-endian nanoseconds, no silent zero. */
    @Test fun codecDelaySources() {
        val head = ByteBuffer.wrap(byteArrayOf(79,112,117,115,72,101,97,100,1,1,56,1,0,0,0,0,0,0,0))
        val delay = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(8_000_000).apply { flip() }
        assertEquals(312, OpusPacket.preSkip(head, delay))
        assertEquals(384, OpusPacket.preSkip(null, delay))
        assertEquals(0, head.position())
        assertEquals(0, delay.position())
    }

    /** Oracle: missing CSD still emits mono/stereo OpusHead with 312 pre-skip, intact
     * audio packets, valid page CRCs and EOS with the requested end trim. */
    @Test fun noCsdProducesValidFileWithDefaultPreSkip() {
        for (channels in 1..2) {
            val out = ByteArrayOutputStream()
            val writer = OggOpusWriter(out, channels, 48000, OpusPacket.preSkip(null, null), 42)
            val silence = hex("f8 ff fe")
            repeat(2) { writer.writePacket(silence) }
            writer.finish(1608)
            val parsed = OggTestParser.parse(out.toByteArray())
            assertArrayEquals(hex("4f 70 75 73 48 65 61 64 01 0${channels} 38 01 80 bb 00 00 00 00 00"), parsed.packets[0])
            assertEquals(4, parsed.packets.size)
            parsed.packets.drop(2).forEach { assertArrayEquals(silence, it) }
            assertEquals(listOf(2, 0, 4), parsed.pages.map { it.flags })
            assertEquals(listOf(0L, 0L, 1920L), parsed.pages.map { it.granule })
        }
    }

    /** Oracle: a Codec2 delay without OpusHead overrides the default, retaining format channels. */
    @Test fun delayOnlyCodec2UsesFormatChannels() {
        val delay = ByteBuffer.wrap("AOPUSDLY".toByteArray() +
            hex("08 00 00 00 00 00 00 00 51 57 63 00 00 00 00 00"))
        assertEquals(OpusPacket.Header(2, 313), OpusPacket.header(delay, fallbackChannels = 2))
    }
}

/** Independent test oracle: parses framing and uses polynomial long division, not production CRC. */
internal object OggTestParser {
    data class Page(val flags: Int, val granule: Long, val serial: Int, val sequence: Int, val laces: List<Int>)
    data class Parsed(val pages: List<Page>, val packets: List<ByteArray>)
    fun parse(file: ByteArray): Parsed {
        val pages = mutableListOf<Page>()
        val packets = mutableListOf<ByteArray>()
        val packet = ByteArrayOutputStream()
        var continuing = false
        var offset = 0
        while (offset < file.size) {
            require(file.size - offset >= 27)
            require(String(file, offset, 4, Charsets.US_ASCII) == "OggS")
            require(file[offset + 4].toInt() == 0)
            val n = file[offset + 26].toInt() and 255
            require(file.size - offset >= 27 + n)
            val laces = (0 until n).map { file[offset + 27 + it].toInt() and 255 }
            val length = 27 + n + laces.sum()
            require(file.size - offset >= length)
            val raw = file.copyOfRange(offset, offset + length)
            val header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val expectedCrc = header.getInt(22)
            raw.fill(0, 22, 26)
            var remainder = 0L
            for (byte in raw) {
                remainder = remainder xor ((byte.toLong() and 255) shl 24)
                repeat(8) {
                    remainder = remainder shl 1
                    if (remainder and 0x100000000L != 0L) remainder = remainder xor 0x104C11DB7L
                }
            }
            require(expectedCrc == remainder.toInt()) { "CRC mismatch on page ${pages.size}" }
            val flags = raw[5].toInt() and 255
            require((flags and 1 != 0) == continuing)
            var body = 27 + n
            for (lace in laces) {
                packet.write(raw, body, lace)
                body += lace
                continuing = lace == 255
                if (!continuing) {
                    packets += packet.toByteArray()
                    packet.reset()
                }
            }
            pages += Page(flags, header.getLong(6), header.getInt(14), header.getInt(18), laces)
            offset += length
        }
        require(!continuing) { "Unfinished packet" }
        return Parsed(pages, packets)
    }
}
