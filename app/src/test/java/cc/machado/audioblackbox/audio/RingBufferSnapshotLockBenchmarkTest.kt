package cc.machado.audioblackbox.audio

import org.junit.Test

/**
 * Benchmark (not a threshold-asserting regression test -- see class doc below) for issue #22:
 * how long does [RingBuffer.snapshot] hold the buffer's lock at the largest configs the app can
 * actually reach today, plus one hypothetical future config?
 *
 * ## What this measures, and why it equals lock hold time
 * [RingBuffer.snapshot]'s entire body -- including the destination `ByteArray(length)`
 * allocation, not just the `System.arraycopy` calls -- runs inside its `synchronized(lock)`
 * block. So the wall-clock duration of the whole `snapshot()` call *is* the lock hold time in
 * the current implementation; there is no separate "caller allocates, then calls snapshot"
 * step to isolate. This benchmark times the whole call for that reason, not as an approximation.
 *
 * ## Configs measured
 * - 16 kHz / mono / 60 min: the real default sample format at the real maximum retention window
 *   the UI offers today (see [AudioConfig.RETENTION_WINDOW_OPTIONS_MINUTES]; the issue's original
 *   "79 min" figure predates the configurable-retention feature and is stale).
 * - 44.1 kHz / stereo / 60 min: NOT reachable through the UI today -- nothing in this app's UI
 *   or settings ever constructs an [AudioConfig] with a non-default `sampleRateHz`/`channelCount`
 *   (grep confirms every call site only varies `bufferDurationMinutes`). Measured anyway per
 *   issue #22 because it is a plausible *future* setting and issue #47 needs the number, but
 *   labelled explicitly as hypothetical so nobody reads this as "the app can do this today".
 *
 * ## Why this is a benchmark, not an assertion
 * A `Test` that asserted e.g. "worst case < 50 ms" would eventually flake on a noisy CI runner
 * (GC pause, neighbor VM contention) and get silenced/deleted rather than fixed -- exactly what
 * issue #22 warns against. This test only asserts structural invariants (a config allocates, a
 * snapshot returns the expected size) and otherwise *reports* numbers via stdout for a human (or
 * `@techlead`) to read out of the CI log and interpret against `AudioRecord`'s real headroom,
 * which is measured separately in the instrumented tier
 * (`AudioRecordHeadroomInstrumentedTest`) because `AudioRecord.getMinBufferSize` needs the
 * Android framework.
 *
 * ## Measurement platform caveat
 * This JVM unit-test tier runs on a GitHub Actions `ubuntu-latest` runner (x86_64), not the
 * repo owner's Samsung S25 (ARM). Memory bandwidth and scheduler behavior differ between the
 * two; see this test's logged output and issue #22's comment thread for how that affects the
 * verdict's confidence.
 */
class RingBufferSnapshotLockBenchmarkTest {

    private data class BenchConfig(
        val label: String,
        val sampleRateHz: Int,
        val channelCount: Int,
        val bufferDurationMinutes: Int,
        val reachableViaUiToday: Boolean,
    ) {
        val bytesPerFrame = BYTES_PER_SAMPLE_PCM16 * channelCount
        val bytesPerSecond = sampleRateHz * bytesPerFrame
        val capacityBytes: Long = bytesPerSecond.toLong() * bufferDurationMinutes * SECONDS_PER_MINUTE
    }

    @Test
    fun `benchmark snapshot lock hold time at real max and hypothetical configs`() {
        val configs = listOf(
            BenchConfig(
                label = "16kHz/mono/60min (REAL: default sample format, max retention offered by the UI)",
                sampleRateHz = 16_000,
                channelCount = 1,
                bufferDurationMinutes = 60,
                reachableViaUiToday = true,
            ),
            BenchConfig(
                label = "44.1kHz/stereo/60min (HYPOTHETICAL: no UI path sets this today)",
                sampleRateHz = 44_100,
                channelCount = 2,
                bufferDurationMinutes = 60,
                reachableViaUiToday = false,
            ),
        )

        var atLeastOneConfigMeasured = false
        for (cfg in configs) {
            if (benchmarkOne(cfg)) atLeastOneConfigMeasured = true
        }

        check(atLeastOneConfigMeasured) {
            "Every config hit OutOfMemoryError on this runner -- no lock-hold-time number could " +
                "be produced at all. See stdout above for details; do not treat this as a pass."
        }
    }

