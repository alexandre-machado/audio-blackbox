package cc.machado.audioblackbox.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MB = 1024L * 1024L

/**
 * Regression test for issue #277: [RingBuffer.resize] must not need the old and new backing
 * stores to coexist at full capacity, so a resize that only grows capacity by a small amount is
 * no longer refused just because the *requested* capacity, added on top of the heap the
 * already-resident old buffer occupies, looks unaffordable.
 *
 * ## The oracle, stated up front (AGENTS.md §2)
 * Before #277, [RingBuffer.resize]'s budget check computed its projected peak as
 * `usedHeapBytes + newCapacityBytes` -- i.e. it assumed the old backing store (already counted
 * inside `usedHeapBytes`, since it is already resident) and the new one had to be simultaneously
 * live, so it double-counted the old capacity. That formula refuses a resize whenever
 * `usedHeapBytes + newCapacityBytes` alone exceeds the safe ceiling, even when the *actual*
 * post-resize footprint (`usedHeapBytes - oldCapacityBytes + newCapacityBytes`, since the old
 * store is freed as the new one is built) fits comfortably. This test picks numbers where that
 * distinction is the entire question: a device whose heap is already mostly occupied *by the
 * ring buffer's own current (large) capacity*, requesting only a small net increase.
 *
 * ## Why "guard formula passes" is not the same as "the peak is actually small" (`@rev` PR #295
 * review, finding #1)
 * The guard's verdict alone does not prove the real transient peak stayed small: a first version
 * of this fix only dropped old chunks that the copy loop happened to visit while copying retained
 * bytes, so an old chunk holding *no* retained bytes (because the buffer had never been written,
 * or had been written to only a small fraction of its capacity) was never dropped until the very
 * end of [RingBuffer.resize] -- by which point the entire new capacity had already been built.
 * Worst case (`bytesToKeep == 0`, a resize before any [RingBuffer.write]): *zero* old chunks were
 * ever dropped before the new store was fully allocated, so the guard's own headline scenario
 * (this class's first test below, which never calls `write()`) silently exercised exactly the
 * full old-plus-new peak the guard's formula assumes never happens. [RingBuffer.resize]'s
 * `residencyProbeForTesting` parameter exists so this file can assert on the *real* peak -- the
 * exact byte count strongly reachable through the buffer's internal chunk arrays at every point
 * during a resize -- rather than only on whether the call returned [ResizeOutcome.Applied].
 *
 * ## Non-vacuity, verified by mutation
 * Two independent mutations were applied by hand during development of this fix, each observed to
 * flip the relevant assertion(s) below from green to red, then reverted:
 * 1. Reverting the guard in [RingBuffer.resize] to the pre-#277 formula (`sample.usedHeapBytes +
 *    newCapacityBytes`, dropping the `netGrowthBytes` calculation) flipped `growing a buffer that
 *    already occupies most of the heap succeeds when only the net growth is small` from Applied to
 *    Refused.
 * 2. Disabling [RingBuffer]'s up-front `retireUntouchedOldChunksLocked` pass (making it a no-op
 *    that returns `0L` without touching `chunks`) flipped every peak assertion in `resizing an
 *    empty buffer never lets the full old and new capacity coexist` and `resizing a sparsely
 *    written buffer only ever keeps the touched old chunks resident` from passing to failing --
 *    the measured peak jumped to (approximately) `oldCapacityBytes + newCapacityBytes`, exactly
 *    the coexistence peak #277 exists to eliminate. See the PR description for the exact observed
 *    numbers.
 */
class RingBufferSingleAllocationTest {

    @Test
    fun `growing a buffer that already occupies most of the heap succeeds when only the net growth is small`() {
        // The buffer's *current* capacity is already 200 MB, and that capacity is already
        // counted inside usedHeapBytes below (it is resident, not hypothetical) -- exactly the
        // scenario where the pre-#277 formula's double count of the old capacity bites.
        val oldCapacityBytes = (200 * MB).toInt()
        val newCapacityBytes = (201 * MB).toInt()
        val buffer = RingBuffer(capacityBytes = oldCapacityBytes, bytesPerSecond = 1_000)

        // usedHeapBytes (205 MB) already includes the 200 MB the buffer currently occupies, plus
        // ~5 MB of everything else the app has resident. maxHeapBytes/safe ceiling mirror the
        // owner's real crash log (256 MB growth limit, 85% safe utilisation -> 217.6 MB).
        val budget = MemoryBudget {
            MemorySample(maxHeapBytes = 256 * MB, usedHeapBytes = 205 * MB)
        }

        // The pre-#277 formula would compute usedHeapBytes + newCapacityBytes = 205 + 201 =
        // 406 MB, refusing even though the real net growth is only 1 MB. #277's fix computes
        // usedHeapBytes + netGrowthBytes(1 MB) + two chunks of slack, comfortably under 217.6 MB.
        val outcome = buffer.resize(newCapacityBytes, budget)

        assertTrue("expected Applied (net growth is only 1 MB), got $outcome", outcome is ResizeOutcome.Applied)
        assertEquals(newCapacityBytes, buffer.capacityBytes)
    }

