package cc.machado.audioblackbox.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MB = 1024L * 1024L

/**
 * The inference that replaces a hand-picked retention constant.
 *
 * The measured anchor these tests are calibrated against is `RetentionCeilingMeasurementTest`, run
 * on the CI emulator (`heapgrowthlimit` 192 MB, production 16 kHz mono): 45 min occupied 82 MB of
 * backing at 94 MB peak, and 105 min failed needing 192.3 MB of backing alone.
 */
class DeviceMemoryBudgetTest {

    private val voice = QualityPreset.VOICE.config(30)

    private fun budget(
        config: AudioConfig = voice,
        maxHeapMb: Long = 256,
        usedHeapMb: Long = 56,
        availableSystemMb: Long? = null,
        hardCeiling: Int = 120,
    ) = DeviceMemoryBudget.maxRetentionMinutes(
        config = config,
        maxHeapBytes = maxHeapMb * MB,
        usedHeapBytes = usedHeapMb * MB,
        availableSystemBytes = availableSystemMb?.times(MB),
        hardCeilingMinutes = hardCeiling,
    )

    @Test
    fun `a device with more heap is offered a longer window`() {
        val small = budget(maxHeapMb = 192)
        val large = budget(maxHeapMb = 512)
        assertTrue("512 MB device ($large) must beat a 192 MB one ($small)", large > small)
    }

    /**
     * The whole point of subtracting live heap rather than estimating it: the app's own resident
     * footprint is the term the offline measurement harness could not hold.
     */
    @Test
    fun `an app already using more heap is offered a shorter window`() {
        val lean = budget(usedHeapMb = 40)
        val heavy = budget(usedHeapMb = 160)
        assertTrue("a heavier app ($heavy) must be offered less than a lean one ($lean)", heavy < lean)
    }

    @Test
    fun `a costlier preset gets a proportionally shorter window on the same device`() {
        val voiceMinutes = budget(config = QualityPreset.VOICE.config(30))
        val balancedMinutes = budget(config = QualityPreset.BALANCED.config(30))
        val hifiMinutes = budget(config = QualityPreset.HIGH_FIDELITY.config(30))

        assertTrue("balanced ($balancedMinutes) must be under voice ($voiceMinutes)", balancedMinutes < voiceMinutes)
        assertTrue("hi-fi ($hifiMinutes) must be under balanced ($balancedMinutes)", hifiMinutes < balancedMinutes)

        // BALANCED is exactly twice VOICE's byte rate, so its window is half -- within one step.
        assertEquals(
            "halving the window is the whole trade a doubled sample rate makes",
            (voiceMinutes / 2).toDouble(),
            balancedMinutes.toDouble(),
            AudioConfig.RETENTION_WINDOW_STEP_MINUTES.toDouble(),
        )
    }

    @Test
    fun `the result is always a whole number of stepper steps`() {
        for (heap in listOf(64L, 128L, 192L, 256L, 384L, 512L, 1024L)) {
            val minutes = budget(maxHeapMb = heap)
            assertEquals(
                "$minutes min (at ${heap}MB heap) is not on the ${AudioConfig.RETENTION_WINDOW_STEP_MINUTES}-minute step",
                0,
                minutes % AudioConfig.RETENTION_WINDOW_STEP_MINUTES,
            )
        }
    }

    @Test
    fun `the product ceiling wins over a device that could hold more`() {
        assertEquals(60, budget(maxHeapMb = 8192, usedHeapMb = 10, hardCeiling = 60))
    }

    /** Offering "0 minutes" would be a broken product, not a safe one. */
    @Test
    fun `a device with no headroom still reports the minimum rather than zero`() {
        assertEquals(
            AudioConfig.RETENTION_WINDOW_MIN_MINUTES,
            budget(maxHeapMb = 64, usedHeapMb = 64),
        )
    }

    @Test
    fun `used heap above the ceiling does not produce a negative window`() {
        assertEquals(
            AudioConfig.RETENTION_WINDOW_MIN_MINUTES,
            budget(maxHeapMb = 128, usedHeapMb = 200),
        )
    }

