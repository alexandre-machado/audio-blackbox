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
 * This mirrors the owner's real 2026-09-01 crash pattern (heap already grown to ~205 MB, a
 * further resize refused) generalized to isolate exactly what #277 changes: the old code refused
 * because it double-counted the already-resident old capacity; the new code does not, because
 * there is no longer a double-allocation peak to guard against, only the real net growth.
 *
 * ## Non-vacuity, verified by mutation
 * Temporarily reverting the guard in [RingBuffer.resize] to the pre-#277 formula
 * (`sample.usedHeapBytes + newCapacityBytes`, dropping the `netGrowthBytes` calculation) flips
 * this test's outcome from [ResizeOutcome.Applied] to [ResizeOutcome.Refused] -- confirmed by
 * hand during development of this test, then reverted. See PR description for the exact mutation
 * and observed failure.
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
        // usedHeapBytes + netGrowthBytes(1 MB) + one chunk of slack, comfortably under 217.6 MB.
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
}
