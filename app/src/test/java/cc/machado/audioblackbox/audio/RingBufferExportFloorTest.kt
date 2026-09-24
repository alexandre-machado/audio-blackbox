package cc.machado.audioblackbox.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Primitive-level semantics of [RingBuffer]'s export floor (issue #410): the stream offset where
 * the next save starts once a previous save succeeded.
 *
 * Oracle for every test: the numbers asserted are what [RingBuffer.oldestCursor] /
 * [RingBuffer.bufferedBytes] / [RingBuffer.bufferedDurationMillis] / [RingBuffer.snapshot] /
 * [RingBuffer.readSince] actually return after real writes, not values recomputed with the
 * production formula. Every write total is kept strictly below capacity unless a test is about
 * wrap/shrink on purpose, so ring saturation can never be what moves `oldestCursor` (AGENTS.md §2,
 * trap 2): any movement seen here can only come from the floor.
 */
class RingBufferExportFloorTest {

    // 1000 Hz mono 16-bit = 2000 bytes/s, so 2 bytes == 1 ms and durations are easy to read.
    private val bytesPerSecond = 2000

    private fun ring(capacity: Int = 10_000) = RingBuffer(capacityBytes = capacity, bytesPerSecond = bytesPerSecond)

    private fun pattern(size: Int, base: Int): ByteArray = ByteArray(size) { ((it % 50) + base).toByte() }

    @Test
    fun `advancing the floor moves oldestCursor, bufferedBytes and bufferedDurationMillis`() {
        val buffer = ring()
        buffer.write(pattern(4000, 1))
        assertEquals(0L, buffer.oldestCursor())
        assertEquals(4000L, buffer.bufferedBytes())
        assertEquals(2000L, buffer.bufferedDurationMillis())

        assertTrue(buffer.advanceExportFloor(3000L))

        assertEquals(3000L, buffer.exportFloor())
        assertEquals(3000L, buffer.oldestCursor())
        assertEquals(1000L, buffer.bufferedBytes())
        assertEquals(500L, buffer.bufferedDurationMillis())
        // The write head is untouched: the floor is logical, nothing was consumed or cleared.
        assertEquals(4000L, buffer.writeCursor())
    }

    @Test
    fun `floor is monotonic and never moves backwards`() {
        val buffer = ring()
        buffer.write(pattern(4000, 1))
        assertTrue(buffer.advanceExportFloor(3000L))

        assertFalse("a lower cursor must be ignored", buffer.advanceExportFloor(1000L))
        assertFalse("the same cursor is not a move", buffer.advanceExportFloor(3000L))

        assertEquals(3000L, buffer.exportFloor())
        assertEquals(3000L, buffer.oldestCursor())
    }

    @Test
    fun `a cursor past the write head is ignored, not clamped`() {
        // Such a cursor can only come from a stream that was cleared after the save fixed its
        // window; clamping it would hide audio of the new stream that no file contains.
        val buffer = ring()
        buffer.write(pattern(4000, 1))

        assertFalse(buffer.advanceExportFloor(4001L))

        assertEquals(0L, buffer.exportFloor())
        assertEquals(0L, buffer.oldestCursor())
        assertEquals(4000L, buffer.bufferedBytes())
    }

    @Test
    fun `floor at the write head leaves nothing saveable until new audio arrives`() {
        val buffer = ring()
        buffer.write(pattern(4000, 1))
        buffer.advanceExportFloor(4000L)

        assertEquals(0L, buffer.bufferedBytes())
        assertEquals(0L, buffer.bufferedDurationMillis())
        assertEquals(buffer.writeCursor(), buffer.oldestCursor())
        assertEquals(0, buffer.snapshot(60_000L).data.size)

        val fresh = pattern(600, 70)
        buffer.write(fresh)

        assertEquals(600L, buffer.bufferedBytes())
        assertArrayEquals("snapshot must hold only post-floor audio", fresh, buffer.snapshot(60_000L).data)
    }

    @Test
    fun `readSince below the floor still returns the real bytes, not Lapped`() {
        // A reader that fixed its cursor before the floor moved (a forward recording draining the
        // retained past while an unrelated save succeeds) still owns real, unoverwritten bytes.
        val buffer = ring()
        val past = pattern(4000, 1)
        buffer.write(past)
        buffer.advanceExportFloor(3000L)

        val result = buffer.readSince(0L, 4000)

        assertTrue("expected Data, got $result", result is ReadSinceResult.Data)
        assertArrayEquals(past, (result as ReadSinceResult.Data).bytes)
    }

    @Test
    fun `clear resets the floor with the stream`() {
        val buffer = ring()
        buffer.write(pattern(4000, 1))
        buffer.advanceExportFloor(3000L)

        buffer.clear()
        assertEquals(0L, buffer.exportFloor())

        buffer.write(pattern(1000, 1))
        assertEquals(0L, buffer.oldestCursor())
        assertEquals(1000L, buffer.bufferedBytes())
    }

    @Test
    fun `growing resize preserves the floor`() {
        val buffer = ring(capacity = 10_000)
        buffer.write(pattern(4000, 1))
        buffer.advanceExportFloor(3000L)

        assertEquals(ResizeOutcome.Applied, buffer.resize(20_000))

        assertEquals(3000L, buffer.exportFloor())
        assertEquals(3000L, buffer.oldestCursor())
        assertEquals(1000L, buffer.bufferedBytes())
    }

    @Test
    fun `shrinking resize below the floor's distance resolves to the physical oldest byte`() {
        val buffer = ring(capacity = 10_000)
        buffer.write(pattern(4000, 1))
        buffer.advanceExportFloor(1000L)

        // Keeps only the newest 2000 bytes: physical oldest becomes 2000, past the floor.
        assertEquals(ResizeOutcome.Applied, buffer.resize(2000))

        assertEquals(2000L, buffer.oldestCursor())
        assertEquals(2000L, buffer.bufferedBytes())
        assertEquals(1000L, buffer.exportFloor())
    }
}
