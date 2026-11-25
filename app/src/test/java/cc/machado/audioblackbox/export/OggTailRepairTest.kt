package cc.machado.audioblackbox.export

import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Test

class OggTailRepairTest {
    private fun fixture(): ByteArray = ByteArrayOutputStream().also { out ->
        val writer = OggOpusWriter(out, 1, 48000, 312, 42)
        repeat(10) { writer.writePacket(ByteArray(300).also { it[0] = 0x78 }) }
        writer.finish()
    }.toByteArray()

    private fun ends(bytes: ByteArray): List<Int> {
        val ends = mutableListOf<Int>()
        var position = 0
        while (position < bytes.size) {
            val segments = bytes[position + 26].toInt() and 255
            var length = 27 + segments
            repeat(segments) { length += bytes[position + 27 + it].toInt() and 255 }
            position += length
            ends += position
        }
        return ends
    }

    private fun verify(bytes: ByteArray, expected: ByteArray, result: OggTailRepair.Result? = null) {
        val file = java.nio.file.Files.createTempFile("ogg-repair", ".ogg").toFile()
        try {
            file.writeBytes(bytes)
            RandomAccessFile(file, "rw").use {
                val actual = OggTailRepair.repair(it.channel)
                if (result != null) assertEquals(result, actual)
            }
            assertArrayEquals("Repair must retain exactly the complete CRC-valid prefix", expected, file.readBytes())
            RandomAccessFile(file, "rw").use {
                assertNotEquals(OggTailRepair.Result.TRUNCATED, OggTailRepair.repair(it.channel))
            }
            assertArrayEquals("Second repair must not change bytes", expected, file.readBytes())
        } finally { file.delete() }
    }

    // Oracle: every byte cut (including every header, lace, body and boundary) retains
    // exactly the last whole page, or ALL original bytes when either header is incomplete.
    @Test fun everyCutAndIdempotence() {
        val bytes = fixture()
        val ends = ends(bytes)
        for (cut in 0..bytes.size) {
            val expected = if (cut < ends[1]) cut else ends.last { it <= cut }
            verify(bytes.copyOf(cut), bytes.copyOf(expected))
        }
    }

    // Oracle: a fully present page with a bad CRC is discarded, not accepted as complete.
    @Test fun corruptLastPageDropped() {
        val bytes = fixture()
        val ends = ends(bytes)
        val corrupt = bytes.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        verify(corrupt, bytes.copyOf(ends[ends.lastIndex - 1]))
    }

    // Oracle: valid later pages forbid truncation even when the damaged page's
    // capture, CRC or lacing is corrupt, or most of that page is missing.
    @Test fun interiorDamagePreservesAllBytes() {
        val bytes = fixture()
        val boundaries = ends(bytes)
        val start = boundaries[1]
        for (offset in listOf(start, start + 22, start + 27)) {
            val corrupt = bytes.clone().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            verify(corrupt, corrupt, OggTailRepair.Result.INTERIOR_DAMAGE)
        }
        val torn = bytes.copyOf(start + 12) + bytes.copyOfRange(boundaries[2], bytes.size)
        verify(torn, torn, OggTailRepair.Result.INTERIOR_DAMAGE)
        val acrossScanBoundary = bytes.copyOf(start) + ByteArray(8190) +
            bytes.copyOfRange(boundaries[2], bytes.size)
        verify(acrossScanBoundary, acrossScanBoundary, OggTailRepair.Result.INTERIOR_DAMAGE)
    }

    // Oracle: corrupt header pages and unrelated ADTS data are never truncated.
    @Test fun invalidHeadersAndAdtsUntouched() {
        val bytes = fixture()
        for (offset in listOf(0, 4, 22, ends(bytes)[0] + 22)) {
            val corrupt = bytes.clone().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            verify(corrupt, corrupt)
        }
        val adts = byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x60, 0x40, 0, 0, 0)
        verify(adts, adts)
    }
}
