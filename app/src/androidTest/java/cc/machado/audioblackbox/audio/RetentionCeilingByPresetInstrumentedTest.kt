package cc.machado.audioblackbox.audio

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures the real, per-[QualityPreset] retention ceiling [DeviceMemoryBudget.maxRetentionMinutes]
 * resolves to on *this* device, right now (issue #71 follow-up to #298/#300).
 *
 * ## Why this is not the same test as [RetentionCeilingMeasurementTest]
 * That harness allocates and exports a real `RingBuffer` to find the largest window this device can
 * physically hold, deliberately excluding the app's own live footprint (Compose, the encoder, the
 * foreground notification) so the number is a conservative floor. This test asks a different
 * question: given the *actual* resident state of this process right now (whatever is running when
 * the test executes -- normally just the instrumentation + test APK, no Compose UI), what does the
 * production formula in [DeviceMemoryBudget] actually resolve to for each shipped preset? It exists
 * to produce a real, dated, on-device number to replace the stale 45/45/15-minute figure recorded on
 * issue #71 -- a number that predates #300 and refers to a constant (`RETENTION_WINDOW_MAX_MINUTES`)
 * that no longer exists.
 *
 * ## Deliberately assertion-light
 * This is a measurement, reported via `Log.i` for a human to read out of the instrumentation log
 * (`adb logcat`), not a correctness gate -- [DeviceMemoryBudget]'s own math is exercised by its
 * (device-independent) JVM unit tests. The two assertions here guard only structural invariants that
 * would have to hold on *any* device for the model to be sane, and would fail loudly if the formula
 * regressed:
 * 1. every preset's ceiling is at least the product floor (`RETENTION_WINDOW_MIN_MINUTES`);
 * 2. the ceiling is monotonically non-increasing as byte rate increases (VOICE >= BALANCED >=
 *    HIGH_FIDELITY) -- a real property of the shared-budget model, not a restatement of the formula
 *    under test, and something a broken clamp (e.g. an inverted comparison) would violate.
 */
@RunWith(AndroidJUnit4::class)
class RetentionCeilingByPresetInstrumentedTest {

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    @Test
    fun logsMeasuredRetentionCeilingPerPreset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        Runtime.getRuntime().gc()
        val maxHeapBytes = Runtime.getRuntime().maxMemory()
        val usedHeapBytes = usedHeapBytes()
        val availableSystemBytes = memInfo.availMem

        val heapGrowthLimit = System.getProperty("dalvik.vm.heapgrowthlimit") ?: "unknown"
        val heapSize = System.getProperty("dalvik.vm.heapsize") ?: "unknown"
        val largeHeapFlag = context.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP
        val isLargeHeap = largeHeapFlag != 0

        Log.i(
            TAG,
            "=== raw inputs === applicationId=${context.packageName} largeHeap=$isLargeHeap " +
                "dalvik.vm.heapgrowthlimit=$heapGrowthLimit dalvik.vm.heapsize=$heapSize " +
                "maxHeapBytes=$maxHeapBytes (${maxHeapBytes / MB} MB) " +
                "usedHeapBytes=$usedHeapBytes (${usedHeapBytes / MB} MB) " +
                "availableSystemBytes=$availableSystemBytes (${availableSystemBytes / MB} MB) " +
                "systemTotalMem=${memInfo.totalMem / MB} MB systemLowMemory=${memInfo.lowMemory}",
        )

        var previousMinutes: Int? = null
        for (preset in QualityPreset.entries) {
            val config = preset.config(AudioConfig.RETENTION_WINDOW_MIN_MINUTES)
            val bytesPerMinute = config.bytesPerSecond.toLong() * 60L

            val resolvedMinutes = DeviceMemoryBudget.maxRetentionMinutes(
                config = config,
                maxHeapBytes = maxHeapBytes,
                usedHeapBytes = usedHeapBytes,
                availableSystemBytes = availableSystemBytes,
            )

            // Re-derive the same intermediate terms the production function computes, purely to
            // report *which* clamp bound the result -- this mirrors DeviceMemoryBudget's own
            // arithmetic rather than asserting against it, so it is not load-bearing for
            // correctness, only for the human-readable breakdown below.
            val safeHeapBytes = (maxHeapBytes * DeviceMemoryBudget.SAFE_HEAP_UTILISATION).toLong()
            val heapBudgetBytes = (safeHeapBytes - usedHeapBytes).coerceAtLeast(0L)
            val systemBudgetBytes =
                (availableSystemBytes * DeviceMemoryBudget.SAFE_HEAP_UTILISATION).toLong()
            val budgetBytes = minOf(heapBudgetBytes, systemBudgetBytes)
            val backingBytes = (budgetBytes / DeviceMemoryBudget.PEAK_TO_BACKING_RATIO).toLong()
            val addressableBytes = minOf(backingBytes, Int.MAX_VALUE.toLong())

            val boundedBy = when {
                resolvedMinutes == AudioConfig.RETENTION_WINDOW_MIN_MINUTES &&
                    (addressableBytes / bytesPerMinute).toInt() < AudioConfig.RETENTION_WINDOW_MIN_MINUTES ->
                    "floor (RETENTION_WINDOW_MIN_MINUTES)"
                backingBytes >= Int.MAX_VALUE.toLong() -> "addressable wall (Int.MAX_VALUE)"
                systemBudgetBytes < heapBudgetBytes -> "available system memory"
                else -> "heap headroom"
            }

            Log.i(
                TAG,
                "preset=$preset bytesPerSecond=${config.bytesPerSecond} " +
                    "bytesPerMinute=$bytesPerMinute (${bytesPerMinute / MB} MB/min) " +
                    "heapBudgetBytes=${heapBudgetBytes / MB} MB " +
                    "systemBudgetBytes=${systemBudgetBytes / MB} MB " +
                    "backingBytes=${backingBytes / MB} MB " +
                    "resolvedMinutes=$resolvedMinutes boundedBy=$boundedBy",
            )

            assertTrue(
                "$preset ceiling ($resolvedMinutes min) must be at least the product floor " +
                    "(${AudioConfig.RETENTION_WINDOW_MIN_MINUTES} min)",
                resolvedMinutes >= AudioConfig.RETENTION_WINDOW_MIN_MINUTES,
            )
            previousMinutes?.let { prior ->
                assertTrue(
                    "presets are declared in decreasing-byte-rate order, so ceilings must be " +
                        "non-increasing: $preset ($resolvedMinutes min) exceeded the prior " +
                        "preset's ceiling ($prior min)",
                    resolvedMinutes <= prior,
                )
            }
            previousMinutes = resolvedMinutes
        }
    }

    private companion object {
        const val TAG = "RetentionCeilingByPreset"
        const val MB = 1024L * 1024L
    }
}
