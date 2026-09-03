package cc.machado.audioblackbox.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises [RecorderService.rebuildEngineIfIdle] directly against the real companion-object
 * singleton (issue #45) -- not a fake, because this is the exact seam that decides whether the
 * *actual* engine [RecorderService] starts capture on is built from a persisted, non-default
 * value, or stays silently pinned to `AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES`. A test that
 * only ever exercised 30 here could not catch a regression where a persisted value stops actually
 * reaching the engine's construction -- see issue #45's testing bar.
 *
 * Runs entirely without touching `android.media.AudioRecord`: `rebuildEngineIfIdle` only
 * *constructs* a new `AudioCaptureEngine` (cheap, config-only) -- it is never started here, so no
 * real `AudioRecord` is ever opened (contrast with `AudioCaptureEngineTest`, which needs the
 * `audioRecordFactory` seam specifically because it does call `start()`).
 */
class RecorderServiceRetentionWindowTest {

    // Issue #298: RecorderService's ceiling is now DeviceMemoryBudget-derived from this JVM's own
    // (huge, 4g -- see app/build.gradle.kts) test heap by default, which would make every value
    // this file exercises (including 65) fit easily and defeat the "rejects out-of-range" tests
    // below. Pinning a fixed, deterministic ceiling here is the injected seam this issue's design
    // requires, exactly like DeviceMemoryBudgetTest does for DeviceMemoryBudget itself -- restored
    // in tearDown so no other test in the suite inherits this override.
    @Before
    fun setUp() {
        RecorderService.maxRetentionMinutesProvider = { 45 }
    }

    @After
    fun tearDown() {
        RecorderService.rebuildEngineIfIdle(
            newBufferDurationMinutes = cc.machado.audioblackbox.audio.AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES,
            newPreset = cc.machado.audioblackbox.audio.QualityPreset.DEFAULT,
        )
        RecorderService.maxRetentionMinutesProvider = { preset ->
            cc.machado.audioblackbox.audio.DeviceMemoryBudget.maxRetentionMinutes(
                config = preset.config(cc.machado.audioblackbox.audio.AudioConfig.RETENTION_WINDOW_MIN_MINUTES),
                maxHeapBytes = Runtime.getRuntime().maxMemory(),
                usedHeapBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            )
        }
    }

    @Test
    fun `rebuildEngineIfIdle at a non-default capacity is reflected by every public mirror, not just one`() {
        val engineBefore = RecorderService.engine

        val applied = RecorderService.rebuildEngineIfIdle(45)

        assertTrue("engine was Idle, rebuild must succeed", applied)
        assertEquals(45, RecorderService.bufferDurationMinutes)
        assertEquals(45, RecorderService.captureConfig.bufferDurationMinutes)
        assertEquals(45, RecorderService.bufferDurationMinutesFlow.value)
        // A genuinely new engine instance, not the same one mutated in place -- the ring buffer
        // cannot be resized after construction (see AudioConfig's class doc), so "honouring" a new
        // capacity means a new AudioCaptureEngine, never the same reference.
        assertNotSame(engineBefore, RecorderService.engine)
    }

    @Test
    fun `rebuildEngineIfIdle to a second, different non-default capacity replaces the previous one, not just the default`() {
        RecorderService.rebuildEngineIfIdle(15)
        assertEquals(15, RecorderService.bufferDurationMinutes)

        RecorderService.rebuildEngineIfIdle(5)

        assertEquals(5, RecorderService.bufferDurationMinutes)
        assertEquals(5, RecorderService.captureConfig.bufferDurationMinutes)
    }

    @Test
    fun `rebuildEngineIfIdle rejects a capacity outside the bounded range and changes nothing`() {
        RecorderService.rebuildEngineIfIdle(30)
        val before = RecorderService.bufferDurationMinutes

        var thrown = false
        try {
            RecorderService.rebuildEngineIfIdle(65)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }

        assertTrue("65 is above this test's fixed 45-minute maxRetentionMinutesProvider ceiling", thrown)
        assertEquals(before, RecorderService.bufferDurationMinutes)
    }

    @Test
    fun `rebuildEngineIfIdle rejects an in-range but off-step capacity and changes nothing`() {
        // Issue #73: the stepper's domain is a range with a step, not the old fixed list -- 37 is
        // inside [MIN, MAX] but not a multiple of STEP, a distinct way to be invalid that could not
        // exist under the pre-#73 fixed-list domain.
        RecorderService.rebuildEngineIfIdle(30)
        val before = RecorderService.bufferDurationMinutes

        var thrown = false
        try {
            RecorderService.rebuildEngineIfIdle(37)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }

        assertTrue("37 is not a multiple of AudioConfig.RETENTION_WINDOW_STEP_MINUTES", thrown)
        assertEquals(before, RecorderService.bufferDurationMinutes)
    }

    @Test
    fun `rebuildEngineIfIdle with QualityPreset updates captureConfig and mirrors`() {
        val engineBefore = RecorderService.engine
        val applied = RecorderService.rebuildEngineIfIdle(
            newBufferDurationMinutes = 20,
            newPreset = cc.machado.audioblackbox.audio.QualityPreset.HIGH_FIDELITY,
        )

        assertTrue(applied)
        assertEquals(20, RecorderService.bufferDurationMinutes)
        assertEquals(cc.machado.audioblackbox.audio.QualityPreset.HIGH_FIDELITY, RecorderService.qualityPreset)
        assertEquals(cc.machado.audioblackbox.audio.QualityPreset.HIGH_FIDELITY, RecorderService.qualityPresetFlow.value)
        assertEquals(44100, RecorderService.captureConfig.sampleRateHz)
        assertEquals(2, RecorderService.captureConfig.channelCount)
        assertNotSame(engineBefore, RecorderService.engine)
    }

    // ---- Issue #298: dynamic, device-derived ceiling ----

    @Test
    fun `a device budget above the old fixed 45-minute ceiling now allows more than 45`() {
        RecorderService.maxRetentionMinutesProvider = { 90 }

        val applied = RecorderService.rebuildEngineIfIdle(90)

        assertTrue("a device whose real budget covers 90 min must not be capped at the old 45", applied)
        assertEquals(90, RecorderService.bufferDurationMinutes)
    }

    @Test
    fun `reconcileRetentionCeiling clamps an already-configured window down when the ceiling shrinks`() {
        RecorderService.maxRetentionMinutesProvider = { 60 }
        RecorderService.rebuildEngineIfIdle(60)
        assertEquals(60, RecorderService.bufferDurationMinutes)

        // Simulates a heavier release/less headroom appearing between one process start and the
        // next (issue #298's "recalculate at service start", not just when Settings is open).
        val shrunkToFit = RecorderService.reconcileRetentionCeiling(maxRetentionMinutesProvider = { 30 })

        assertTrue("the stored 60 min no longer fits a 30 min ceiling, so this must clamp", shrunkToFit)
        assertEquals(30, RecorderService.bufferDurationMinutes)
    }

    @Test
    fun `reconcileRetentionCeiling is a no-op when the current window already fits`() {
        RecorderService.maxRetentionMinutesProvider = { 60 }
        RecorderService.rebuildEngineIfIdle(30)

        val changed = RecorderService.reconcileRetentionCeiling(maxRetentionMinutesProvider = { 60 })

        assertTrue("30 already fits a 60 min ceiling, nothing to reconcile", !changed)
        assertEquals(30, RecorderService.bufferDurationMinutes)
    }
}
