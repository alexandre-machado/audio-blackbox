package cc.machado.audioblackbox.export

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Startup-only repair of our single-stream Opus files. Preserves interior damage.
 * Memory is bounded by one maximum Ogg page plus an 8 KiB scan buffer.
 * No EOS is synthesized or rewritten.
 */
internal object OggTailRepair {
    enum class Result { UNCHANGED, TRUNCATED, INVALID_HEADERS, INTERIOR_DAMAGE }

    fun repair(input: FileChannel, output: FileChannel = input): Result {
        val size = input.size()
        val page = ByteArray(27 + 255 + 65025)
        var end = 0L
        var pages = 0
        var serial = 0
        fun read(offset: Int, length: Int): Boolean {
            val buffer = ByteBuffer.wrap(page, offset, length)
            while (buffer.hasRemaining()) {
                if (input.read(buffer) <= 0) return false
            }
            return true
        }
        input.position(0)
        while (input.position() < size) {
            if (!read(0, 27)) break
            if (String(page, 0, 4, Charsets.US_ASCII) != "OggS" || page[4] != 0.toByte()) break
            val header = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
            val segments = page[26].toInt() and 255
            if (!read(27, segments)) break
            var bodyLength = 0
            for (i in 27 until 27 + segments) bodyLength += page[i].toInt() and 255
            val body = 27 + segments
            if (!read(body, bodyLength)) break
            val length = body + bodyLength
            val expectedCrc = header.getInt(22)
            header.putInt(22, 0)
            if (OggPageWriter.crc(page, length) != expectedCrc) break
            if (pages == 0) serial = header.getInt(14)
            if (header.getInt(14) != serial || header.getInt(18) != pages) break
            if (pages < 2) {
                // Our writer emits each header as one complete packet on its own page.
                val signature = if (pages == 0) "OpusHead" else "OpusTags"
                if (bodyLength < (if (pages == 0) 19 else 16) ||
                    String(page, body, 8, Charsets.US_ASCII) != signature ||
                    segments == 0 || (page[body - 1].toInt() and 255) == 255 ||
                    page[5].toInt() != (if (pages == 0) 2 else 0)) break
                if (pages == 0 && (bodyLength != 19 || page[body + 8] != 1.toByte() ||
                        page[body + 9].toInt() !in 1..2 || page[body + 18] != 0.toByte())) break
                if (pages == 1) {
                    val vendorLength = header.getInt(body + 8)
                    if (vendorLength < 0 || vendorLength > bodyLength - 16) break
                    // The production writer emits no user comments. Refuse unfamiliar headers.
                    if (header.getInt(body + 12 + vendorLength) != 0 || bodyLength != 16 + vendorLength) break
                }
            }
            end = input.position()
            pages++
        }
        if (pages < 2) return Result.INVALID_HEADERS
        if (end == size) return Result.UNCHANGED
        // Damaged lacing/length cannot be trusted: search every subsequent byte,
        // using bounded chunks and the existing page buffer to validate candidates.
        val scan = ByteBuffer.allocate(8192)
        var position = end
        var matched = 0
        val capture = "OggS".toByteArray(Charsets.US_ASCII)
        while (position < size) {
            scan.clear()
            scan.limit(minOf(scan.capacity().toLong(), size - position).toInt())
            val count = input.read(scan, position)
            check(count > 0) { "Incomplete Ogg tail scan" }
            scan.flip()
            repeat(count) {
                val byte = scan.get()
                position++
                matched = if (byte == capture[matched]) matched + 1 else if (byte == capture[0]) 1 else 0
                if (matched == capture.size) {
                    matched = 0
                    input.position(position - capture.size)
                    if (read(0, 27) && page[4] == 0.toByte()) {
                        val segments = page[26].toInt() and 255
                        if (read(27, segments)) {
                            val body = 27 + segments
                            var length = 0
                            for (i in 27 until body) length += page[i].toInt() and 255
                            if (read(body, length)) {
                                val header = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
                                val crc = header.getInt(22)
                                header.putInt(22, 0)
                                if (OggPageWriter.crc(page, body + length) == crc) {
                                    return Result.INTERIOR_DAMAGE
                                }
                            }
                        }
                    }
                }
            }
        }
        output.truncate(end)
        output.force(true)
        return Result.TRUNCATED
    }
}
