package cc.machado.audioblackbox.audio

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [RingBuffer] (issue #2). Pure JVM tests -- no Robolectric, no
 * instrumentation -- because [RingBuffer] contains no Android framework types.
 */
class RingBufferTest {

    /** Feeds a fixed sequence of timestamps to successive `clock()` calls, sticking on the last
     * value once exhausted. Lets timing tests assert exact derived start timestamps. */
    private fun scriptedClock(vararg times: Long): () -> Long {
        var i = 0
        return {
            val t = times[i.coerceAtMost(times.size - 1)]
            if (i < times.size - 1) i++
            t
        }
    }

    @Test
    fun `fill without wrap returns bytes in order`() {
        val buffer = RingBuffer(capacityBytes = 10, bytesPerSecond = 1000)
        buffer.write(byteArrayOf(1, 2, 3, 4, 5))

        val result = buffer.snapshot(durationMillis = 1000)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), result.data)
    }

    @Test
    fun `exact fill returns everything written with no wrap distortion`() {
        val buffer = RingBuffer(capacityBytes = 5, bytesPerSecond = 1000)
        buffer.write(byteArrayOf(1, 2, 3, 4, 5))

        val result = buffer.snapshot(durationMillis = 1000)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), result.data)
    }

    @Test
    fun `single wrap overwrites oldest bytes and keeps chronological order`() {
        val buffer = RingBuffer(capacityBytes = 5, bytesPerSecond = 1000)
        buffer.write(byteArrayOf(1, 2, 3, 4, 5))
        buffer.write(byteArrayOf(6, 7)) // wraps: total written = 7 > capacity 5

        val result = buffer.snapshot(durationMillis = 1000)

        // Last 5 logical bytes of the stream 1..7 are 3,4,5,6,7.
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7), result.data)
    }

    @Test
    fun `multiple wraps keep only the most recent capacity bytes`() {
        val buffer = RingBuffer(capacityBytes = 4, bytesPerSecond = 1000)
        for (value in 1..20) {
            buffer.write(byteArrayOf(value.toByte()))
        }

        val result = buffer.snapshot(durationMillis = 1000)

        assertArrayEquals(byteArrayOf(17, 18, 19, 20), result.data)
    }

    @Test
    fun `snapshot shorter than buffer returns only the requested tail`() {
        val buffer = RingBuffer(capacityBytes = 10, bytesPerSecond = 1000) // 1 byte per ms
        buffer.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))

        val result = buffer.snapshot(durationMillis = 3)

        assertArrayEquals(byteArrayOf(8, 9, 10), result.data)
    }

    @Test
    fun `snapshot longer than buffered content returns everything available, not an error`() {
        val buffer = RingBuffer(capacityBytes = 100, bytesPerSecond = 1000)
        buffer.write(byteArrayOf(1, 2, 3, 4, 5))

        val result = buffer.snapshot(durationMillis = 10_000)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), result.data)
    }

    @Test
    fun `snapshot ordering is correct across the wrap boundary`() {
        val buffer = RingBuffer(capacityBytes = 6, bytesPerSecond = 1000)
        buffer.write(byteArrayOf(1, 2, 3, 4, 5, 6)) // exact fill, pos wraps to 0
        buffer.write(byteArrayOf(7, 8, 9)) // physical layout becomes [7,8,9,4,5,6]

        val result = buffer.snapshot(durationMillis = 5)

        // Logical last 5 bytes of stream 1..9 are 5,6,7,8,9 -- spanning the physical wrap
        // point between index 5 (value 6) and index 0 (value 7).
        assertArrayEquals(byteArrayOf(5, 6, 7, 8, 9), result.data)
    }

    @Test
    fun `snapshot on empty buffer returns empty data`() {
        val buffer = RingBuffer(capacityBytes = 10, bytesPerSecond = 1000)

        val result = buffer.snapshot(durationMillis = 1000)

        assertEquals(0, result.data.size)
    }

    @Test
    fun `snapshot derives start timestamp from the nearest per-frame marker`() {
        // One write() call per byte, each stamped with an increasing millisecond timestamp;
        // bytesPerSecond = 1000 makes 1 byte equal 1 ms so the math is exact.
        val clock = scriptedClock(1000, 1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008, 1009)
        val buffer = RingBuffer(capacityBytes = 10, bytesPerSecond = 1000, clock = clock)
        for (value in 0 until 10) {
            buffer.write(byteArrayOf(value.toByte()))
        }

        // Last 4 bytes: stream offsets 6..9, whose write() calls were stamped 1006..1009.
        val result = buffer.snapshot(durationMillis = 4)

        assertArrayEquals(byteArrayOf(6, 7, 8, 9), result.data)
        assertEquals(1006L, result.startTimestampMillis)
    }

    @Test
    fun `concurrent writer and reader never observe a torn frame`() {
        val frameSize = 4
        val bufferedFrames = 256
        val buffer = RingBuffer(capacityBytes = frameSize * bufferedFrames, bytesPerSecond = frameSize * 1000)

        val writerDone = AtomicBoolean(false)
        val tornFrameSeen = AtomicBoolean(false)
        val readsPerformed = AtomicInteger(0)
        val iterations = 20_000

        val writer = Thread({
            for (i in 0 until iterations) {
                // Every byte of this frame carries the same value, so any read that observes a
                // partially-overwritten frame (a "torn" frame) is caught by the reader below.
                val value = (i and 0xFF).toByte()
                buffer.write(byteArrayOf(value, value, value, value))
            }
            writerDone.set(true)
        }, "test-writer")

        val reader = Thread({
            while (!writerDone.get()) {
                val data = buffer.snapshot(durationMillis = 10_000).data
                assertTrue("snapshot length must be frame-aligned", data.size % frameSize == 0)
                var offset = 0
                while (offset < data.size) {
                    val b0 = data[offset]
                    if (data[offset + 1] != b0 || data[offset + 2] != b0 || data[offset + 3] != b0) {
                        tornFrameSeen.set(true)
                    }
                    offset += frameSize
                }
                readsPerformed.incrementAndGet()
            }
        }, "test-reader")

        reader.start()
        writer.start()
        writer.join()
        reader.join()

        assertTrue("expected the reader to actually exercise concurrent snapshots", readsPerformed.get() > 0)
        assertTrue("observed a torn frame during concurrent write+read", !tornFrameSeen.get())
    }
}
