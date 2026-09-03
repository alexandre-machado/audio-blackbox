package cc.machado.audioblackbox.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for `@rev`'s HIGH finding on PR #295: [RingBuffer.resize] drops old chunks
 * incrementally as it copies them, before the new backing store is fully built and before
 * [RingBuffer.capacityBytes]/the buffer's internal accounting is updated -- so an allocation
 * failure partway through (a real [OutOfMemoryError] from a `ByteArray(...)` call; the failure
 * mode #272/#277 exist to make safe in the first place) used to leave the buffer with some
 * `chunks` entries zero-length while `_capacityBytes` still claimed the old, fully-backed size:
 * silent corruption for the rest of the session.
 *
 * ## Failure injection
 * There is no reliable way to force a real `OutOfMemoryError` at a precise instant in a JVM unit
 * test. [RingBuffer.resize]'s existing `residencyProbeForTesting` seam is invoked at every point
 * this method allocates or drops a chunk -- exactly the sites a real allocation failure would
 * originate from -- and a callback throwing from inside that seam lands inside the same `try`
 * block that wraps those allocation sites in production. This test reuses that seam rather than
 * adding a second, narrower one: no new production surface is introduced by this test.
 *
 * ## The invariant under test
 * Per [RingBuffer.resize]'s "Exception safety" doc: after the injected failure propagates out of
 * [RingBuffer.resize], the buffer must still be a coherent, usable buffer at its *old* capacity --
 * not the new one, and not a half-built hybrid. Concretely:
 * - [RingBuffer.capacityBytes] reverts to the pre-resize value.
 * - Every subsequent [RingBuffer.write]/[RingBuffer.snapshot]/[RingBuffer.readSince] call succeeds
 *   without throwing (no dangling zero-length chunk causes a negative-length `arraycopy`).
 * - Whatever old data the partial run actually destroyed while draining source chunks is reported
 *   as lost through the ordinary [ReadSinceResult.Lapped] path -- never silently returned as
 *   zero-filled bytes standing in for real audio.
 */
class RingBufferAllocationFailureTest {

    private class InjectedAllocationFailure : RuntimeException("simulated allocation failure")

    @Test
    fun `a resize that fails partway through leaves the buffer coherent at the old capacity`() {
        val chunkSizeBytes = 1_000
        val oldCapacityBytes = 20 * chunkSizeBytes
        val newCapacityBytes = 21 * chunkSizeBytes
        val buffer = RingBuffer(capacityBytes = oldCapacityBytes, chunkSizeBytes = chunkSizeBytes)
        buffer.write(ByteArray(oldCapacityBytes)) // fill completely so the copy loop does real work

        var invocations = 0
        var failed = false
        try {
            buffer.resize(newCapacityBytes) { _ ->
                invocations++
                // Fail partway through the copy loop, after some old chunks have already been
                // dropped and some new chunks already allocated, but before the method reaches its
                // final `chunks = newChunks...` reassignment.
                if (invocations == 5) throw InjectedAllocationFailure()
            }
        } catch (e: InjectedAllocationFailure) {
            failed = true
        }
        assertTrue("resize must propagate the injected failure rather than swallowing it", failed)
        assertTrue("test setup must actually reach the injected failure point", invocations >= 5)

        // Capacity must revert -- not left claiming the new size while `chunks` is still (partially)
        // the old, partially-dropped backing store.
        assertEquals(
            "capacityBytes must revert to the old size after a failed resize",
            oldCapacityBytes,
            buffer.capacityBytes,
        )

        // The buffer must still be structurally usable: no dangling zero-length chunk should make
        // write/read throw.
        buffer.write(byteArrayOf(1, 2, 3))
        assertTrue("buffer must still hold buffered bytes after recovering from a failed resize", buffer.bufferedBytes() > 0)

        // Whatever was destroyed while building the doomed new store must be honestly reported as
        // lost, not silently returned as zero-filled bytes standing in for real audio.
        val stale = buffer.readSince(cursor = 0L, maxBytes = oldCapacityBytes)
        assertTrue(
            "a cursor from before the failure must be reported Lapped, not returned as Data",
            stale is ReadSinceResult.Lapped,
        )

        // A cursor at the (now-advanced) oldest available position must still read real, working
        // data going forward -- the buffer keeps functioning past the point of the failure.
        val fresh = buffer.readSince(cursor = buffer.oldestCursor(), maxBytes = oldCapacityBytes + 3)
        assertTrue("reading from the current oldest cursor must succeed", fresh is ReadSinceResult.Data)
    }

    @Test
    fun `a resize that fails before any chunk is touched leaves the buffer completely untouched`() {
        // Failing on the very first probe invocation (right after the initial residency snapshot,
        // before retireUntouchedOldChunksLocked runs) must not lose anything at all: startOffset +
        // copied == startOffset, and nothing has been dropped yet.
        val chunkSizeBytes = 1_000
        val oldCapacityBytes = 20 * chunkSizeBytes
        val newCapacityBytes = 21 * chunkSizeBytes
        val buffer = RingBuffer(capacityBytes = oldCapacityBytes, chunkSizeBytes = chunkSizeBytes)
        buffer.write(ByteArray(oldCapacityBytes))
        val bufferedBeforeAttempt = buffer.bufferedBytes()

        var failed = false
        try {
            buffer.resize(newCapacityBytes) { _ -> throw InjectedAllocationFailure() }
        } catch (e: InjectedAllocationFailure) {
            failed = true
        }
        assertTrue("resize must propagate the injected failure", failed)

        assertEquals(oldCapacityBytes, buffer.capacityBytes)
        assertEquals(
            "no data should be lost when the failure happens before any chunk is dropped",
            bufferedBeforeAttempt,
            buffer.bufferedBytes(),
        )
        val readBack = buffer.readSince(cursor = buffer.oldestCursor(), maxBytes = oldCapacityBytes)
        assertTrue(readBack is ReadSinceResult.Data)
    }
}
