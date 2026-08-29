package cc.machado.audioblackbox.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RingBuffer.resize] (issue #223).
 *
 * Verifies dynamic in-place capacity resizing across:
 * - Expanding unwrapped and wrapped buffers (100% data preservation)
 * - Shrinking wrapped buffers (honest FIFO truncation of oldest bytes exceeding new capacity)
 * - Continuous monotonic stream offset coordinates ([totalWritten], [oldestCursor])
 * - Multi-format segment pruning and preservation
 * - Invariant: readSince reports honest [ReadSinceResult.Lapped] when reading evicted audio
 * - Thread-safety under concurrent writes, reads, and resizing
 */
class RingBufferResizeTest {

    @Test
    fun `expanding unwrapped buffer preserves 100 percent of audio and allows further growth`() {
        val buffer = RingBuffer(capacityBytes = 1_000, bytesPerSecond = 1_000)
        val payload1 = ByteArray(400) { (it % 100).toByte() }
        buffer.write(payload1)

        assertEquals(400L, buffer.bufferedBytes())
        assertEquals(0L, buffer.oldestCursor())
        assertEquals(400L, buffer.writeCursor())

        // Resize from 1,000 to 2,500 bytes
        buffer.resize(2_500)

        assertEquals(2_500, buffer.capacityBytes)
        assertEquals(400L, buffer.bufferedBytes())
        assertEquals(0L, buffer.oldestCursor())
        assertEquals(400L, buffer.writeCursor())

        val snap = buffer.snapshot(1_000)
        assertEquals(400, snap.data.size)
        assertTrue("snapshot data must match original payload", payload1.contentEquals(snap.data))

        val read = buffer.readSince(0L, 1_000) as ReadSinceResult.Data
        assertEquals(400, read.bytes.size)
        assertTrue("readSince data must match original payload", payload1.contentEquals(read.bytes))

        // Write additional 1,000 bytes into expanded capacity
        val payload2 = ByteArray(1_000) { ((it + 100) % 100).toByte() }
        buffer.write(payload2)

        assertEquals(1_400L, buffer.bufferedBytes())
        assertEquals(0L, buffer.oldestCursor())
        assertEquals(1_400L, buffer.writeCursor())

        val snapAll = buffer.snapshot(2_000)
        assertEquals(1_400, snapAll.data.size)
        assertTrue("first 400 bytes match payload1", payload1.contentEquals(snapAll.data.copyOfRange(0, 400)))
        assertTrue("next 1000 bytes match payload2", payload2.contentEquals(snapAll.data.copyOfRange(400, 1_400)))
    }

    @Test
    fun `expanding saturated wrapped buffer preserves all surviving audio across ring wrap`() {
        val buffer = RingBuffer(capacityBytes = 1_000, bytesPerSecond = 1_000)
        // Write 2,500 bytes in 500-byte chunks to simulate streaming past capacity
        val totalBytes = 2_500
        val fullStream = ByteArray(totalBytes) { (it and 0xFF).toByte() }
        for (i in 0 until totalBytes step 500) {
            buffer.write(fullStream, i, 500)
        }

        // Saturated 1,000-byte buffer retains stream offsets [1500, 2500)
        assertEquals(1_000L, buffer.bufferedBytes())
        assertEquals(1_500L, buffer.oldestCursor())
        assertEquals(2_500L, buffer.writeCursor())

        // Expand to 3,000 bytes
        buffer.resize(3_000)

        assertEquals(3_000, buffer.capacityBytes)
        assertEquals(1_000L, buffer.bufferedBytes())
        assertEquals(1_500L, buffer.oldestCursor())
        assertEquals(2_500L, buffer.writeCursor())

        // Verify surviving 1,000 bytes match stream offsets 1500..2499
        val expectedSurviving = fullStream.copyOfRange(1_500, 2_500)
        val snap = buffer.snapshot(2_000)
        assertEquals(1_000, snap.data.size)
        assertTrue("surviving bytes must match expected range", expectedSurviving.contentEquals(snap.data))

        val read = buffer.readSince(1_500L, 1_000) as ReadSinceResult.Data
        assertEquals(1_000, read.bytes.size)
        assertTrue("readSince must match expected range", expectedSurviving.contentEquals(read.bytes))

        // Write 1,500 more bytes (stream offsets 2500..3999) without overflowing new 3,000 capacity
        val moreBytes = ByteArray(1_500) { ((it + 2_500) and 0xFF).toByte() }
        for (i in 0 until 1_500 step 500) {
            buffer.write(moreBytes, i, 500)
        }

        assertEquals(2_500L, buffer.bufferedBytes())
        assertEquals(1_500L, buffer.oldestCursor())
        assertEquals(4_000L, buffer.writeCursor())

        val snapFull = buffer.snapshot(3_000)
        assertEquals(2_500, snapFull.data.size)
        val expectedAll = expectedSurviving + moreBytes
        assertTrue("snapshot after growth must contain all 2,500 bytes", expectedAll.contentEquals(snapFull.data))
    }

    @Test
    fun `shrinking buffer truncates oldest audio and reports lapped on evicted offsets`() {
        val buffer = RingBuffer(capacityBytes = 3_000, bytesPerSecond = 1_000)
        val fullStream = ByteArray(3_000) { (it and 0xFF).toByte() }
        buffer.write(fullStream)

        assertEquals(3_000L, buffer.bufferedBytes())
        assertEquals(0L, buffer.oldestCursor())
        assertEquals(3_000L, buffer.writeCursor())

        // Shrink capacity from 3,000 to 1,000 bytes
        buffer.resize(1_000)

        assertEquals(1_000, buffer.capacityBytes)
        assertEquals(1_000L, buffer.bufferedBytes())
        assertEquals(2_000L, buffer.oldestCursor())
        assertEquals(3_000L, buffer.writeCursor())

        // Surviving bytes must be the newest 1,000 bytes [2000, 3000)
        val expectedSurviving = fullStream.copyOfRange(2_000, 3_000)
        val snap = buffer.snapshot(3_000)
        assertEquals(1_000, snap.data.size)
        assertTrue("snapshot must contain newest 1,000 bytes", expectedSurviving.contentEquals(snap.data))

        // Reading at offset 1,500 (which was evicted by the shrink) must report Lapped
        val lappedResult = buffer.readSince(1_500L, 500)
        assertTrue("reading evicted audio must return Lapped, got $lappedResult", lappedResult is ReadSinceResult.Lapped)
        val lapped = lappedResult as ReadSinceResult.Lapped
        assertEquals(1_500L, lapped.requestedCursor)
        assertEquals(2_000L, lapped.oldestAvailableCursor)
        assertEquals(500L, lapped.lostBytes)

        // Reading from oldest surviving cursor (2,000) succeeds with Data
        val validResult = buffer.readSince(2_000L, 1_000)
        assertTrue("reading from oldest available must return Data, got $validResult", validResult is ReadSinceResult.Data)
        val data = validResult as ReadSinceResult.Data
        assertEquals(1_000, data.bytes.size)
        assertTrue("data must match surviving range", expectedSurviving.contentEquals(data.bytes))
    }

    @Test
    fun `shrinking unwrapped buffer where data fits entirely preserves all data`() {
        val buffer = RingBuffer(capacityBytes = 3_000, bytesPerSecond = 1_000)
        val payload = ByteArray(800) { (it % 120).toByte() }
        buffer.write(payload)

        assertEquals(800L, buffer.bufferedBytes())

        // Shrink to 1,500 bytes (larger than 800 bytes held)
        buffer.resize(1_500)

        assertEquals(1_500, buffer.capacityBytes)
        assertEquals(800L, buffer.bufferedBytes())
        assertEquals(0L, buffer.oldestCursor())
        assertEquals(800L, buffer.writeCursor())

        val snap = buffer.snapshot(2_000)
        assertEquals(800, snap.data.size)
        assertTrue("all 800 bytes preserved", payload.contentEquals(snap.data))
    }

    @Test
    fun `resizing with multi-format segments preserves and prunes segments correctly`() {
        val configA = AudioConfig(sampleRateHz = 16_000, channelCount = 1) // 32,000 B/s
        val configB = AudioConfig(sampleRateHz = 44_100, channelCount = 1) // 88,200 B/s

        val buffer = RingBuffer(capacityBytes = 64_000, initialConfig = configA)

        // Write 32,000 bytes in format A (1 second of audio)
        val dataA = ByteArray(32_000) { 1 }
        buffer.write(dataA)

        // Switch to format B and write 88,200 bytes in chunks of 20,000 bytes
        buffer.setFormat(configB)
        val dataB = ByteArray(88_200) { 2 }
        var written = 0
        while (written < 88_200) {
            val chunk = minOf(20_000, 88_200 - written)
            buffer.write(dataB, written, chunk)
            written += chunk
        }

        // totalWritten = 120,200 bytes. Buffer capacity = 64,000.
        // Currently retained: [56,200, 120,200) -> part of A (56,200..32,000 is gone, so all retained is in B)
        assertEquals(64_000L, buffer.bufferedBytes())

        // Expand capacity to 200,000 bytes
        buffer.resize(200_000)
        assertEquals(200_000, buffer.capacityBytes)
        // Since old capacity was 64,000, the surviving audio in the buffer was 64,000 bytes (offsets 56,200..120,200)
        assertEquals(64_000L, buffer.bufferedBytes())

        val segments = buffer.activeSegments()
        assertEquals(1, segments.size)
        assertEquals(configB, segments[0].config)
    }

    @Test
    fun `resizing preserves multi-format boundary when both formats survive`() {
        val configA = AudioConfig(sampleRateHz = 16_000, channelCount = 1) // 32,000 B/s
        val configB = AudioConfig(sampleRateHz = 44_100, channelCount = 1) // 88,200 B/s

        val buffer = RingBuffer(capacityBytes = 200_000, initialConfig = configA)

        // Write 32,000 bytes in A
        buffer.write(ByteArray(32_000) { 10 })

        // Switch to B and write 44,100 bytes in B
        buffer.setFormat(configB)
        buffer.write(ByteArray(44_100) { 20 })

        // Total written = 76,100 bytes. Both segments fit in 200,000 capacity.
        assertEquals(2, buffer.activeSegments().size)

        // Expand to 300,000 bytes
        buffer.resize(300_000)
        assertEquals(300_000, buffer.capacityBytes)
        assertEquals(76_100L, buffer.bufferedBytes())
        val segmentsAfterExpand = buffer.activeSegments()
        assertEquals(2, segmentsAfterExpand.size)
        assertEquals(configA, segmentsAfterExpand[0].config)
        assertEquals(configB, segmentsAfterExpand[1].config)

        // Shrink to 50,000 bytes (offsets 26,100..76,100 survive -> 5,900 bytes of A + 44,100 bytes of B)
        buffer.resize(50_000)
        assertEquals(50_000, buffer.capacityBytes)
        assertEquals(50_000L, buffer.bufferedBytes())
        val segmentsAfterShrink = buffer.activeSegments()
        assertEquals(2, segmentsAfterShrink.size)
        assertEquals(configA, segmentsAfterShrink[0].config)
        assertEquals(configB, segmentsAfterShrink[1].config)
    }

    @Test
    fun `concurrent writing, reading, and resizing operates safely under lock`() {
        val buffer = RingBuffer(capacityBytes = 10_000, bytesPerSecond = 10_000)
        val running = AtomicBoolean(true)
        val startLatch = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)

        // Thread 1: Writer
        val writerThread = Thread({
            startLatch.await()
            val chunk = ByteArray(256) { (it % 100).toByte() }
            while (running.get()) {
                try {
                    buffer.write(chunk)
                } catch (t: Throwable) {
                    error.compareAndSet(null, t)
                    break
                }
            }
        }, "ResizeStressWriter")

        // Thread 2: Reader (snapshots and readSince)
        val readerThread = Thread({
            startLatch.await()
            var cursor = 0L
            while (running.get()) {
                try {
                    buffer.snapshot(1_000)
                    when (val result = buffer.readSince(cursor, 512)) {
                        is ReadSinceResult.Data -> cursor = result.nextCursor
                        is ReadSinceResult.Lapped -> cursor = result.oldestAvailableCursor
                        is ReadSinceResult.StreamReset -> cursor = result.currentCursor
                    }
                } catch (t: Throwable) {
                    error.compareAndSet(null, t)
                    break
                }
            }
        }, "ResizeStressReader")

        writerThread.start()
        readerThread.start()
        startLatch.countDown()

        // Main thread: Rapidly resize buffer back and forth
        val sizes = listOf(5_000, 20_000, 8_000, 35_000, 10_000, 15_000, 4_000, 25_000)
        for (size in sizes) {
            buffer.resize(size)
            Thread.sleep(5)
        }

        running.set(false)
        writerThread.join(2_000)
        readerThread.join(2_000)

        assertNull("no exception should be thrown during concurrent resize: ${error.get()}", error.get())
        assertTrue("buffer should hold valid capacity", buffer.capacityBytes > 0)
        assertTrue("buffer should hold valid bufferedBytes", buffer.bufferedBytes() > 0)
    }
}
