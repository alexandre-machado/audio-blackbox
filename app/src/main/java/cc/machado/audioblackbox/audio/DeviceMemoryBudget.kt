package cc.machado.audioblackbox.audio

/**
 * Derives how much audio this device can actually hold, from this device, at runtime.
 *
 * ## Why this is not a constant
 * `AudioConfig.RETENTION_WINDOW_MAX_MINUTES` was a hand-picked number calibrated against a memory
 * cost that stopped existing when #72 was fixed (see that constant's own comment, and the measured
 * table `RetentionCeilingMeasurementTest` produced). A constant cannot distinguish a 192 MB
 * emulator from a 256 MB phone from a tablet with `largeHeap`, so it has to be set for the
 * smallest device anyone might run, and every better device silently loses the difference.
 *
 * Everything needed to compute it honestly is already available at runtime:
 * `Runtime.getRuntime().maxMemory()` is this process's real heap ceiling -- it already accounts for
 * `dalvik.vm.heapgrowthlimit`, `android:largeHeap`, and whatever the OEM configured -- and the
 * currently used heap already includes Compose, the encoder and the foreground notification being
 * resident, which is precisely the term the measurement harness could not hold and which is why
 * that measurement had to stay conservative.
 *
 * ## The model
 * ```
 * safeHeap   = maxHeap * SAFE_HEAP_UTILISATION
 * backing    = (safeHeap - usedHeap) / PEAK_TO_BACKING_RATIO
 * minutes    = backing / bytesPerMinute   (floored to the stepper's step)
 * ```
 *
 * The margin is taken *once*, as a cap on total heap utilisation, and the app's live footprint is
 * then subtracted from it. An earlier draft took a fraction of the already-headroom-reduced figure
 * instead, which double-counted the safety margin: on the CI emulator it produced 30 minutes for
 * the default preset, against 45 minutes that the measurement had directly observed working. A
 * model that refuses a configuration already known to be good is not being careful, it is wrong.
 *
 * [PEAK_TO_BACKING_RATIO] is measured, not guessed: across 30/45/60/75/90-minute windows on the CI
 * emulator, peak used heap during a full export ran about 1.15x the ring buffer's own backing
 * array, because the export drains in fixed 4 KB chunks rather than copying the window (#72/#114).
 *
 * [SAFE_HEAP_UTILISATION] is the one number here that is judgement rather than measurement -- see
 * its own doc for what it is anchored to.
 *
 * ## What this deliberately does not model
 * System-wide memory pressure beyond `availableSystemBytes`, and nothing at all about an OEM
 * low-memory killer deciding to reclaim a long-running foreground service. No API predicts that;
 * it is why [SAFE_HEAP_UTILISATION] stays below 1.
 */
object DeviceMemoryBudget {

    /**
     * The largest retention window, in whole stepper steps, that [config]'s audio format can
     * safely occupy on this device right now.
     *
     * Always within `[AudioConfig.RETENTION_WINDOW_MIN_MINUTES, hardCeilingMinutes]`: a device that
     * could technically hold more is still capped by what the product chooses to offer, and a
     * device that can barely hold anything still reports the minimum rather than zero -- offering
     * "0 minutes" would be a broken product, so the minimum is treated as the cost of running at
     * all rather than as something to negotiate.
     *
     * @param config the audio format whose byte rate the window is measured in. Passing a
     *   different quality preset's config is exactly how each preset gets its own ceiling.
     * @param maxHeapBytes `Runtime.getRuntime().maxMemory()`.
     * @param usedHeapBytes `totalMemory() - freeMemory()` at the moment of asking, so the app's
     *   real resident footprint is subtracted rather than estimated.
     * @param availableSystemBytes `ActivityManager.MemoryInfo.availMem`, or `null` when unknown.
     *   Applied as a second, independent limit: a heap ceiling means nothing if the system as a
     *   whole has no memory left to back it.
     * @param hardCeilingMinutes the product's own maximum, independent of hardware.
     */
    fun maxRetentionMinutes(
        config: AudioConfig,
        maxHeapBytes: Long,
        usedHeapBytes: Long,
        availableSystemBytes: Long? = null,
        hardCeilingMinutes: Int = AudioConfig.RETENTION_WINDOW_MAX_MINUTES,
    ): Int {
        val step = AudioConfig.RETENTION_WINDOW_STEP_MINUTES
        val floor = AudioConfig.RETENTION_WINDOW_MIN_MINUTES

        val safeHeapBytes = (maxHeapBytes * SAFE_HEAP_UTILISATION).toLong()
        var budgetBytes = (safeHeapBytes - usedHeapBytes).coerceAtLeast(0L)

        // The system's own availability is an independent ceiling, not an average: a generous heap
        // limit on a device that is currently out of memory buys nothing.
        if (availableSystemBytes != null) {
            budgetBytes = minOf(budgetBytes, (availableSystemBytes * SAFE_HEAP_UTILISATION).toLong())
        }

        val backingBytes = (budgetBytes / PEAK_TO_BACKING_RATIO).toLong()

        // A ring buffer is addressed with an Int, so this is a structural wall no amount of heap
        // gets past -- see RingBuffer's capacityBytes and AudioConfig.totalBufferBytes.
        val addressableBytes = minOf(backingBytes, Int.MAX_VALUE.toLong())

        val bytesPerMinute = config.bytesPerSecond.toLong() * SECONDS_PER_MINUTE
        if (bytesPerMinute <= 0L) return floor

        val rawMinutes = (addressableBytes / bytesPerMinute).toInt()
        val stepped = (rawMinutes / step) * step
        return stepped.coerceIn(floor, hardCeilingMinutes)
    }

    /**
     * How much of the process heap ceiling total usage is allowed to reach, buffer included.
     *
     * The one number here that is judgement rather than measurement, but it is judgement anchored
     * to observation: on the CI emulator a 45-minute window peaked at 94 MB of a 192 MB ceiling
     * (49%) with no trouble, while 90 minutes peaked at 188 MB (98%) and 105 minutes threw. 85%
     * sits above the comfortable case and well below the one that failed.
     *
     * The asymmetry that keeps it below 1: overestimating costs an `OutOfMemoryError` on the save
     * path -- the one moment where failure destroys what this product exists to preserve -- while
     * underestimating only offers a shorter window than the hardware could technically survive.
     */
    const val SAFE_HEAP_UTILISATION = 0.85f

    /**
     * Peak heap during a full export, relative to the ring buffer's backing array.
     *
     * Measured by `RetentionCeilingMeasurementTest` across 30/45/60/75/90-minute windows: peak ran
     * ~1.15x backing, because the export drains in fixed 4 KB chunks and never materialises the
     * window (#72, fixed in #114). Before that fix this figure was ~2.0, and re-measuring is the
     * right response to changing the export path -- not editing this number to taste.
     */
    const val PEAK_TO_BACKING_RATIO = 1.15f

    private const val SECONDS_PER_MINUTE = 60L
}
