package cc.machado.audioblackbox.audio

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real writer thread racing a real drain thread over [RingBuffer.readSince] (issue #51).
 *
 * ## Why this is modelled on `CaptureContinuesDuringSnapshotTest`, and where it differs
 * That test's class doc records why an earlier version of it was vacuous: it wrote past capacity
 * and then asserted on `bufferedBytes()`, whose own saturating `minOf` returned `capacityBytes` on
 * a correct implementation *and* on one silently dropping bytes, so the assertion could not fail.
 * The same trap applies here in a sharper form: if the drain falls behind and the ring laps its
 * cursor, PCM is legitimately gone, so a reconstruction test that allows lapping would be
 * asserting on an outcome the implementation is allowed to shorten -- and could pass with real
 * bugs in it.
 *
 * So [drain thread reconstructs every byte the writer wrote, in order, while both run] keeps the
 * bytes written *during the race* strictly under `capacityBytes` (enforced by a `check`), which
 * makes lapping impossible by construction and turns the reconstruction into an exact,
 * byte-for-byte equality with no room to be masked. The buffer is pre-filled first so the race
 * still crosses the physical wrap point -- otherwise the wrap-handling half of `readSince` would
 * never execute here.
 *
 * The forced-lapping case is the other test, and it is the one issue #51 exists for.
 *
 * ## No sleeps, no retries
 * Ordering between the threads is established with `CountDownLatch` handshakes only. The bounded
 * `await`/`join` timeouts are stuck-test guards (fail loudly instead of hanging forever); they are
 * never the mechanism that makes the interleaving happen. Race windows are widened with real work
 * (`java.util.Arrays.fill` over a large scratch array, byte-by-byte verification of every drained
 * chunk), never by injecting delays into production code.
 */
class RingBufferReadSinceConcurrencyTest {

    private val bytesPerSecond = 16_000 // AudioConfig's 16 kHz mono default
    private val chunkSize = 320 // ~20 ms of PCM, same order as a real AudioRecord.read()

    /** Chunk carrying its own index (first 4 bytes, big-endian) plus an index-derived marker. */
    private fun chunkFor(index: Int): ByteArray {
        val chunk = ByteArray(chunkSize)
        java.util.Arrays.fill(chunk, (index % 256).toByte())
        chunk[0] = (index ushr 24).toByte()
        chunk[1] = (index ushr 16).toByte()
        chunk[2] = (index ushr 8).toByte()
        chunk[3] = index.toByte()
        return chunk
    }

    @Test
    fun `drain thread reconstructs every byte the writer wrote, in order, while both run`() {
        // Oracle: fails if readSince and write can interleave badly -- a byte dropped, duplicated,
        // reordered, or read half-written (the copy racing the writer across the wrap point), or a
        // nextCursor that does not exactly account for the bytes handed back. On a correct
        // implementation the concatenation of everything the drain thread received is bit-identical
        // to the sequence write() was called with; a single lost or torn byte makes the chunk-level
        // comparison below fail and names the chunk.
        val capacityBytes = bytesPerSecond * 5 // 80,000 bytes
        val writesToPerform = 200 // 64,000 bytes written during the race
        val racedBytes = writesToPerform.toLong() * chunkSize
        check(racedBytes < capacityBytes) {
            "test setup invariant: bytes written during the race must stay under capacityBytes, " +
                "or lapping becomes legal and the reconstruction assertion stops being exact"
        }

        val buffer = RingBuffer(capacityBytes = capacityBytes, bytesPerSecond = bytesPerSecond)

        // Pre-fill so the write head starts 60,000 bytes in: the 64,000 bytes written during the
        // race then cross the physical end of the array, exercising readSince's two-part copy.
        // These bytes are before the drain cursor, so they are never part of the expected output.
        val preFill = ByteArray(6_000)
        java.util.Arrays.fill(preFill, 0xEE.toByte())
        repeat(10) { buffer.write(preFill) }
        val startCursor = buffer.writeCursor()
        assertEquals(60_000L, startCursor)

        val startLatch = CountDownLatch(1)
        // Counted down by the drain thread the first time it actually receives bytes. The writer
        // blocks on it after its first chunk, so "a real drain read completed with 199 writes
        // still pending" is guaranteed by synchronization, not by hoping the scheduler runs the
        // drain thread before the writer finishes 200 fast in-memory writes.
        val firstDrainLatch = CountDownLatch(1)
        val writerDone = AtomicBoolean(false)
        val writerFailure = AtomicReference<Throwable?>(null)
        val drainFailure = AtomicReference<Throwable?>(null)
        val drainsDuringWriting = AtomicInteger(0)

        val writer = Thread({
            try {
                // Real work, not a sleep: filling a 256 KB scratch array between writes widens the
                // window in which the drain thread can observe a partially-advanced ring without
                // adding any delay to production code.
                val scratch = ByteArray(256 * 1024)
                startLatch.await()
                repeat(writesToPerform) { index ->
                    buffer.write(chunkFor(index))
                    if (index == 0 && !firstDrainLatch.await(30, TimeUnit.SECONDS)) {
                        throw AssertionError("drain thread never completed a concurrent read")
                    }
                    java.util.Arrays.fill(scratch, index.toByte())
                }
            } catch (t: Throwable) {
                writerFailure.set(t)
            } finally {
                writerDone.set(true)
            }
        }, "test-writer")

        val drained = ByteArrayOutputStream()
        val drainer = Thread({
            try {
                startLatch.await()
                var cursor = startCursor
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (drained.size() < racedBytes) {
                    if (System.nanoTime() > deadline) {
                        throw AssertionError("drain did not reach $racedBytes bytes in time")
                    }
                    when (val result = buffer.readSince(cursor, maxBytes = 4096)) {
                        is ReadSinceResult.Data -> {
                            if (result.startCursor != cursor) {
                                throw AssertionError("startCursor ${result.startCursor} != requested $cursor")
                            }
                            if (result.nextCursor != cursor + result.bytes.size) {
                                throw AssertionError(
                                    "nextCursor ${result.nextCursor} does not account for " +
                                        "${result.bytes.size} bytes from $cursor"
                                )
                            }
                            if (result.bytes.isNotEmpty()) {
                                drained.write(result.bytes)
                                cursor = result.nextCursor
                                if (!writerDone.get()) drainsDuringWriting.incrementAndGet()
                                firstDrainLatch.countDown()
                            }
                        }
                        // Both are failures *of this test's setup invariant*: with the raced bytes
                        // held under capacity, a correct implementation can never report either.
                        is ReadSinceResult.Lapped ->
                            throw AssertionError("unexpected lapping with raced bytes under capacity: $result")
                        is ReadSinceResult.StreamReset ->
                            throw AssertionError("unexpected stream reset: $result")
                    }
                }
            } catch (t: Throwable) {
                drainFailure.set(t)
            }
        }, "test-drainer")

        writer.start()
        drainer.start()
        startLatch.countDown()
        writer.join(TimeUnit.SECONDS.toMillis(60))
        drainer.join(TimeUnit.SECONDS.toMillis(60))

        assertNull("writer thread threw", writerFailure.get())
        assertNull("drain thread threw", drainFailure.get())
        assertTrue("writer did not finish", writerDone.get())
        assertTrue(
            "expected at least one drain read to complete while the writer was still writing",
            drainsDuringWriting.get() > 0,
        )

        val bytes = drained.toByteArray()
        assertEquals(racedBytes.toInt(), bytes.size)
        for (index in 0 until writesToPerform) {
            val offset = index * chunkSize
            val expected = chunkFor(index)
            val actual = bytes.copyOfRange(offset, offset + chunkSize)
            assertTrue(
                "drained chunk $index differs from what write() was called with",
                expected.contentEquals(actual),
            )
        }
    }

    @Test
    fun `a stalled drain whose cursor the writer laps is told, with the exact loss, not given a short read`() {
        // Oracle: THE test for issue #51's blocking requirement. The drain thread reads once, is
        // held at that cursor by a latch while a real writer thread overwrites the whole ring past
        // it, and then reads again. On a correct implementation the second read is Lapped, naming
        // the 4,800 bytes (300 ms of audio) that no longer exist and where to resume. It fails if
        // readSince instead returns Data -- i.e. if it clamps the request to whatever survived,
        // which is the exact "black box that loses audio while telling the user everything is
        // fine" failure this issue was opened to prevent. It also fails if the loss accounting is
        // wrong, since the consumer (issue #54) has to report a real number to the user.
        //
        // Nothing here depends on scheduling luck: the writer cannot lap until the drain thread
        // has read, and the drain thread cannot read again until the writer has lapped.
        val capacityBytes = 8_000 // 500 ms at 16 kHz mono
        val buffer = RingBuffer(capacityBytes = capacityBytes, bytesPerSecond = bytesPerSecond)

        val startLatch = CountDownLatch(1)
        val firstChunkWritten = CountDownLatch(1)
        val drainReadOnce = CountDownLatch(1)
        val writerLapped = CountDownLatch(1)

        val firstRead = AtomicReference<ReadSinceResult?>(null)
        val secondRead = AtomicReference<ReadSinceResult?>(null)
        val writerFailure = AtomicReference<Throwable?>(null)
        val drainFailure = AtomicReference<Throwable?>(null)

        // 40 chunks = 12,800 bytes, comfortably more than the 8,000-byte ring, so the drain
        // cursor at byte 320 is guaranteed to be overwritten rather than merely approached.
        val lappingWrites = 40
        val writer = Thread({
            try {
                val scratch = ByteArray(256 * 1024)
                startLatch.await()
                buffer.write(chunkFor(0))
                firstChunkWritten.countDown()
                if (!drainReadOnce.await(30, TimeUnit.SECONDS)) {
                    throw AssertionError("drain thread never performed its first read")
                }
                // Overwrite far past the drain cursor. Real work between writes (a 256 KB fill)
                // keeps the writer honestly interleaved rather than executing as one uninterrupted
                // burst the drain thread could never observe mid-flight.
                repeat(lappingWrites) { index ->
                    buffer.write(chunkFor(index + 1))
                    java.util.Arrays.fill(scratch, index.toByte())
                }
                writerLapped.countDown()
            } catch (t: Throwable) {
                writerFailure.set(t)
            }
        }, "test-lapping-writer")

        val drainer = Thread({
            try {
                startLatch.await()
                if (!firstChunkWritten.await(30, TimeUnit.SECONDS)) {
                    throw AssertionError("writer never produced the first chunk")
                }
                val first = buffer.readSince(cursor = 0, maxBytes = capacityBytes)
                firstRead.set(first)
                drainReadOnce.countDown()
                if (!writerLapped.await(30, TimeUnit.SECONDS)) {
                    throw AssertionError("writer never lapped the buffer")
                }
                secondRead.set(
                    buffer.readSince(
                        (first as ReadSinceResult.Data).nextCursor,
                        maxBytes = capacityBytes,
                    )
                )
            } catch (t: Throwable) {
                drainFailure.set(t)
            }
        }, "test-stalled-drainer")

        writer.start()
        drainer.start()
        startLatch.countDown()
        writer.join(TimeUnit.SECONDS.toMillis(60))
        drainer.join(TimeUnit.SECONDS.toMillis(60))

        assertNull("writer thread threw", writerFailure.get())
        assertNull("drain thread threw", drainFailure.get())

        val first = firstRead.get()
        assertTrue("first read should be plain Data, got $first", first is ReadSinceResult.Data)
        assertEquals(chunkSize, (first as ReadSinceResult.Data).bytes.size)

        val second = secondRead.get()
        assertTrue("stalled drain must be told it was lapped, got $second", second is ReadSinceResult.Lapped)
        second as ReadSinceResult.Lapped

        // 41 chunks written in total (13,120 bytes); the ring keeps the last 8,000, so the oldest
        // surviving stream offset is 5,120 and the drain cursor at 320 lost 4,800 bytes. The
        // `check` keeps that setup arithmetic honest instead of assumed.
        val totalWritten = (lappingWrites + 1).toLong() * chunkSize
        val expectedOldest = totalWritten - capacityBytes
        check(expectedOldest > chunkSize) {
            "test setup invariant: the writer must overwrite past the drain cursor ($chunkSize), " +
                "oldest surviving offset was $expectedOldest"
        }
        assertEquals(chunkSize.toLong(), second.requestedCursor)
        assertEquals(expectedOldest, second.oldestAvailableCursor)
        assertEquals(expectedOldest - chunkSize, second.lostBytes)

        // And the documented recovery path actually works: resuming where Lapped points yields
        // exactly the audio that survived, so a consumer can report the gap and carry on.
        val resumed = buffer.readSince(second.oldestAvailableCursor, maxBytes = capacityBytes)
        assertTrue("resume from oldestAvailableCursor must succeed, got $resumed", resumed is ReadSinceResult.Data)
        assertEquals(capacityBytes, (resumed as ReadSinceResult.Data).bytes.size)
    }

    @Test
    fun `concurrent readSince drain plus snapshot while writer is actively writing causes zero frame drops`() {
        val capacityBytes = bytesPerSecond * 5 // 80,000 bytes
        val writesToPerform = 200 // 64,000 bytes (< 80,000 bytes, so no lapping)
        val racedBytes = writesToPerform.toLong() * chunkSize

        val buffer = RingBuffer(capacityBytes = capacityBytes, bytesPerSecond = bytesPerSecond)
        val startLatch = CountDownLatch(1)
        val firstDrainLatch = CountDownLatch(1)
        val firstSnapshotLatch = CountDownLatch(1)
        val writerDone = AtomicBoolean(false)
        val writerFailure = AtomicReference<Throwable?>(null)
        val drainFailure = AtomicReference<Throwable?>(null)
        val snapshotFailure = AtomicReference<Throwable?>(null)
        val snapshotsTaken = AtomicInteger(0)

        val writer = Thread({
            try {
                val scratch = ByteArray(128 * 1024)
                startLatch.await()
                repeat(writesToPerform) { index ->
                    buffer.write(chunkFor(index))
                    if (index == 0) {
                        if (!firstDrainLatch.await(30, TimeUnit.SECONDS)) {
                            throw AssertionError("drain thread never ran first read")
                        }
                        if (!firstSnapshotLatch.await(30, TimeUnit.SECONDS)) {
                            throw AssertionError("snapshot thread never ran first snapshot")
                        }
                    }
                    java.util.Arrays.fill(scratch, index.toByte())
                }
            } catch (t: Throwable) {
                writerFailure.set(t)
            } finally {
                writerDone.set(true)
            }
        }, "test-writer-threeway")

        val drained = ByteArrayOutputStream()
        val drainer = Thread({
            try {
                startLatch.await()
                var cursor = 0L
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (drained.size() < racedBytes) {
                    if (System.nanoTime() > deadline) {
                        throw AssertionError("drain timed out waiting for $racedBytes bytes")
                    }
                    when (val result = buffer.readSince(cursor, maxBytes = 4096)) {
                        is ReadSinceResult.Data -> {
                            if (result.bytes.isNotEmpty()) {
                                drained.write(result.bytes)
                                cursor = result.nextCursor
                                firstDrainLatch.countDown()
                            }
                        }
                        is ReadSinceResult.Lapped -> throw AssertionError("unexpected lapping: $result")
                        is ReadSinceResult.StreamReset -> throw AssertionError("unexpected stream reset: $result")
                    }
                }
            } catch (t: Throwable) {
                drainFailure.set(t)
            }
        }, "test-drainer-threeway")

        val snapshotter = Thread({
            try {
                startLatch.await()
                while (!writerDone.get()) {
                    val snap = buffer.snapshot(2_000L)
                    if (snap.data.isNotEmpty()) {
                        snapshotsTaken.incrementAndGet()
                        firstSnapshotLatch.countDown()
                    }
                }
            } catch (t: Throwable) {
                snapshotFailure.set(t)
            }
        }, "test-snapshotter-threeway")

        writer.start()
        drainer.start()
        snapshotter.start()

        startLatch.countDown()

        writer.join(30_000)
        drainer.join(30_000)
        snapshotter.join(30_000)

        assertNull("writer failed: ${writerFailure.get()}", writerFailure.get())
        assertNull("drainer failed: ${drainFailure.get()}", drainFailure.get())
        assertNull("snapshotter failed: ${snapshotFailure.get()}", snapshotFailure.get())

        assertEquals(racedBytes, drained.size().toLong())
        val reconstructed = drained.toByteArray()
        repeat(writesToPerform) { i ->
            val expected = chunkFor(i)
            val actual = reconstructed.copyOfRange(i * chunkSize, (i + 1) * chunkSize)
            assertTrue("chunk $i mismatch in three-way test", expected.contentEquals(actual))
        }
        assertTrue("at least one snapshot should have run concurrently", snapshotsTaken.get() > 0)
    }

}
