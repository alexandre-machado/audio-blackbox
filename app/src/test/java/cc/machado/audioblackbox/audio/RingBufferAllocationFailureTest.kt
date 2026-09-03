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
 *
 * Coverage spans both loops the recovery path has to patch up after: a mid-copy-loop failure and a
 * trailing-backfill-loop failure (issue #296, closing the gap `@rev`'s PR #295 review found between
 * this doc's claim and what was actually exercised). It does **not** cover a second failure during
 * recovery itself -- see [RingBuffer.resize]'s KDoc for why that residual case is left as a
 * documented limit rather than hardened here.
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
    fun `a resize that fails during the trailing backfill loop leaves the buffer coherent at the old capacity`() {
        // `@rev`'s LOW finding on PR #295 (folded into issue #296): both prior tests in this file
        // fail either before anything is touched, or mid-copy-loop -- neither exercises the
        // trailing `for (i in 0 until newChunkCount) if (newChunks[i] == null)` backfill loop that
        // runs after the copy loop completes. A small write (bytesToKeep << newCapacityBytes) makes
        // the copy loop finish in one chunk, so almost the entire new capacity is built by the
        // backfill loop instead -- exactly the gap to close.
        val chunkSizeBytes = 1_000
        val oldCapacityBytes = 5 * chunkSizeBytes
        val newCapacityBytes = 10 * chunkSizeBytes

        // Learn exactly how many residencyProbeForTesting invocations this specific resize makes
        // (a real, non-failing run) rather than hardcoding a fragile invocation number -- the last
        // invocation is, by construction below, inside the backfill loop.
        val probe = RingBuffer(capacityBytes = oldCapacityBytes, chunkSizeBytes = chunkSizeBytes)
        probe.write(ByteArray(chunkSizeBytes))
        var totalInvocations = 0
        probe.resize(newCapacityBytes) { _ -> totalInvocations++ }
        assertTrue("setup must actually invoke the probe", totalInvocations > 0)

        val buffer = RingBuffer(capacityBytes = oldCapacityBytes, chunkSizeBytes = chunkSizeBytes)
        buffer.write(ByteArray(chunkSizeBytes))

        var invocations = 0
        var failed = false
        try {
            buffer.resize(newCapacityBytes) { _ ->
                invocations++
                // Fail on the very last invocation. With bytesToKeep == chunkSizeBytes, the copy
                // loop copies everything in a single iteration (one destination chunk exactly
                // full), so it and the up-front retirement pass are done in the first handful of
                // invocations; the rest -- including this last one -- come from the trailing
                // backfill loop allocating the remaining, never-written new chunks.
                if (invocations == totalInvocations) throw InjectedAllocationFailure()
            }
        } catch (e: InjectedAllocationFailure) {
            failed = true
        }
        assertTrue("resize must propagate the injected failure rather than swallowing it", failed)
        assertEquals(
            "test setup must actually reach the last (backfill-loop) invocation before failing",
            totalInvocations,
            invocations,
        )

        assertEquals(
            "capacityBytes must revert to the old size after a failed resize",
            oldCapacityBytes,
            buffer.capacityBytes,
        )

        // The buffer must still be structurally usable: no dangling zero-length chunk should make
        // write/read throw.
        buffer.write(byteArrayOf(1, 2, 3))
        assertTrue("buffer must still hold buffered bytes after recovering from a failed resize", buffer.bufferedBytes() > 0)

        // The copy loop had already fully drained the retained window before the backfill loop (and
        // therefore the failure) ran, so all of it is honestly reported as lost rather than
        // silently returned.
        val stale = buffer.readSince(cursor = 0L, maxBytes = oldCapacityBytes)
        assertTrue(
            "a cursor from before the failure must be reported Lapped, not returned as Data",
            stale is ReadSinceResult.Lapped,
        )

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
