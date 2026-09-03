package cc.machado.audioblackbox.audio

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.machado.audioblackbox.export.ExportEngine
import cc.machado.audioblackbox.export.ExportSink
import cc.machado.audioblackbox.export.ExportState
import cc.machado.audioblackbox.export.ExportTarget
import cc.machado.audioblackbox.export.PayloadChunkSource
import cc.machado.audioblackbox.export.PayloadEncoder
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures how large a retention window this device can actually hold and export, on the real
 * Dalvik heap (issue #72 follow-up).
 *
 * ## Why this exists as a test and not an adb session
 * `AudioConfig` used to ship a fixed `RETENTION_WINDOW_MAX_MINUTES` of 45 (removed by issue #298
 * in favor of `DeviceMemoryBudget`'s per-device inference), and the comment justifying that number
 * described peak memory as "roughly 2x the retention window" because `RingBuffer.snapshot()`
 * allocated a second full-size copy at save time. **That has not been true since #72 was fixed in
 * #114**: the export
 * path drains through `readSince` in `drainChunkSizeBytes` chunks and never materialises the
 * window (see `BoundedExportAllocationTest`). The clamp is calibrated against a cost that no
 * longer exists, and the stale table outlived the design by three days and misled at least one
 * reader. A number living in a test cannot rot the same way: it re-runs, and it fails when the
 * thing it measured changes.
 *
 * ## What this measures, and what it does not
 * It measures the dominant term: allocating the ring buffer's backing array at a given capacity,
 * filling it, and draining a full export through it, all under this device's
 * `dalvik.vm.heapgrowthlimit`. The encoder and sink are deliberately lightweight, so the reported
 * peak is the *buffer + drain* cost, accurate to within the few MB a real AAC encoder adds -- not
 * a claim about the whole app's footprint with Compose resident.
 *
 * It says nothing about OEM memory management, the low-memory killer, or what survives hours in
 * the background. Those need a real device and real conditions.
 *
 * ## Reading the result
 * On an emulator with a *lower* growth limit than the target phone, a pass is a conservative
 * floor: what fits here fits there. The CI emulator is 192 MB against the S25's 256 MB, so
 * anything green here has ~25% of headroom unaccounted for on the real device.
 */
@RunWith(AndroidJUnit4::class)
class RetentionCeilingMeasurementTest {

    /** Counts bytes and discards them: the sink's cost must not pollute the buffer measurement. */
    private class CountingSink : ExportSink {
        var committedBytes = 0L
        override fun open(displayName: String, mimeType: String): ExportTarget =
            object : ExportTarget {
                override val outputStream: OutputStream = object : OutputStream() {
                    override fun write(b: Int) { committedBytes += 1 }
                    override fun write(b: ByteArray, off: Int, len: Int) { committedBytes += len }
                }
                override fun commit() = Unit
                override fun abort() = Unit
            }
    }

    /**
     * Passes chunks straight through. A real AAC encoder would add its own buffers, but they are
     * fixed-size and in the KB range -- irrelevant beside a backing array measured in hundreds of
     * MB, which is the term that decides whether the window fits.
     */
    private class PassthroughEncoder : PayloadEncoder {
        override val mimeType = "audio/L16"
        override val fileExtension = "pcm"
        override fun encode(
            config: AudioConfig,
            totalPayloadBytes: Long,
            chunks: PayloadChunkSource,
            out: OutputStream,
            isCancelled: () -> Boolean,
        ) {
            while (!isCancelled()) {
                val chunk = chunks.nextChunk() ?: break
                out.write(chunk, 0, chunk.size)
            }
        }
    }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    /** What one window's run cost: peak used heap, and the ring buffer's own backing array. */
    private data class Measurement(val peakBytes: Long, val backingBytes: Long) {
        /** Peak relative to the buffer itself. ~1.15 for a bounded drain; ~2.0 if the export
         * materialises the whole window again, which is issue #72's regression. */
        val peakToBacking: Float get() = peakBytes.toFloat() / backingBytes.toFloat()
    }

    /**
     * Allocates, fills and fully exports a window of [minutes], returning what it cost, or `null`
     * if the device could not do it.
     */
    private fun measure(minutes: Int): Measurement? {
        val config = AudioConfig(bufferDurationMinutes = minutes)
        val capacityBytes = config.totalBufferBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        Runtime.getRuntime().gc()
        val before = usedHeapBytes()

        var buffer: RingBuffer? = null
        return try {
            buffer = RingBuffer(
                capacityBytes = capacityBytes,
                bytesPerSecond = config.bytesPerSecond,
                clock = System::currentTimeMillis,
            )

            // Fill it for real. A buffer that is allocated but never written can sit on untouched
            // pages; the export has to read every byte, so the measurement has to write them.
            val block = ByteArray(64 * 1024) { (it % 251).toByte() }
            var written = 0L
            while (written < capacityBytes) {
                val n = minOf(block.size.toLong(), capacityBytes - written).toInt()
                buffer.write(block, 0, n)
                written += n
            }

            val sink = CountingSink()
            val engine = ExportEngine(
                config = config,
                readSinceProvider = { cursor, maxBytes -> buffer!!.readSince(cursor, maxBytes) },
                writeCursorProvider = { buffer!!.writeCursor() },
                oldestCursorProvider = { buffer!!.oldestCursor() },
                estimateTimestampProvider = { offset -> buffer!!.estimateTimestamp(offset) },
                gapsProvider = { emptyList() },
                sink = sink,
                payloadEncoder = PassthroughEncoder(),
            )

            val result = engine.export(
                durationMillis = minutes.toLong() * 60_000L,
                minutesLabel = minutes,
            )
            val peak = usedHeapBytes() - before
            if (result !is ExportState.Success) {
                Log.w(TAG, "$minutes min: export did not succeed: $result")
                return null
            }
            assertTrue("$minutes min: export wrote nothing", sink.committedBytes > 0)
            Measurement(peakBytes = peak, backingBytes = capacityBytes.toLong())
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "$minutes min: OOM -- ${e.message}")
            null
        } finally {
            buffer?.clear()
            @Suppress("UNUSED_VALUE")
            buffer = null
            Runtime.getRuntime().gc()
        }
    }

    /**
     * The permanent regression assertion.
     *
     * Two claims, and only the second one guards issue #72. That the shipped maximum allocates and
     * exports is worth checking but is not a regression test: @rev's review of this PR showed the
     * pre-#114 cost at 45 minutes (~82 MB backing plus an ~82 MB snapshot copy) still fits under
     * the CI emulator's 192 MB ceiling, so a success-only assertion stays green with the bug fully
     * present. The peak-to-backing ratio is what actually distinguishes a bounded drain from a
     * whole-window copy, on any device and at any heap size.
     */
    @Test
    fun theShippedMaximumRetentionWindowCanBeAllocatedAndExported() {
        val minutes = MEASURED_MAX_MINUTES
        val result = measure(minutes)
        assertEquals(
            "the shipped maximum retention window ($minutes min) must be exportable on this device",
            true,
            result != null,
        )
        val measurement = result!!
        Log.i(
            TAG,
            "shipped max $minutes min -> backing ${measurement.backingBytes / MB} MB, " +
                "peak ${measurement.peakBytes / MB} MB, ratio ${measurement.peakToBacking}",
        )

        // THE assertion that actually guards issue #72, and the reason "it exported without
        // throwing" is not enough on its own.
        //
        // @rev's finding on this PR: at the shipped 45-minute window the pre-#114 export cost
        // ~82 MB of backing plus an ~82 MB snapshot copy = ~164 MB, which still fits under the CI
        // emulator's 192 MB heap ceiling. So a success-only assertion stays green with the
        // regression fully present -- it was measuring the device, not the code. Only a window
        // large enough to actually exhaust the heap would have caught it, and the shipped maximum
        // deliberately is not that.
        //
        // The ratio is what distinguishes the two implementations regardless of heap size or
        // device: a bounded drain adds a fixed chunk on top of the buffer, so peak tracks backing
        // (measured 1.15-1.23 across 30-90 minute windows). Materialising the window again puts it
        // at ~2.0. The threshold sits above every measured value and well below the regression.
        assertTrue(
            "peak heap was ${measurement.peakToBacking}x the ring buffer's backing array " +
                "(${measurement.peakBytes / MB} MB peak vs ${measurement.backingBytes / MB} MB backing). " +
                "The export must add a bounded drain chunk, not a second copy of the window -- " +
                "anything approaching 2x means issue #72's snapshot allocation is back.",
            measurement.peakToBacking < MAX_PEAK_TO_BACKING_RATIO,
        )
    }

    /**
     * Walks the window upward past the shipped maximum until the device refuses, and reports the
     * largest that worked. Deliberately assertion-free above the shipped maximum: this is a
     * measurement, and failing CI because a *bigger-than-shipped* window did not fit would be
     * asserting something the app never promised.
     */
    @Test
    fun reportsTheLargestRetentionWindowThisDeviceCanExport() {
        val growthLimit = System.getProperty("dalvik.vm.heapgrowthlimit") ?: "unknown"
        Log.i(TAG, "=== retention ceiling measurement (heapgrowthlimit=$growthLimit) ===")

        var largestOk = 0
        for (minutes in CANDIDATE_MINUTES) {
            val measurement = measure(minutes)
            if (measurement == null) {
                Log.i(TAG, String.format("%4d min | %8s | DID NOT FIT", minutes, "-"))
                break
            }
            largestOk = minutes
            Log.i(
                TAG,
                String.format(
                    "%4d min | %5d MB | ok (backing %d MB, ratio %.2f)",
                    minutes,
                    measurement.peakBytes / MB,
                    measurement.backingBytes / MB,
                    measurement.peakToBacking,
                ),
            )
        }

        Log.i(TAG, "=== largest window that fit: $largestOk min ===")
        assertTrue(
            "not even the measured maximum ($MEASURED_MAX_MINUTES min) fit",
            largestOk >= MEASURED_MAX_MINUTES,
        )
    }

    private companion object {
        const val TAG = "RetentionCeiling"
        const val MB = 1024L * 1024L

        /**
         * Ceiling on peak-heap-over-backing-array. Measured values ran 1.15-1.23 across
         * 30-90 minute windows; issue #72's whole-window snapshot copy puts it at ~2.0. Set above
         * every observed value and far below the regression, so it discriminates the two
         * implementations rather than the device they run on.
         */
        const val MAX_PEAK_TO_BACKING_RATIO = 1.5f

        /**
         * The window this whole measurement is calibrated against -- historically
         * `AudioConfig.RETENTION_WINDOW_MAX_MINUTES`, now a local constant of this measurement
         * harness since issue #298 removed that product ceiling in favor of
         * [cc.machado.audioblackbox.audio.DeviceMemoryBudget]'s per-device inference. This harness
         * still measures against 45 min specifically because that is the exact window
         * `RetentionCeilingMeasurementTest`'s own history (issue #72) is calibrated against; it is
         * no longer "the shipped maximum" in the app itself.
         */
        const val MEASURED_MAX_MINUTES = 45

        /** Steps past [MEASURED_MAX_MINUTES], in the stepper's own 5-minute increments. */
        val CANDIDATE_MINUTES = listOf(30, 45, 60, 75, 90, 105, 120, 150, 180)
    }
}