    /**
     * A generous heap limit on a device that is out of memory buys nothing, so system availability
     * is an independent ceiling rather than something averaged in.
     */
    @Test
    fun `a memory-starved system caps the window regardless of heap ceiling`() {
        val unconstrained = budget(maxHeapMb = 512, availableSystemMb = null)
        val starved = budget(maxHeapMb = 512, availableSystemMb = 100)
        assertTrue("a starved system ($starved) must cap below the heap-only figure ($unconstrained)", starved < unconstrained)
    }

    @Test
    fun `an abundant system does not inflate the window beyond what the heap allows`() {
        assertEquals(
            budget(maxHeapMb = 256, availableSystemMb = null),
            budget(maxHeapMb = 256, availableSystemMb = 16_384),
        )
    }

    /**
     * Calibration against the real emulator run. At 192 MB heap with a modest resident app, the
     * inference must land comfortably under the 105-minute window that actually threw OOM there --
     * a model that would have offered the user the configuration we watched fail is not safe.
     */
    @Test
    fun `on the measured emulator profile the inference stays well under the observed OOM point`() {
        val minutes = budget(maxHeapMb = 192, usedHeapMb = 56)
        assertTrue("inferred $minutes min must stay under the measured 105-minute OOM", minutes < 105)
        assertTrue("inferred $minutes min should still be usable", minutes >= AudioConfig.RETENTION_WINDOW_MIN_MINUTES)
    }

    @Test
    fun `never exceeds what a ring buffer can address`() {
        val minutes = budget(config = QualityPreset.VOICE.config(30), maxHeapMb = 64_000, usedHeapMb = 10, hardCeiling = Int.MAX_VALUE)
        val bytes = QualityPreset.VOICE.config(minutes).totalBufferBytes
        assertTrue("$bytes bytes exceeds the Int addressing limit of RingBuffer", bytes <= Int.MAX_VALUE.toLong())
    }
}

/**
 * Pins the model against the only two facts that are not opinion: what the CI emulator was
 * observed to do. Separate from [DeviceMemoryBudgetTest] because these are calibration, not
 * behaviour -- if the memory model changes, these are the tests that must be re-derived from a
 * fresh `RetentionCeilingMeasurementTest` run rather than adjusted until green.
 */
class DeviceMemoryBudgetCalibrationTest {

    private fun emulatorProfile(preset: QualityPreset, usedMb: Long = 56) =
        DeviceMemoryBudget.maxRetentionMinutes(
            config = preset.config(30),
            maxHeapBytes = 192 * MB,
            usedHeapBytes = usedMb * MB,
            hardCeilingMinutes = 120,
        )

    /**
     * The regression that motivated replacing the first model. 45 minutes was directly observed
     * working on this profile (82 MB backing, 94 MB peak, against a 192 MB ceiling), and it is
     * what the app ships today -- an inference that offers existing users *less* than the value
     * already proven good on their device is a regression dressed as caution.
     */
    @Test
    fun `the default preset is never offered less than the window measured working`() {
        val minutes = emulatorProfile(QualityPreset.VOICE)
        assertTrue(
            "inferred $minutes min is below the 45 min observed working on this exact profile",
            minutes >= 45,
        )
    }

    /** 105 minutes threw OOM on this profile. Offering it would be offering a known crash. */
    @Test
    fun `the default preset is never offered the window measured failing`() {
        val minutes = emulatorProfile(QualityPreset.VOICE)
        assertTrue("inferred $minutes min reaches the 105 min that threw OOM here", minutes < 105)
    }

    /** 90 minutes technically fit, but at 188 MB of a 192 MB ceiling -- with the harness holding
     * no Compose UI. Offering it to a real app would be spending margin that does not exist. */
    @Test
    fun `the default preset stays clear of the window that fit with no margin`() {
        val minutes = emulatorProfile(QualityPreset.VOICE)
        assertTrue("inferred $minutes min reaches the no-margin 90 min case", minutes < 90)
    }

    @Test
    fun `a heavier resident app shrinks the offer on the same hardware`() {
        assertTrue(emulatorProfile(QualityPreset.VOICE, usedMb = 120) < emulatorProfile(QualityPreset.VOICE, usedMb = 40))
    }
}
