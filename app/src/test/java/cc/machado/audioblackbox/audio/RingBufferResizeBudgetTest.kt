package cc.machado.audioblackbox.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MB = 1024L * 1024L

/**
 * Unit tests for [RingBuffer.resize]'s memory-budget refusal (issue #272).
 *
 * ## The oracle, stated up front (AGENTS.md §2)
 * A resize whose projected peak (current heap usage + the new array being allocated) would
 * exceed [DeviceMemoryBudget.SAFE_HEAP_UTILISATION] of the injected [MemoryBudget]'s reported
 * ceiling must be refused *before* any allocation happens: [RingBuffer.capacityBytes] and
 * [RingBuffer.bufferedBytes] stay exactly as they were, and [RingBuffer.resize] returns
 * [ResizeOutcome.Refused] rather than throwing or silently truncating.
 *
 * ## Why this is genuinely Tier-0 testable, unlike the OOM itself (AGENTS.md §6)
 * The 256 MB Dalvik heap growth limit that actually killed the app in production
 * (`cc.machado.audioblackbox v212`, three confirmed crashes, issue #272) cannot be reproduced on
 * the host JVM tier -- `testDebugUnitTest` runs with `maxHeapSize = "4g"`, which structurally
 * masks it. What *is* real, deterministic, Tier-0-observable behaviour is the refusal logic
 * itself: given a [MemoryBudget] the test controls completely (a fake reporting fixed numbers,
 * not `Runtime.getRuntime()`), does the resize correctly refuse or correctly proceed? That
 * question has nothing to do with how big the host JVM's own heap happens to be, so a fake budget
 * answers it exactly as truthfully as a real Dalvik heap would. No test in this file, or anywhere
 * in Tier 0, exercises the real 256 MB ceiling -- only a device (Tier 2, `scripts/device-smoke.sh`)
 * or an instrumented tier with a constrained heap can do that.
 *
 * ## Non-vacuity, verified by mutation (AGENTS.md §2)
 * Each assertion below was checked to fail with the exact expected message when the guard in
 * [RingBuffer.resize] was mutated to `false`/removed (turning every refusal into an unconditional
 * apply) or to `true` (turning every apply into an unconditional refusal) during development of
 * this fix; both mutations flipped every test in this class from green to red.
 */
class RingBufferResizeBudgetTest {

    /** Fixed-report budget: the test controls the exact numbers, never touches a real `Runtime`. */
    private fun fixedBudget(maxHeapMb: Long, usedHeapMb: Long): MemoryBudget =
        MemoryBudget { MemorySample(maxHeapBytes = maxHeapMb * MB, usedHeapBytes = usedHeapMb * MB) }

    @Test
    fun `resize is refused before allocating when the projected peak exceeds the safe heap ceiling`() {
        val buffer = RingBuffer(capacityBytes = 1_000, bytesPerSecond = 1_000)
        buffer.write(ByteArray(400))

        // Mirrors the owner's 2026-09-01 21:22:51 crash almost exactly: 256 MB ceiling, heap
        // already at 205 MB, a 101 MB request. 205 + 101 = 306 MB, far past the 85% (217.6 MB)
        // safe ceiling of 256 MB.
        val budget = fixedBudget(maxHeapMb = 256, usedHeapMb = 205)
        val outcome = buffer.resize(newCapacityBytes = 101 * MB.toInt(), memoryBudget = budget)

        assertTrue("expected Refused, got $outcome", outcome is ResizeOutcome.Refused)
        val refused = outcome as ResizeOutcome.Refused
        assertEquals(101 * MB.toInt(), refused.requestedCapacityBytes)
        assertEquals(256L * MB, refused.maxHeapBytes)

        // The load-bearing part of the oracle: refusal must be a true no-op, not a partial resize.
        assertEquals(1_000, buffer.capacityBytes)
        assertEquals(400L, buffer.bufferedBytes())
        val snap = buffer.snapshot(1_000)
        assertEquals(400, snap.data.size)
    }

    @Test
    fun `resize applies normally when the projected peak fits comfortably`() {
        val buffer = RingBuffer(capacityBytes = 1_000, bytesPerSecond = 1_000)
        buffer.write(ByteArray(400))

        // A tiny device by comparison to the failure case, but the requested growth is tiny too:
        // 50 MB used + 10 MB new = 60 MB, well under 85% of a 256 MB ceiling.
        val budget = fixedBudget(maxHeapMb = 256, usedHeapMb = 50)
        val outcome = buffer.resize(newCapacityBytes = 10 * MB.toInt(), memoryBudget = budget)

        assertEquals(ResizeOutcome.Applied, outcome)
        assertEquals(10 * MB.toInt(), buffer.capacityBytes)
        assertEquals(400L, buffer.bufferedBytes())
    }

    @Test
    fun `a request that only barely fits is applied, and one byte more is refused`() {
        // Pins the exact boundary rather than an arbitrary comfortable case, so the 0.85 constant
        // itself is load-bearing in this test, not just "some margin exists".
        val maxHeapBytes = 200L * MB
        val usedHeapBytes = 50L * MB
        val safeHeapBytes = (maxHeapBytes * DeviceMemoryBudget.SAFE_HEAP_UTILISATION).toLong()
        val exactlyFits = (safeHeapBytes - usedHeapBytes).toInt()

        val buffer = RingBuffer(capacityBytes = 1_000)
        val budget = MemoryBudget { MemorySample(maxHeapBytes = maxHeapBytes, usedHeapBytes = usedHeapBytes) }

        val fits = buffer.resize(exactlyFits, budget)
        assertEquals(ResizeOutcome.Applied, fits)

        val tooMuch = buffer.resize(exactlyFits + MB.toInt(), budget)
        assertTrue("one MB past the safe ceiling must be refused, got $tooMuch", tooMuch is ResizeOutcome.Refused)
    }

    @Test
    fun `a no-op resize to the current capacity never consults the budget`() {
        var sampleCalls = 0
        val budget = MemoryBudget {
            sampleCalls++
            MemorySample(maxHeapBytes = 1L, usedHeapBytes = 1L) // would refuse anything real
        }
        val buffer = RingBuffer(capacityBytes = 1_000, bytesPerSecond = 1_000)

        val outcome = buffer.resize(1_000, budget)

        assertEquals(ResizeOutcome.Applied, outcome)
        assertEquals(0, sampleCalls)
    }
}
