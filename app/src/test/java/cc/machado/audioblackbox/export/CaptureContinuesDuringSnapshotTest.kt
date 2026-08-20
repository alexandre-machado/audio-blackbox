package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.RingBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves capture writes are not dropped or corrupted while a snapshot is being taken (issue #5
 * mandatory test). Exercises [RingBuffer] directly -- the real production dependency
 * [ExportEngine.export] calls through `snapshotProvider` -- rather than
 * [cc.machado.audioblackbox.audio.AudioCaptureEngine], both because that class is owned by a
 * concurrent PR (issue #26) and because the property under test ("does snapshot() ever cause
 * write() to lose or corrupt a frame") lives entirely inside [RingBuffer]'s own locking, which
 * this test drives directly with a real writer thread racing real snapshot calls.
 *
 * ## Why the total-write count stays under capacity (PR #28 review, `@rev` finding 3)
 * An earlier version of this test wrote 8x the buffer's capacity (2000 writes of 320 bytes into
 * an 80,000-byte buffer) and asserted `bufferedBytes() == minOf(totalWritten, capacityBytes)`.
 * `bufferedBytes()`'s own saturating `minOf` meant that assertion evaluated to `capacityBytes` on
 * *both* a correct implementation and one with a silent per-write byte undercount, or even with
 * `RingBuffer`'s locking removed entirely -- the assertion could not distinguish them, and neither
 * perturbation made the test fail (verified by actually applying both, one at a time, to the
 * unmodified `RingBuffer.kt` under test, observing this test still pass, then reverting -- see
 * PR #28 review comments). `RingBuffer` itself is untouched by this PR and out of scope to modify
 * (issue #22), so the fix has to live entirely in what this test asks of it.
 *
 * This version keeps `writesToPerform * chunkSize` strictly below `capacityBytes`, so the buffer
 * never wraps and `bufferedBytes()` can never saturate -- it is provably exactly
 * `totalBytesWritten` on a correct implementation, and *not* equal to it the moment a single byte
 * is silently dropped or miscounted anywhere in `write()`. On top of the byte count, every chunk
 * carries its own write index and a content marker, and the final full snapshot is checked chunk
 * by chunk against the exact sequence `write()` was called with -- catching not just a dropped
 * byte but a reordered or corrupted one, which a bare length check cannot.
 */
class CaptureContinuesDuringSnapshotTest {

    @Test
    fun `writer thread completes all writes with no drops or corruption while snapshots run concurrently`() {
        val bytesPerSecond = 16_000 // matches AudioConfig's 16kHz mono default
        val capacityBytes = bytesPerSecond * 5 // 80,000 bytes -- 5 seconds of retention

        val chunkSize = 320 // ~20ms of audio at 16kHz/16-bit mono, same order as a real AudioRecord.read()
        // Deliberately well under capacityBytes (64,000 < 80,000): see the class doc for why
        // staying under capacity, not wrapping past it, is what makes the final assertions below
        // load-bearing instead of saturation-masked.
        val writesToPerform = 200
        check(writesToPerform.toLong() * chunkSize < capacityBytes) {
            "test setup invariant: total writes must stay under capacityBytes, or bufferedBytes() saturates"
        }

        val buffer = RingBuffer(capacityBytes = capacityBytes, bytesPerSecond = bytesPerSecond)

        val totalBytesWritten = AtomicLong(0)
        val writerDone = AtomicBoolean(false)
        val writerFailure = AtomicBoolean(false)
        val startLatch = CountDownLatch(1)
        // Counted down by the snapshotter the first time it completes a real snapshot() call.
        // The writer blocks on this once, after its first write, so "at least one concurrent
        // snapshot ran" is guaranteed by synchronization rather than by hoping the OS scheduler
        // gives the snapshotter thread a timeslice before the writer -- which does 200 fast,
        // in-memory, no-I/O writes -- finishes on its own. On a contended/fewer-core machine the
        // snapshotter thread's first scheduling can lag long enough for the writer to finish
        // first, making `snapshotsTaken` legitimately zero without this: that was the flake.
        val firstSnapshotLatch = CountDownLatch(1)

        // Every chunk encodes its own write index (first 4 bytes, big-endian) plus a repeating
        // content marker (index mod 256) filling the rest -- lets the final check below prove the
        // buffer's retained bytes are the *exact*, *in-order*, *uncorrupted* sequence of writes,
        // not merely the right total length.
        fun chunkFor(index: Int): ByteArray {
            val chunk = ByteArray(chunkSize)
            chunk[0] = (index ushr 24).toByte()
            chunk[1] = (index ushr 16).toByte()
            chunk[2] = (index ushr 8).toByte()
            chunk[3] = index.toByte()
            val marker = (index % 256).toByte()
            for (i in 4 until chunkSize) chunk[i] = marker
            return chunk
        }

        val writer = Thread({
            startLatch.await()
            try {
                repeat(writesToPerform) { index ->
                    buffer.write(chunkFor(index))
                    totalBytesWritten.addAndGet(chunkSize.toLong())
                    if (index == 0) {
                        // Force at least one real snapshot() to complete concurrently, with the
                        // remaining 199 writes still pending, before the writer is allowed to
                        // race ahead and finish. Bounded wait is a stuck-test failure guard only
                        // (fails loudly instead of hanging) -- it does not change how likely the
                        // concurrent snapshot is, it is required before the writer may proceed.
                        if (!firstSnapshotLatch.await(30, TimeUnit.SECONDS)) {
                            throw AssertionError(
                                "snapshotter thread never completed a concurrent snapshot"
                            )
                        }
                    }
                }
            } catch (e: Throwable) {
                writerFailure.set(true)
            } finally {
                writerDone.set(true)
            }
        }, "test-writer")

        val snapshotFailure = AtomicBoolean(false)
        val snapshotsTaken = AtomicLong(0)
        val snapshotter = Thread({
            startLatch.await()
            try {
                // Hammer snapshot() concurrently with the writer until it finishes, proving the
                // writer is never starved/corrupted by concurrent readers.
                while (!writerDone.get()) {
                    val snap = buffer.snapshot(durationMillis = 1000)
                    // A torn read would show up as a length that doesn't correspond to any
                    // possible buffer state -- bounded by capacity is the invariant that must
                    // always hold.
                    if (snap.data.size > capacityBytes) throw AssertionError("snapshot exceeded capacity")
                    snapshotsTaken.incrementAndGet()
                    firstSnapshotLatch.countDown()
                }
            } catch (e: Throwable) {
                snapshotFailure.set(true)
            }
        }, "test-snapshotter")

        writer.start()
        snapshotter.start()
        startLatch.countDown()
        writer.join(TimeUnit.SECONDS.toMillis(30))
        snapshotter.join(TimeUnit.SECONDS.toMillis(30))

        assertTrue("writer thread did not finish in time", writerDone.get())
        assertTrue("writer thread threw", !writerFailure.get())
        assertTrue("snapshotter thread threw", !snapshotFailure.get())
        assertTrue("expected at least one concurrent snapshot to have run", snapshotsTaken.get() > 0)

        val expectedTotalBytes = writesToPerform.toLong() * chunkSize
        assertEquals(expectedTotalBytes, totalBytesWritten.get())

        // No saturation possible here (expectedTotalBytes < capacityBytes, enforced by the
        // `check` above), so this is an exact equality, not a "clamped to capacity" one -- a
        // silent per-write undercount anywhere in RingBuffer.write() would make these differ.
        assertEquals(expectedTotalBytes, buffer.bufferedBytes())

        // Full-content proof: every chunk the writer sent must be present, in order, unmodified.
        val finalSnapshot = buffer.snapshot(durationMillis = (capacityBytes.toLong() * 1000) / bytesPerSecond)
        assertEquals(expectedTotalBytes.toInt(), finalSnapshot.data.size)
        for (index in 0 until writesToPerform) {
            val offset = index * chunkSize
            val expected = chunkFor(index)
            val actual = finalSnapshot.data.copyOfRange(offset, offset + chunkSize)
            assertTrue("chunk $index does not match what write() was called with", expected.contentEquals(actual))
        }
    }
}
