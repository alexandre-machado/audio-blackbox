package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.RingBuffer
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Allocation-bound regression test for issue #72 -- OOM on export at large retention.
 *
 * ## Why "did the export throw OOM" is not an acceptable oracle here
 * The JVM unit test tier runs with a raised heap (~4GB, PR #69), specifically so slow/flaky
 * OOM-adjacent tests don't destabilize CI. A test that merely asserts "export() completed without
 * throwing" at a large retention window passes vacuously on *both* the pre-fix
 * `RingBuffer.snapshot()`-based path (which allocated a second full-window `ByteArray` on top of
 * the ring's own backing array) and this fix -- that gap is exactly how issue #72 reached a real
 * device (`heapGrowthLimit=256m`, no `largeHeap`) undetected by this repo's existing CI. This test
 * instead asserts directly on the *size* of every destination `ByteArray` the export path asks the
 * ring buffer to allocate, at two differently sized retention windows, and checks that size is
 * pinned to the drain chunk size and provably independent of the window -- an assertion that fails
 * deterministically for any implementation (buggy or fixed) that ever requests a
 * window-proportional allocation, on any heap size, without needing to actually exhaust memory.
 *
 * ## Why this would have caught the original bug
 * Before this fix, [ExportEngine]'s only way to pull PCM out of the ring buffer was
 * `RingBuffer.snapshot(durationMillis)`, which -- unchanged, still present, and exercised directly
 * for comparison below -- allocates exactly one `ByteArray` sized to `min(requestedBytes,
 * available)`: by construction, a single allocation that scales 1:1 with the requested window (see
 * `RingBuffer snapshot -- the primitive the original bug lived in`). `export never asks the ring
 * buffer for more than one drain chunk` then proves the production [ExportEngine.export] entry
 * point no longer goes through anything shaped like that: every request it makes to
 * [RingBuffer.readSince] is capped at `drainChunkSizeBytes`, at both a small and a ~40x larger
 * retention window -- had this fix instead routed the bounded plan through one `snapshot`-shaped
 * call (or through `readSince` with `maxBytes` set to the whole window, the misuse
 * [RingBuffer.readSince]'s own doc calls out `maxBytes` being non-defaulted to prevent), the
 * larger-window assertion below would fail.
 */
class BoundedExportAllocationTest {

    private val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1) // production default

    private class RecordingSink : ExportSink {
        val buffer = ByteArrayOutputStream()
        override fun open(displayName: String, mimeType: String): ExportTarget = object : ExportTarget {
            override val outputStream: OutputStream = buffer
            override fun commit() = Unit
            override fun abort() = Unit
        }
    }

    /** Fills a [RingBuffer] with [bufferedBytes] of audio and runs one export, recording the
     * `maxBytes` argument of every [RingBuffer.readSince] call the export makes -- exactly the
     * size of the `ByteArray` `readSince` allocates for that call (see its own doc: "Allocation +
     * copy are both under the lock ... bounded by `length` (<= maxBytes)"). Returns the largest
     * recorded value, i.e. the single biggest destination array the export path asked for. */
    private fun largestReadSinceRequestFor(bufferedBytes: Int, drainChunkSizeBytes: Int): Int {
        val ring = RingBuffer(capacityBytes = bufferedBytes, bytesPerSecond = config.bytesPerSecond)
        ring.write(ByteArray(bufferedBytes) { 1 })
        var largestRequest = 0
        val engine = ExportEngine(
            config = config,
            readSinceProvider = { cursor, maxBytes ->
                largestRequest = maxOf(largestRequest, maxBytes)
                ring.readSince(cursor, maxBytes)
            },
            writeCursorProvider = { ring.writeCursor() },
            oldestCursorProvider = { ring.oldestCursor() },
            estimateTimestampProvider = { offset -> ring.estimateTimestamp(offset) },
            gapsProvider = { emptyList() },
            sink = RecordingSink(),
            payloadEncoder = WavPayloadEncoder,
            drainChunkSizeBytes = drainChunkSizeBytes,
        )

        val durationMillis = (bufferedBytes.toLong() * 1000L) / config.bytesPerSecond
        val result = engine.export(durationMillis = durationMillis, minutesLabel = 1)
        assertTrue("export must succeed for this test's allocation assertion to mean anything", result is ExportState.Success)
        return largestRequest
    }

    @Test
    fun `export never asks the ring buffer for more than one drain chunk, independent of retention window size`() {
        val chunkSizeBytes = 4096

        // Exactly 2 drain chunks' worth of buffered audio -- a tiny retention window.
        val smallWindowLargestRequest = largestReadSinceRequestFor(bufferedBytes = 8_192, drainChunkSizeBytes = chunkSizeBytes)
        // ~40x the small window above (still small in absolute terms, kept fast for a unit test;
        // a real device's 45-minute retention window is thousands of times larger still than this
        // -- the point here is the *shape* of the allocation curve, which a 40x spread already
        // demonstrates without a slow multi-hundred-MB test run).
        val largeWindowLargestRequest = largestReadSinceRequestFor(bufferedBytes = 320_000, drainChunkSizeBytes = chunkSizeBytes)

        assertEquals(
            "the largest single ByteArray the export path ever asks RingBuffer.readSince to " +
                "allocate must be pinned to drainChunkSizeBytes",
            chunkSizeBytes,
            smallWindowLargestRequest,
        )
        assertEquals(
            "a ~40x larger retention window must not change the largest single allocation -- if " +
                "it did, peak export memory would again scale with the retention window (issue #72)",
            smallWindowLargestRequest,
            largeWindowLargestRequest,
        )
    }

    @Test
    fun `RingBuffer snapshot -- the primitive the original bug lived in -- allocates one ByteArray sized to the whole window, by contrast`() {
        // Pinned here, not to re-introduce snapshot() into the export path (it is deliberately
        // unused by ExportEngine as of this fix), but to document -- as an executable fact, not
        // just a comment -- exactly the allocation shape issue #72's fix moves away from: a single
        // destination array whose size is the requested window, which is why this repo's old
        // export path allocated a second full-size copy on top of the ring's own backing array.
        val smallWindowRing = RingBuffer(capacityBytes = 8_192, bytesPerSecond = config.bytesPerSecond)
        smallWindowRing.write(ByteArray(8_192) { 1 })
        val largeWindowRing = RingBuffer(capacityBytes = 320_000, bytesPerSecond = config.bytesPerSecond)
        largeWindowRing.write(ByteArray(320_000) { 1 })

        val smallSnapshotBytes = smallWindowRing.snapshot(durationMillis = 100_000L).data.size
        val largeSnapshotBytes = largeWindowRing.snapshot(durationMillis = 100_000L).data.size

        assertEquals(8_192, smallSnapshotBytes)
        assertEquals(320_000, largeSnapshotBytes)
        assertTrue(
            "snapshot()'s single allocation scales linearly with the window -- exactly the " +
                "shape issue #72's fix avoids for the export path",
            largeSnapshotBytes > smallSnapshotBytes,
        )
    }
}