    @Test
    fun `a resize that would still exceed the safe ceiling on true net growth is refused`() {
        // Sanity check that this is not "budget check removed": a request whose *net* growth
        // really does not fit is still refused, keeping #276's backstop in force for a genuinely
        // impossible size.
        val oldCapacityBytes = (10 * MB).toInt()
        val newCapacityBytes = (250 * MB).toInt() // net growth ~240 MB
        val buffer = RingBuffer(capacityBytes = oldCapacityBytes, bytesPerSecond = 1_000)

        val budget = MemoryBudget {
            MemorySample(maxHeapBytes = 256 * MB, usedHeapBytes = 10 * MB)
        }

        val outcome = buffer.resize(newCapacityBytes, budget)

        assertTrue("expected Refused (net growth ~240 MB does not fit), got $outcome", outcome is ResizeOutcome.Refused)
        assertEquals(oldCapacityBytes, buffer.capacityBytes)
    }

    /**
     * Runs [RingBuffer.resize] with [RingBuffer.resize]'s `residencyProbeForTesting` wired up and
     * returns the maximum reported value -- the true peak byte count strongly reachable through
     * the buffer's internal chunk storage at any instant during the call, not an estimate.
     */
    private fun resizeAndMeasurePeak(buffer: RingBuffer, newCapacityBytes: Int): Long {
        var peak = 0L
        val outcome = buffer.resize(newCapacityBytes) { residentBytes -> peak = maxOf(peak, residentBytes) }
        assertTrue("resize must apply for this test's scenario, got $outcome", outcome is ResizeOutcome.Applied)
        return peak
    }

    @Test
    fun `resizing an empty buffer never lets the full old and new capacity coexist`() {
        // Small chunk size so the test allocates real, small ByteArrays (this is a real resize,
        // not a fake budget) while still exercising many chunks -- 10 old chunks, 11 new chunks.
        val chunkSizeBytes = 1_000
        val oldCapacityBytes = 10 * chunkSizeBytes
        val newCapacityBytes = 11 * chunkSizeBytes
        val buffer = RingBuffer(capacityBytes = oldCapacityBytes, chunkSizeBytes = chunkSizeBytes)
        // Deliberately never write() -- this is exactly the bytesToKeep == 0 path finding #1
        // identified: the copy loop never runs at all, so every old chunk must be dropped by the
        // up-front pass, not by the loop.

        val peak = resizeAndMeasurePeak(buffer, newCapacityBytes)

        // The only real work here is building the new capacity; none of the old capacity should
        // ever be resident alongside it. A peak anywhere near oldCapacityBytes + newCapacityBytes
        // (21,000) would mean the old store stayed alive until the new one was fully built.
        assertTrue(
            "peak resident bytes ($peak) must not approach oldCapacityBytes + newCapacityBytes " +
                "(${oldCapacityBytes + newCapacityBytes}); expected close to newCapacityBytes " +
                "($newCapacityBytes) alone",
            peak <= newCapacityBytes + 2L * chunkSizeBytes,
        )
        assertEquals(newCapacityBytes.toLong(), peak) // exact: nothing old survives the up-front pass
    }

    @Test
    fun `resizing a sparsely written buffer only ever keeps the touched old chunks resident`() {
        val chunkSizeBytes = 1_000
        val oldCapacityBytes = 20 * chunkSizeBytes // 20 chunks
        val newCapacityBytes = 20 * chunkSizeBytes + 500 // 21 chunks
        val buffer = RingBuffer(capacityBytes = oldCapacityBytes, chunkSizeBytes = chunkSizeBytes)
        // Only the first 1.5 chunks' worth of the 20-chunk store are ever written -- 18 of 20 old
        // chunks hold nothing retained and must be dropped before any new chunk is allocated.
        buffer.write(ByteArray(chunkSizeBytes + chunkSizeBytes / 2))

        val peak = resizeAndMeasurePeak(buffer, newCapacityBytes)

        // Bound restated from RingBuffer.resize's class doc: at most max(old, new) + two chunks
        // of slack, never their sum (~20,500 + 500 = 21,000 vs. the coexistence peak of 40,500).
        val coexistencePeak = oldCapacityBytes + newCapacityBytes
        val claimedBound = maxOf(oldCapacityBytes, newCapacityBytes) + 2L * chunkSizeBytes
        assertTrue("peak ($peak) must stay within the claimed bound ($claimedBound)", peak <= claimedBound)
        assertTrue(
            "peak ($peak) must be far below the eliminated coexistence peak ($coexistencePeak)",
            peak < coexistencePeak - 15L * chunkSizeBytes,
        )
    }

    @Test
    fun `resizing a nearly full buffer keeps the same small peak the near-full case always had`() {
        val chunkSizeBytes = 1_000
        val oldCapacityBytes = 20 * chunkSizeBytes
        val newCapacityBytes = 21 * chunkSizeBytes
        val buffer = RingBuffer(capacityBytes = oldCapacityBytes, chunkSizeBytes = chunkSizeBytes)
        buffer.write(ByteArray(oldCapacityBytes)) // fill completely, so bytesToKeep == oldCapacityBytes

        val peak = resizeAndMeasurePeak(buffer, newCapacityBytes)

        val claimedBound = maxOf(oldCapacityBytes, newCapacityBytes) + 2L * chunkSizeBytes
        assertTrue("peak ($peak) must stay within the claimed bound ($claimedBound)", peak <= claimedBound)
    }
}
