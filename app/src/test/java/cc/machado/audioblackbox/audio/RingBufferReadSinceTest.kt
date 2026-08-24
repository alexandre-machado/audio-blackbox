package cc.machado.audioblackbox.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single-threaded behaviour of [RingBuffer.readSince] (issue #51). The racing writer/drainer
 * cases -- including the forced-lapping one, which is the requirement this issue exists for --
 * live in [RingBufferReadSinceConcurrencyTest]; this file pins the arithmetic those tests depend
 * on, deterministically, with no threads involved.
 *
 * Each test states its oracle: what production behaviour would have to break for it to fail.
 * Tiny capacities and `bytesPerSecond = 1000` are used throughout so every offset in the
 * assertions is a literal the reader can check by hand rather than a computed expectation that
 * would agree with a buggy implementation.
 */
class RingBufferReadSinceTest {

    /**
     * "Larger than anything this test's buffer can hold", used where a case is about *what* comes
     * back rather than about chunking. `maxBytes` has no default (PR #86 review): the signature
     * makes every caller state a bound, so these tests state theirs too, and the ones that are
     * genuinely about the bound ([maxBytes caps a single read...]) pass a small explicit value.
     */
    private val ample = 1 shl 20

    @Test
    fun `successive reads threading the returned cursor reconstruct the stream gap-free and in order`() {
        // Oracle: fails if readSince ever skips, repeats or reorders bytes across calls -- i.e. if
        // nextCursor is not exactly startCursor + bytes.size, or if the copy starts at the wrong
        // physical position. Capacity is deliberately smaller than the total written (6 < 12), so
        // the reads also have to be correct across two physical wraps while never lapping, because
        // the drain here never falls more than one write behind.
        val buffer = RingBuffer(capacityBytes = 6, bytesPerSecond = 1000)

        var cursor = buffer.writeCursor()
        val drained = ArrayList<Byte>()
        for (value in 1..12) {
            buffer.write(byteArrayOf(value.toByte()))
            val result = buffer.readSince(cursor, maxBytes = ample)
            assertTrue("expected Data, got $result", result is ReadSinceResult.Data)
            result as ReadSinceResult.Data
            assertEquals(cursor, result.startCursor)
            assertEquals(cursor + result.bytes.size, result.nextCursor)
            assertEquals(0L, result.remainingBytes)
            drained.addAll(result.bytes.toList())
            cursor = result.nextCursor
        }

        assertArrayEquals((1..12).map { it.toByte() }.toByteArray(), drained.toByteArray())
        assertEquals(12L, cursor)
    }

    @Test
    fun `a read that spans the physical wrap point returns the logical order, not the array order`() {
        // Oracle: fails if the two-part arraycopy is wrong (e.g. the tail and head halves swapped,
        // or the split computed from the wrong index). The physical array here ends up as
        // [7,8,3,4,5,6] while the logical stream from cursor 2 is 3,4,5,6,7,8.
        val buffer = RingBuffer(capacityBytes = 6, bytesPerSecond = 1000)
        buffer.write(byteArrayOf(1, 2, 3, 4, 5, 6))
        buffer.write(byteArrayOf(7, 8))

        val result = buffer.readSince(cursor = 2, maxBytes = ample) as ReadSinceResult.Data

        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7, 8), result.bytes)
        assertEquals(8L, result.nextCursor)
    }

    @Test
    fun `a read is sized by what the caller has not seen, not by what the buffer holds`() {
        // Oracle: this is the test that fails if readSince is ever reimplemented as snapshot()
        // under another name. 4096 bytes are buffered; the caller has seen all but the last 10.
        // A capacity-shaped read would hand back 4096 bytes (and allocate 4096); a genuinely
        // incremental one hands back exactly 10. The returned array's size is the observable
        // proxy for both the allocation and the lock hold.
        val buffer = RingBuffer(capacityBytes = 4096, bytesPerSecond = 1000)
        buffer.write(ByteArray(4086) { 7 })
        val cursor = buffer.writeCursor()
        buffer.write(ByteArray(10) { 9 })

        val result = buffer.readSince(cursor, maxBytes = ample) as ReadSinceResult.Data

        assertEquals(10, result.bytes.size)
        assertArrayEquals(ByteArray(10) { 9 }, result.bytes)
        assertEquals(4086L, result.startCursor)
        assertEquals(4096L, result.nextCursor)
    }

    @Test
    fun `maxBytes caps a single read and the rest is reported as remaining, then read next`() {
        // Oracle: fails if maxBytes is ignored (the whole backlog copied under the lock in one
        // go, defeating the bound this primitive promises), or if remainingBytes is computed
        // against the wrong cursor. The two chunked reads must also still reassemble exactly.
        val buffer = RingBuffer(capacityBytes = 100, bytesPerSecond = 1000)
        buffer.write(ByteArray(30) { it.toByte() })

        val first = buffer.readSince(cursor = 0, maxBytes = 12) as ReadSinceResult.Data
        assertEquals(12, first.bytes.size)
        assertEquals(12L, first.nextCursor)
        assertEquals(18L, first.remainingBytes)

        val second = buffer.readSince(first.nextCursor, maxBytes = 12) as ReadSinceResult.Data
        assertEquals(12, second.bytes.size)
        assertEquals(6L, second.remainingBytes)

        val third = buffer.readSince(second.nextCursor, maxBytes = 12) as ReadSinceResult.Data
        assertEquals(6, third.bytes.size)
        assertEquals(0L, third.remainingBytes)

        assertArrayEquals(
            ByteArray(30) { it.toByte() },
            first.bytes + second.bytes + third.bytes,
        )
    }

    @Test
    fun `a caller that is fully caught up gets an empty Data, not a failure`() {
        // Oracle: fails if an idle poll is modelled as an error, or if nextCursor drifts when
        // nothing was written -- either would make a drain loop either cry wolf or lose its place.
        val buffer = RingBuffer(capacityBytes = 10, bytesPerSecond = 1000)
        buffer.write(byteArrayOf(1, 2, 3))

        val result = buffer.readSince(cursor = 3, maxBytes = ample) as ReadSinceResult.Data

        assertEquals(0, result.bytes.size)
        assertEquals(3L, result.startCursor)
        assertEquals(3L, result.nextCursor)
        assertEquals(0L, result.remainingBytes)
    }

    @Test
    fun `lapping is reported with the exact number of bytes lost and where to resume`() {
        // Oracle: the core requirement of issue #51. Capacity 10, 25 bytes written, so the stream
        // now holds offsets 15..24 and everything before 15 is gone. A caller sitting at cursor 4
        // lost exactly 11 bytes (offsets 4..14). Fails if lapping goes undetected, or if the
        // reported loss/resume point is wrong -- a consumer that surfaces "N seconds of audio
        // were lost" (issue #54) reads these exact numbers.
        val buffer = RingBuffer(capacityBytes = 10, bytesPerSecond = 1000)
        for (value in 1..25) buffer.write(byteArrayOf(value.toByte()))

        val result = buffer.readSince(cursor = 4, maxBytes = ample)

        assertTrue("expected Lapped, got $result", result is ReadSinceResult.Lapped)
        result as ReadSinceResult.Lapped
        assertEquals(4L, result.requestedCursor)
        assertEquals(15L, result.oldestAvailableCursor)
        assertEquals(11L, result.lostBytes)
    }

    @Test
    fun `resuming from the cursor a Lapped result hands back yields the surviving audio`() {
        // Oracle: fails if oldestAvailableCursor is off by one in either direction -- too low and
        // the resumed read would itself lap (or return overwritten bytes), too high and the
        // consumer silently drops audio that had survived.
        val buffer = RingBuffer(capacityBytes = 10, bytesPerSecond = 1000)
        for (value in 1..25) buffer.write(byteArrayOf(value.toByte()))

        val lapped = buffer.readSince(cursor = 0, maxBytes = ample) as ReadSinceResult.Lapped
        val resumed = buffer.readSince(lapped.oldestAvailableCursor, maxBytes = ample) as ReadSinceResult.Data

        // Bytes 16..25 are the ten values still in the ring after 25 single-byte writes.
        assertArrayEquals((16..25).map { it.toByte() }.toByteArray(), resumed.bytes)
    }

    @Test
    fun `a cursor sitting exactly on the oldest buffered byte is not lapped`() {
        // Oracle: the off-by-one guard on the lapping boundary. The byte at `totalWritten -
        // capacity` is still present, so a caller there has lost nothing. A `<=` where the
        // implementation needs `<` would report phantom audio loss to the user on every poll of
        // a caller that is exactly one full buffer behind -- and phantom loss reports are how a
        // real one stops being believed.
        val buffer = RingBuffer(capacityBytes = 10, bytesPerSecond = 1000)
        for (value in 1..25) buffer.write(byteArrayOf(value.toByte()))

        val result = buffer.readSince(cursor = 15, maxBytes = ample)

        assertTrue("expected Data at the exact boundary, got $result", result is ReadSinceResult.Data)
        assertEquals(10, (result as ReadSinceResult.Data).bytes.size)
    }

    @Test
    fun `one byte older than the oldest buffered byte is lapped, losing exactly that one byte`() {
        // Oracle: the other side of the boundary, and the case PR #86's review found missing --
        // every other Lapped test sits far from the edge (smallest loss asserted elsewhere is 11
        // bytes), so a comparison loosened by one (`cursor < oldestAvailable - 1`) passed the
        // whole suite. That mutation is not benign: at exactly this cursor it returns a Data whose
        // copy starts one slot *behind* the oldest live byte, so the caller receives already-
        // overwritten audio as its first byte and silently loses the newest one -- corruption and
        // loss dressed up as a good read, which is the failure issue #51 exists to make
        // impossible. This test fails on any relaxation of that comparison, in either direction,
        // and it pins the smallest possible loss report: exactly one byte.
        val buffer = RingBuffer(capacityBytes = 10, bytesPerSecond = 1000)
        for (value in 1..25) buffer.write(byteArrayOf(value.toByte()))
        val oldest = buffer.oldestCursor()
        assertEquals(15L, oldest)

        val result = buffer.readSince(oldest - 1, maxBytes = ample)

        assertTrue("one byte past the edge must be Lapped, got $result", result is ReadSinceResult.Lapped)
        result as ReadSinceResult.Lapped
        assertEquals(14L, result.requestedCursor)
        assertEquals(oldest, result.oldestAvailableCursor)
        assertEquals(1L, result.lostBytes)
    }

    @Test
    fun `losing audio and merely having little available are different types, not different lengths`() {
        // Oracle: the "never indistinguishable from a normal short read" criterion. Both buffers
        // below answer the same question -- "what is there since cursor 0" -- and both could
        // plausibly hand back 5 bytes. A clamped-length API would make them identical to the
        // caller. Fails the moment lapping is expressed as a shorter Data.
        val plentyOfRoom = RingBuffer(capacityBytes = 100, bytesPerSecond = 1000)
        plentyOfRoom.write(ByteArray(5) { 1 })
        val shortRead = plentyOfRoom.readSince(cursor = 0, maxBytes = ample)

        val lappedBuffer = RingBuffer(capacityBytes = 5, bytesPerSecond = 1000)
        // 12 separate writes, not one 60-byte write: a single write larger than the whole buffer
        // is truncated to its tail by write()'s own documented rule, which would leave
        // totalWritten at 5 and nothing lapped at all.
        repeat(12) { lappedBuffer.write(ByteArray(5) { 1 }) }
        val lapped = lappedBuffer.readSince(cursor = 0, maxBytes = ample)

        assertTrue("a genuinely short read must stay Data", shortRead is ReadSinceResult.Data)
        assertEquals(5, (shortRead as ReadSinceResult.Data).bytes.size)
        assertTrue("overwritten audio must not come back as a short Data", lapped is ReadSinceResult.Lapped)
        // 60 bytes written, 5 retained: offsets 0..54 are gone.
        assertEquals(55L, (lapped as ReadSinceResult.Lapped).lostBytes)
    }

    @Test
    fun `a cursor left over from before clear is reported as a stream reset, not as data or loss`() {
        // Oracle: clear() rewinds the stream to zero (stop, and the save-then-restart flow), so a
        // drain thread that outlives it holds a cursor past the end of the stream. Fails if that
        // is answered with bytes from the new stream (a silent splice of unrelated audio), or by
        // throwing (killing a drain thread that did nothing wrong), or by being conflated with
        // Lapped -- nothing was overwritten out from under this caller.
        val buffer = RingBuffer(capacityBytes = 10, bytesPerSecond = 1000)
        buffer.write(ByteArray(8) { 1 })
        val staleCursor = buffer.writeCursor()
        buffer.clear()
        buffer.write(ByteArray(3) { 2 })

        val result = buffer.readSince(staleCursor, maxBytes = ample)

        assertTrue("expected StreamReset, got $result", result is ReadSinceResult.StreamReset)
        result as ReadSinceResult.StreamReset
        assertEquals(8L, result.requestedCursor)
        assertEquals(3L, result.currentCursor)
    }

    @Test
    fun `once a restarted stream grows past the stale cursor the reset is no longer detectable`() {
        // Oracle: this pins the documented *limit* of StreamReset rather than a desirable
        // behaviour, so it is the rare test that would fail on an improvement -- and that is the
        // point. Detection is positional (cursor > totalWritten), so it expires once the new
        // stream is longer than the stale cursor: the same drain that got StreamReset above now
        // gets ordinary Data carrying new-stream bytes at old-stream offsets, spliced across the
        // stop/start boundary with no signal. PR #86 review (`@rev` finding 2) asked for this
        // written down so issue #54 inherits a contract instead of an assumption; the durable fix
        // is a generation counter in the cursor, which belongs to #54, not to this primitive.
        // If someone implements that counter, this test SHOULD fail -- update it deliberately,
        // and take the failure as confirmation the gap closed, not as a regression.
        val buffer = RingBuffer(capacityBytes = 100, bytesPerSecond = 1000)
        buffer.write(ByteArray(8) { 1 })
        val staleCursor = buffer.writeCursor()
        buffer.clear()
        buffer.write(ByteArray(20) { 2 })

        val result = buffer.readSince(staleCursor, maxBytes = ample)

        assertTrue("today this is undetectable and comes back as Data, got $result", result is ReadSinceResult.Data)
        result as ReadSinceResult.Data
        // 12 bytes of the *new* stream, handed back at offsets 8..19 of a stream that no longer
        // exists. Every byte is a 2 (new stream), never a 1 (old stream): the old audio is gone
        // and the caller is not told.
        assertEquals(12, result.bytes.size)
        assertArrayEquals(ByteArray(12) { 2 }, result.bytes)
    }

    @Test
    fun `cursor accessors bracket the readable range`() {
        // Oracle: writeCursor is the seed for "record from now on" and oldestCursor for "include
        // the retained past" (issue #47). Fails if either drifts from totalWritten / the lapping
        // boundary, which would make a caller seeded from them immediately lap or skip audio.
        val buffer = RingBuffer(capacityBytes = 10, bytesPerSecond = 1000)
        assertEquals(0L, buffer.writeCursor())
        assertEquals(0L, buffer.oldestCursor())

        buffer.write(ByteArray(4) { 1 })
        assertEquals(4L, buffer.writeCursor())
        assertEquals(0L, buffer.oldestCursor()) // nothing evicted yet

        buffer.write(ByteArray(9) { 2 })
        assertEquals(13L, buffer.writeCursor())
        assertEquals(3L, buffer.oldestCursor()) // 13 written, 10 retained

        assertTrue(buffer.readSince(buffer.oldestCursor(), maxBytes = ample) is ReadSinceResult.Data)
        assertEquals(0, (buffer.readSince(buffer.writeCursor(), maxBytes = ample) as ReadSinceResult.Data).bytes.size)
    }

    @Test
    fun `invalid arguments are rejected rather than silently normalised`() {
        // Oracle: no legal call sequence produces a negative cursor or a non-positive maxBytes, so
        // these are caller bugs. Fails if they are quietly coerced -- a maxBytes of 0 coerced to
        // "everything" would restore exactly the unbounded copy this primitive exists to avoid,
        // and a negative cursor coerced to 0 would fabricate a stream position.
        val buffer = RingBuffer(capacityBytes = 10, bytesPerSecond = 1000)
        buffer.write(ByteArray(4) { 1 })

        assertThrows(IllegalArgumentException::class.java) { buffer.readSince(cursor = -1, maxBytes = ample) }
        assertThrows(IllegalArgumentException::class.java) { buffer.readSince(cursor = 0, maxBytes = 0) }
        assertThrows(IllegalArgumentException::class.java) { buffer.readSince(cursor = 0, maxBytes = -5) }
    }

    @Test
    fun `readSince does not disturb snapshot, bufferedBytes or the write path`() {
        // Oracle: issue #51 requires the existing guarantees to be untouched. Fails if readSince
        // ever mutates state (e.g. "consuming" the bytes it returns, or advancing an internal
        // counter), which would make the existing Save-the-past export return less audio after a
        // live drain has run -- exactly the silent loss this issue is about, arriving by the
        // opposite door.
        val buffer = RingBuffer(capacityBytes = 10, bytesPerSecond = 1000)
        buffer.write(byteArrayOf(1, 2, 3, 4, 5))

        repeat(3) { buffer.readSince(cursor = 0, maxBytes = ample) }

        assertEquals(5L, buffer.bufferedBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), buffer.snapshot(durationMillis = 1000).data)
        buffer.write(byteArrayOf(6))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), buffer.snapshot(durationMillis = 1000).data)
    }
}
