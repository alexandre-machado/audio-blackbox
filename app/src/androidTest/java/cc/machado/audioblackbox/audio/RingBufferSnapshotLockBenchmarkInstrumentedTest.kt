package cc.machado.audioblackbox.audio

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures [RingBuffer.snapshot]'s real lock hold time **on ARM, on-device** (issue #297), at the
 * reachable worst case: [QualityPreset.HIGH_FIDELITY] at whatever ceiling the production
 * [DeviceMemoryBudget.maxRetentionMinutes] grants on *this* device, right now.
 *
 * ## Why this test exists at all
 * Every `snapshot()` lock-hold-time number this repo has ever recorded (#22, #69, #71) came from
 * [RingBufferSnapshotLockBenchmarkTest], which lives in the JVM unit-test tier and therefore only
 * ever ran on the CI runner's x86_64 host. Issue #71's safety margin was computed by taking that
 * x86 number and scaling it down linearly to a device-granted ceiling -- an extrapolation compared
 * against a genuinely ARM-measured `AudioRecord` headroom ([AudioRecordHeadroomInstrumentedTest]).
 * That comparison mixed an estimate with a measurement. This test produces the missing half: a real
 * ARM number for the same primitive, at the same methodology, so the margin can be restated from
 * two measured numbers instead of one measured and one extrapolated.
 *
 * ## Where this deliberately follows [RingBufferSnapshotLockBenchmarkTest], and where it deviates
 * **Same as the JVM benchmark:**
 * - What is timed: the entire `snapshot()` call, wall-clock, because (per that test's class doc)
 *   [RingBuffer.snapshot]'s whole body -- including the destination `ByteArray(length)` allocation
 *   -- runs inside its `synchronized(lock)` block, so the call's wall-clock duration *is* the lock
 *   hold time, not an approximation of it.
 * - The definition of "worst case": fill the buffer to full capacity, then request more duration
 *   than is buffered, forcing `snapshot()` to copy the entire backing store every call.
 * - Warmup/measured iteration counts and the best/median/worst-of-N reporting shape.
 *
 * **Deviates, and why:**
 * - **Only one config, not a five-point ladder.** The JVM benchmark measures a fixed ladder
 *   (5/15/30/45/60 min at 16kHz/mono, plus a hypothetical 44.1kHz/stereo/60min) because it has no
 *   device to ask for a real ceiling. This test has a real device, so it asks
 *   [DeviceMemoryBudget.maxRetentionMinutes] for [QualityPreset.HIGH_FIDELITY]'s actual reachable
 *   ceiling instead of hand-picking or hardcoding a duration -- the acceptance criterion this test
 *   exists to satisfy. Measuring a ladder of *unreachable* durations on-device would tell us nothing
 *   the JVM benchmark doesn't already show, and risks exactly the OOM issue #297 warns against
 *   (the old hypothetical 44.1kHz/stereo/60min / ~635 MB point is never constructed here).
 * - **HIGH_FIDELITY specifically, not the ladder's 16kHz/mono format.** HIGH_FIDELITY is this app's
 *   highest byte-rate preset (see [QualityPreset]'s class doc), so at any given device's memory
 *   budget it is the shortest *reachable* window -- and per this benchmark's own worst-case
 *   definition (fill to capacity, request more), lock hold time scales with buffer size, not with
 *   sample rate/channel count directly. A shorter HIGH_FIDELITY window is not obviously worse or
 *   better than a longer VOICE window for this specific measurement; HIGH_FIDELITY is chosen
 *   because it is the config #71's safety conclusion is actually about (the ARM `AudioRecord`
 *   headroom it is compared against is also HIGH_FIDELITY-shaped internal buffering), not because
 *   it is expected to produce the largest number.
 * - **Graceful skip, not a hard failure or an artificial floor.** If the device's granted ceiling
 *   somehow resolved below [AudioConfig.RETENTION_WINDOW_MIN_MINUTES] (structurally impossible per
 *   [DeviceMemoryBudget]'s own floor coercion, but checked here defensively rather than assumed) or
 *   an [OutOfMemoryError] is thrown while filling the buffer at the resolved size, the test uses
 *   [assumeTrue] to report a skip with a logged reason rather than a failure -- mirroring the JVM
 *   benchmark's own OOM-skip behaviour, adapted to JUnit's "ignored" mechanism so a skip is visibly
 *   distinct from a pass in the test report, not silently swallowed.
 * - **Fills the buffer in bounded chunks, not one monolithic `ByteArray(capacityBytes)`.** The JVM
 *   benchmark fills with a single `ByteArray(capacity)` (a 4g JVM heap absorbs that trivially). On
 *   a real device's much smaller, fragmentable Dalvik heap this is not representative *or* safe:
 *   [AudioCaptureEngine] never allocates a buffer-sized array either (it streams small `AudioRecord`
 *   reads), and a first attempt at this test proved the difference is real, not theoretical --
 *   requesting one ~151 MB contiguous array OOM'd on the Samsung S25's 256 MB heap even though
 *   [DeviceMemoryBudget] had granted that exact capacity from its own accounting. Filling via
 *   repeated bounded-size [RingBuffer.write] calls (matching how the real capture path streams
 *   data in) reaches the same fully-buffered worst-case state without that artificial, test-only
 *   allocation spike.
 *
 * ## Why this is a benchmark, not an assertion (oracle boundary)
 * Per AGENTS.md §2 (Vacuous-Test Rule), the only things asserted here are structural invariants true
 * by construction on any device that can run this test at all:
 * 1. the resolved ceiling is at least the product floor (`RETENTION_WINDOW_MIN_MINUTES`);
 * 2. every measured `snapshot()` call returns exactly `capacityBytes` (the full buffer) -- a sanity
 *    check that the worst-case fill actually happened, not a timing assertion.
 * No wall-clock number is asserted against a threshold: a device-dependent millisecond figure would
 * eventually flake on a noisy or thermally-throttled run and invite exactly the kind of
 * escape-hatch AGENTS.md §3 forbids. The measured lock-hold-time numbers are reported via `Log.i`
 * (tag [TAG]) for a human (or `@techlead`) to read out of the instrumentation log and interpret
 * against [AudioRecordHeadroomInstrumentedTest]'s real ARM `AudioRecord` headroom number.
 *
 * ## Measurement platform
 * This runs wherever `connectedDebugAndroidTest` targets: the CI instrumented tier's x86_64
 * emulator, or a real ARM device (e.g. the repo owner's Samsung S25, arm64-v8a) when run via
 * `scripts/device-smoke.sh` or a manually-targeted `adb`/Gradle invocation. Because the measured
 * buffer size is derived from *this* device's own [DeviceMemoryBudget] ceiling rather than
 * hardcoded, it scales down naturally on a smaller-heap emulator instead of risking an OOM -- see
 * the class doc's "reachable, never hardcoded" requirement. [Build.MODEL]/[Build.SUPPORTED_ABIS]
 * are logged with every run specifically so a reader can tell which platform a given number came
 * from without cross-referencing the CI job that produced it.
 */
@RunWith(AndroidJUnit4::class)
class RingBufferSnapshotLockBenchmarkInstrumentedTest {

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    @Test
    fun benchmarkSnapshotLockHoldTimeAtReachableHighFidelityCeiling() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        Runtime.getRuntime().gc()
        val maxHeapBytes = Runtime.getRuntime().maxMemory()
        val usedHeapBytes = usedHeapBytes()
        val availableSystemBytes = memInfo.availMem

        val abis = Build.SUPPORTED_ABIS.joinToString(",")
        Log.i(
            TAG,
            "=== device === manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} abis=$abis " +
                "maxHeapBytes=$maxHeapBytes (${maxHeapBytes / MB} MB) " +
                "usedHeapBytes=$usedHeapBytes (${usedHeapBytes / MB} MB) " +
                "availableSystemBytes=$availableSystemBytes (${availableSystemBytes / MB} MB)",
        )

        // Resolve the reachable worst case at runtime -- never hardcode a duration (issue #297).
        val probeConfig = QualityPreset.HIGH_FIDELITY.config(AudioConfig.RETENTION_WINDOW_MIN_MINUTES)
        val resolvedMinutes = DeviceMemoryBudget.maxRetentionMinutes(
            config = probeConfig,
            maxHeapBytes = maxHeapBytes,
            usedHeapBytes = usedHeapBytes,
            availableSystemBytes = availableSystemBytes,
        )

        assumeTrue(
            "SKIPPED: DeviceMemoryBudget granted only $resolvedMinutes min for HIGH_FIDELITY on " +
                "this device -- below the product floor (${AudioConfig.RETENTION_WINDOW_MIN_MINUTES} " +
                "min), so no meaningful worst-case measurement can be taken here. This would " +
                "indicate a regression in DeviceMemoryBudget's own floor coercion, not an expected " +
                "outcome.",
            resolvedMinutes >= AudioConfig.RETENTION_WINDOW_MIN_MINUTES,
        )

        val config = QualityPreset.HIGH_FIDELITY.config(resolvedMinutes)
        val capacityBytesLong = config.totalBufferBytes
        assumeTrue(
            "SKIPPED: resolved HIGH_FIDELITY capacity ($capacityBytesLong bytes) exceeds " +
                "Int.MAX_VALUE, which RingBuffer cannot address. DeviceMemoryBudget's own " +
                "addressableBytes clamp should prevent this -- if it did not, that is a separate " +
                "regression, not something to work around here.",
            capacityBytesLong in 1..Int.MAX_VALUE.toLong(),
        )
        val capacityBytes = capacityBytesLong.toInt()

        Log.i(
            TAG,
            "=== resolved config === preset=HIGH_FIDELITY resolvedMinutes=$resolvedMinutes " +
                "bytesPerSecond=${config.bytesPerSecond} capacityBytes=$capacityBytes " +
                "(${capacityBytes / MB} MB)",
        )

        val buffer: RingBuffer
        try {
            buffer = RingBuffer(capacityBytes = capacityBytes, bytesPerSecond = config.bytesPerSecond)
            // Fill to full capacity via bounded, repeated writes -- not one monolithic
            // ByteArray(capacityBytes) -- so setup itself does not require a contiguous
            // allocation the size of the whole buffer (see class doc: this is real, not
            // theoretical, on a device-sized heap). Outside any timed section either way.
            val fillChunk = ByteArray(FILL_CHUNK_BYTES)
            var written = 0L
            while (written < capacityBytes) {
                val remaining = (capacityBytes - written).coerceAtMost(FILL_CHUNK_BYTES.toLong()).toInt()
                buffer.write(fillChunk, offset = 0, length = remaining)
                written += remaining
            }
        } catch (oom: OutOfMemoryError) {
            assumeTrue(
                "SKIPPED: OutOfMemoryError filling $capacityBytes bytes on this device/emulator, " +
                    "even though DeviceMemoryBudget granted this ceiling from its own view of the " +
                    "heap. Not substituting a smaller buffer and reporting it as this config's " +
                    "number -- see issue #22/#297 for why that would be dishonest.",
                false,
            )
            return
        }

        // Request more than the buffer holds so snapshot() is forced to copy the full capacity
        // every call -- the same worst-case definition the JVM benchmark uses.
        val durationMillisRequest = resolvedMinutes.toLong() * MILLIS_PER_MINUTE + 1_000L

        // snapshot() itself allocates a fresh capacityBytes-sized ByteArray on top of the buffer's
        // own already-resident backing store (see RingBuffer.snapshot's doc) -- a real, and much
        // larger, peak than the filling loop above needed, and one DeviceMemoryBudget's
        // PEAK_TO_BACKING_RATIO does not model (that ratio is calibrated against the chunked
        // readSince export path, not a single full-buffer snapshot() copy). If *this* device's
        // granted ceiling cannot even complete one snapshot() call without OOM, that is exactly
        // the "granted ceiling is below what a meaningful measurement needs" case the class doc
        // commits to degrading gracefully on, so it is caught here too, not just around the fill.
        val timesNanos: LongArray
        try {
            repeat(WARMUP_ITERATIONS) { buffer.snapshot(durationMillisRequest) }

            timesNanos = LongArray(MEASURED_ITERATIONS)
            for (i in 0 until MEASURED_ITERATIONS) {
                val start = System.nanoTime()
                val snap = buffer.snapshot(durationMillisRequest)
                timesNanos[i] = System.nanoTime() - start
                assertEquals(
                    "sanity: snapshot() should return the full $capacityBytes-byte buffer " +
                        "(worst case), got ${snap.data.size}",
                    capacityBytes,
                    snap.data.size,
                )
            }
        } catch (oom: OutOfMemoryError) {
            Log.w(
                TAG,
                "OOM finding: HIGH_FIDELITY's DeviceMemoryBudget-granted ceiling " +
                    "($resolvedMinutes min, $capacityBytes backing bytes) on this device could not " +
                    "complete a single snapshot() call -- OutOfMemoryError allocating the " +
                    "snapshot's own $capacityBytes-byte result array on top of the already-resident " +
                    "backing store. This is a real finding for @techlead/issue #71's follow-up: " +
                    "the budget's PEAK_TO_BACKING_RATIO models the chunked export path's ~1.15x " +
                    "peak, not snapshot()'s own full-copy (~2x) peak.",
            )
            assumeTrue(
                "SKIPPED: OutOfMemoryError calling snapshot() at the granted $resolvedMinutes-min " +
                    "HIGH_FIDELITY ceiling on this device -- see the OOM finding logged just above " +
                    "with tag $TAG. Not substituting a smaller window and reporting it as this " +
                    "config's number.",
                false,
            )
            return
        }
        timesNanos.sort()

        val medianMs = timesNanos[MEASURED_ITERATIONS / 2] / NANOS_PER_MILLI
        val worstMs = timesNanos[MEASURED_ITERATIONS - 1] / NANOS_PER_MILLI
        val bestMs = timesNanos[0] / NANOS_PER_MILLI

        Log.i(
            TAG,
            "RESULT preset=HIGH_FIDELITY resolvedMinutes=$resolvedMinutes " +
                "capacityBytes=$capacityBytes best=${bestMs}ms median=${medianMs}ms " +
                "worst=${worstMs}ms over $MEASURED_ITERATIONS iterations (after " +
                "$WARMUP_ITERATIONS warmup), platform=ARM/instrumented-tier " +
                "model=${Build.MODEL} abis=$abis",
        )
    }

    private companion object {
        const val TAG = "SnapshotLockBenchmark"
        const val MB = 1024L * 1024L
        const val MILLIS_PER_MINUTE = 60_000L
        const val NANOS_PER_MILLI = 1_000_000.0
        const val WARMUP_ITERATIONS = 5
        const val MEASURED_ITERATIONS = 30

        /** Bounded fill chunk size -- see class doc's "fills in bounded chunks" deviation. */
        const val FILL_CHUNK_BYTES = 256 * 1024
    }
}
