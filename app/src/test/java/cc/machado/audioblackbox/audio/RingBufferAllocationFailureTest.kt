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
 *
 * ## Oracle for the recovery patch loop itself (`@rev` PR #312 review, HIGH finding)
 * The tests that exercise the recovery `catch` block's patch loop assert coherence directly, via
 * [assertChunksCoherent] (reflection over [RingBuffer]'s private `chunks` field), rather than
 * inferring it from a downstream [RingBuffer.write] call: a corrupted (dangling zero-length) chunk
 * only causes [RingBuffer.write] to spin forever if a *subsequent* write happens to land on that
 * exact index, which is incidental to which index the patch loop actually failed to fix, and a
 * hang is a wedged CI job on this repo's shared self-hosted runner, not a clean, assertion-based
 * failure (AGENTS.md §3). Those tests also carry a JUnit `@Test(timeout = ...)` (same convention
 * already used in [cc.machado.audioblackbox.export.BoundedExportHangTest] for an analogous
 * uninterruptible-tight-loop hazard) as defense in depth for a future regression the direct
 * assertion doesn't happen to cover.
 */
class RingBufferAllocationFailureTest {

    private class InjectedAllocationFailure : RuntimeException("simulated allocation failure")

    /**
     * Reads [RingBuffer]'s private `chunks` field directly via reflection, deliberately bypassing
     * every public method that could itself spin forever on a dangling zero-length chunk (`@rev`'s
     * HIGH finding on PR #312: routing the oracle through a downstream [RingBuffer.write] call is
     * not an assertion-based oracle -- it is an incidental hang that depends on which chunk index
     * the write happens to land on, and a hang surfaces as a wedged job on this repo's shared
     * self-hosted runner, not a clean test failure). No new production surface: this reads existing
     * private state, it does not add a test seam to [RingBuffer] itself.
     */
    @Suppress("UNCHECKED_CAST")
    private fun chunksOf(buffer: RingBuffer): List<ByteArray> {
        val field = RingBuffer::class.java.getDeclaredField("chunks")
        field.isAccessible = true
        return field.get(buffer) as List<ByteArray>
    }

    /**
     * Direct, index-independent oracle for post-recovery coherence: every chunk of [buffer]'s
     * backing store must be a real array, correctly sized for [expectedCapacityBytes] at
     * [chunkSizeBytes] granularity -- i.e. the recovery patch loop
     * (`RingBuffer.kt`'s `catch` block) actually patched every index it dropped, not just the one a
     * subsequent write happens to touch. Fails with the specific mismatched index/size rather than
     * hanging.
     */
    private fun assertChunksCoherent(buffer: RingBuffer, expectedCapacityBytes: Int, chunkSizeBytes: Int) {
        val chunks = chunksOf(buffer)
        val expectedChunkCount = (expectedCapacityBytes + chunkSizeBytes - 1) / chunkSizeBytes
        assertEquals(
            "chunk list length must match the reverted (old) capacity",
            expectedChunkCount,
            chunks.size,
        )
        for (i in chunks.indices) {
            val expectedSize = minOf(chunkSizeBytes, expectedCapacityBytes - i * chunkSizeBytes)
            assertEquals(
                "chunk $i must be patched back to a real, correctly-sized array after recovery " +
                    "(no dangling zero-length EMPTY_CHUNK left over from a partial patch loop)",
                expectedSize,
                chunks[i].size,
            )
        }
    }

    @Test(timeout = 5_000L) // Fail loudly if the recovery patch loop leaves a dangling chunk (AGENTS.md §3)
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

        // Direct oracle: every chunk index the recovery patch loop dropped must have been patched
        // back to a real, correctly-sized array -- checked before touching any method that could
        // spin on a dangling chunk, not inferred from where a downstream write happens to land.
        assertChunksCoherent(buffer, expectedCapacityBytes = oldCapacityBytes, chunkSizeBytes = chunkSizeBytes)

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

    @Test(timeout = 5_000L) // Fail loudly if the recovery patch loop leaves a dangling chunk (AGENTS.md §3)
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

        // Direct oracle: every chunk index the recovery patch loop dropped must have been patched
        // back to a real, correctly-sized array -- checked before touching any method that could
        // spin on a dangling chunk, not inferred from where a downstream write happens to land.
        // This is the assertion that closes `@rev`'s HIGH finding on PR #312: the prior version of
        // this test relied on the write() call below to trip over corruption, which only happened
        // to work here because totalWritten % oldCapacityBytes landed on chunk index 1 -- an
        // incidental hang, not a real oracle, and specifically not one for any *other* dangling
        // index.
        assertChunksCoherent(buffer, expectedCapacityBytes = oldCapacityBytes, chunkSizeBytes = chunkSizeBytes)

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
