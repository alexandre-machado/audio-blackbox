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
    fun `rebuildEngineIfIdle no-ops on a capacity above the ceiling instead of throwing (issue 317)`() {
        // Pre-#317 this threw IllegalArgumentException: a value above the live-sampled ceiling is
        // a runtime condition (the ceiling can legitimately shrink between an offer and this call
        // running -- see RecorderService.switchSettings's issue #317 doc), not a caller bug, so it
        // must never reach a `require()`. rebuildEngineIfIdle has no refusal channel of its own
        // (unlike switchSettings), so this is a plain no-op, exactly like the existing not-Idle case.
        RecorderService.rebuildEngineIfIdle(30)
        val before = RecorderService.bufferDurationMinutes

        val applied = RecorderService.rebuildEngineIfIdle(65)

        assertTrue("65 is above this test's fixed 45-minute ceiling, must no-op rather than throw", !applied)
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

    // ---- Issue #317: switchSettings must never crash on an over-ceiling value ----

    @Test
    fun `switchSettings never throws for an over-ceiling value -- it refuses through resizeRefusalFlow (issue 317)`() {
        // Reproduces the production crash directly: a value that was on-step and within bounds
        // when offered, sampled against a live ceiling that has since shrunk. Before issue #317
        // this `require`d and crashed with "newBufferDurationMinutes must be in 5..95 ... was 100" --
        // exactly the second/third of the four captured production crashes.
        RecorderService.maxRetentionMinutesProvider = { 95 }
        val before = RecorderService.bufferDurationMinutes

        val applied = RecorderService.switchSettings(newBufferDurationMinutes = 100, newPreset = cc.machado.audioblackbox.audio.QualityPreset.VOICE)

        assertTrue("an over-ceiling commit must be refused, not applied", !applied)
        val refusal = RecorderService.resizeRefusalFlow.value
        assertTrue("the existing refusal channel must carry the refusal, not a swallowed failure", refusal != null)
        assertEquals("a refused commit must leave the committed value untouched", before, RecorderService.bufferDurationMinutes)
        RecorderService.acknowledgeResizeRefusal()
    }

    @Test
    fun `switchSettings leaves the previously-committed setting completely unchanged on an over-ceiling refusal`() {
        RecorderService.maxRetentionMinutesProvider = { 90 }
        RecorderService.rebuildEngineIfIdle(newBufferDurationMinutes = 60, newPreset = cc.machado.audioblackbox.audio.QualityPreset.VOICE)
        val engineBefore = RecorderService.engine

        RecorderService.maxRetentionMinutesProvider = { 55 } // ceiling shrinks below the still-committed 60 by commit time
        val applied = RecorderService.switchSettings(newBufferDurationMinutes = 90, newPreset = cc.machado.audioblackbox.audio.QualityPreset.VOICE)

        assertTrue(!applied)
        assertEquals("the previous, still-running setting must stay in force", 60, RecorderService.bufferDurationMinutes)
        assertEquals(60, RecorderService.captureConfig.bufferDurationMinutes)
        assertEquals(60, RecorderService.bufferDurationMinutesFlow.value)
        assertTrue("capture must keep running unaffected at its previous configuration", engineBefore === RecorderService.engine)
        RecorderService.acknowledgeResizeRefusal()
    }

    // The real production shape (issue #317's root cause): the stepper's own offer and
    // switchSettings' own live check independently sample the same non-stationary
    // maxRetentionMinutesProvider seconds apart. A stateful provider whose return value genuinely
    // changes between calls -- not one hard-coded constant -- is what actually reproduces the bug;
    // see issue #317's regression-test requirement.
    @Test
    fun `a ceiling sampled lower on the commit call than it was on the offer never crashes switchSettings (issue 317)`() {
        var sampleCount = 0
        RecorderService.maxRetentionMinutesProvider = {
            sampleCount++
            if (sampleCount == 1) 100 else 5
        }
        // First sample models the stepper computing its offered maximum (100).
        val offeredMax = RecorderService.maxRetentionMinutesProvider(cc.machado.audioblackbox.audio.QualityPreset.HIGH_FIDELITY)
        assertEquals(100, offeredMax)

        // Second sample -- inside switchSettings itself -- has shrunk to 5, exactly the shape of
        // the fourth captured production crash ("must be in 5..5, was 10"): the ceiling collapses
        // to the floor under memory pressure.
        val applied = RecorderService.switchSettings(
            newBufferDurationMinutes = offeredMax,
            newPreset = cc.machado.audioblackbox.audio.QualityPreset.HIGH_FIDELITY,
        )

        assertTrue("must refuse, not crash, when the live ceiling has collapsed below the offered value", !applied)
        assertTrue(RecorderService.resizeRefusalFlow.value != null)
        RecorderService.acknowledgeResizeRefusal()
    }
}