    /** Returns true if the config was actually measured (false if it had to be skipped for OOM). */
    private fun benchmarkOne(cfg: BenchConfig): Boolean {
        println("[SnapshotBenchmark] --- ${cfg.label} ---")
        println(
            "[SnapshotBenchmark] capacityBytes=${cfg.capacityBytes} " +
                "(${cfg.capacityBytes / BYTES_PER_MB} MB), bytesPerSecond=${cfg.bytesPerSecond}, " +
                "reachableViaUiToday=${cfg.reachableViaUiToday}",
        )

        if (cfg.capacityBytes > Int.MAX_VALUE) {
            println(
                "[SnapshotBenchmark] SKIPPED ${cfg.label}: capacityBytes exceeds Int.MAX_VALUE, " +
                    "which RingBuffer/AudioCaptureEngine already coerce down in production code " +
                    "(see AudioCaptureEngine.start's coerceIn) -- not a benchmark concern.",
            )
            return false
        }
        val capacity = cfg.capacityBytes.toInt()

        val buffer: RingBuffer
        try {
            buffer = RingBuffer(capacityBytes = capacity, bytesPerSecond = cfg.bytesPerSecond)
            // One-shot fill to bring the buffer to full capacity so snapshot() always has to copy
            // the worst case (the whole backing array). This single arraycopy happens once, here,
            // outside any timing -- it is setup, not part of the measured path.
            val fillSource = ByteArray(capacity)
            buffer.write(fillSource)
        } catch (oom: OutOfMemoryError) {
            println(
                "[SnapshotBenchmark] SKIPPED ${cfg.label}: OutOfMemoryError allocating $capacity " +
                    "bytes on this JVM/runner. Not substituting a smaller buffer and reporting it " +
                    "as if it were this config's number -- see issue #22 for why that would be " +
                    "dishonest. Increase the test JVM's heap (app/build.gradle.kts, " +
                    "tasks.withType<Test> { maxHeapSize }) or run on a runner with more memory.",
            )
            return false
        }

        // Request more than the buffer holds so snapshot() is forced to copy the full capacity
        // every time -- the worst case this benchmark exists to measure.
        val durationMillisRequest = cfg.bufferDurationMinutes.toLong() * MILLIS_PER_MINUTE + 1_000L

        repeat(WARMUP_ITERATIONS) { buffer.snapshot(durationMillisRequest) }

        val timesNanos = LongArray(MEASURED_ITERATIONS)
        for (i in 0 until MEASURED_ITERATIONS) {
            val start = System.nanoTime()
            val snap = buffer.snapshot(durationMillisRequest)
            timesNanos[i] = System.nanoTime() - start
            check(snap.data.size == capacity) {
                "sanity: snapshot() should return the full $capacity-byte buffer, got ${snap.data.size}"
            }
        }
        timesNanos.sort()

        val medianMs = timesNanos[MEASURED_ITERATIONS / 2] / NANOS_PER_MILLI
        val worstMs = timesNanos[MEASURED_ITERATIONS - 1] / NANOS_PER_MILLI
        val bestMs = timesNanos[0] / NANOS_PER_MILLI
        println(
            "[SnapshotBenchmark] RESULT ${cfg.label}: best=${bestMs}ms median=${medianMs}ms " +
                "worst=${worstMs}ms over $MEASURED_ITERATIONS iterations (after $WARMUP_ITERATIONS " +
                "warmup), platform=JVM-unit-test-tier (see class doc for CPU/tier caveat)",
        )
        return true
    }

    private companion object {
        const val BYTES_PER_SAMPLE_PCM16 = 2
        const val SECONDS_PER_MINUTE = 60L
        const val MILLIS_PER_MINUTE = 60_000L
        const val BYTES_PER_MB = 1_000_000L
        const val NANOS_PER_MILLI = 1_000_000.0
        const val WARMUP_ITERATIONS = 5
        const val MEASURED_ITERATIONS = 30
    }
}
