package cc.machado.audioblackbox.service

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.DeviceMemoryBudget
import cc.machado.audioblackbox.audio.QualityPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the precondition that made issue #322 *conditional* rather than systematic.
 *
 * `SettingsViewModel.commitPending` applies a settings change in two steps: first
 * [RecorderService.switchSettings] (which moves the engine's `activeConfig` and the service's
 * committed `captureConfig`), then [RecorderService.rebuildEngineIfIdle] (which replaces the engine
 * instance so it is *constructed* with the new config). The second step is what makes the engine's
 * constructor config agree with its `activeConfig` again -- and it declines, silently and by
 * design, in two situations:
 *
 * - capture state is not `Idle` (`CaptureState.Error` after the mic was taken by another app, or
 *   after a foreground-promotion refusal, both reachable on the real device); or
 * - the ceiling it re-samples has shrunk below the value [switchSettings] already approved a moment
 *   earlier. [RecorderService.switchSettings]'s own issue #317 doc calls this out: the ceiling is a
 *   live, non-stationary memory sample, and `rebuildEngineIfIdle` takes a *third* independent
 *   reading of it. The test below drives exactly that.
 *
 * Either way the engine keeps running as an instance constructed with the *previous* format while
 * the service reports the new one, and nothing repairs that until some later commit both succeeds
 * and finds the engine Idle -- or the process restarts, which is why a fresh build cannot reproduce
 * the defect on demand and why the same session that produced a refused switch (issue #321) is the
 * one that produced the corrupted file.
 *
 * ## What this test does and does not claim
 * It asserts the *precondition* is reachable, not that audio is corrupted -- the audio consequence
 * lived in `AudioCaptureEngine.start`, which used to open `AudioRecord` from the stale constructor
 * config while labelling the ring buffer from `activeConfig`. That is fixed and covered by
 * `CaptureFormatLabelTest`, which is where the format-versus-content oracle lives. This test exists
 * so that if the two-step commit is ever reworked, the reason this window matters is on record.
 *
 * The engine here is never started, so no real `AudioRecord` is opened -- same as
 * [RecorderServiceRetentionWindowTest], and the reason this can be a JVM test at all.
 */
class RecorderServiceEngineRebuildDeclineTest {

    @Before
    fun setUp() {
        RecorderService.maxRetentionMinutesProvider = { GENEROUS_CEILING_MINUTES }
        RecorderService.rebuildEngineIfIdle(
            newBufferDurationMinutes = WINDOW_MINUTES,
            newPreset = QualityPreset.DEFAULT,
        )
    }

    @After
    fun tearDown() {
        // Unconditionally, and *before* the restoring rebuild below (`@rev` finding 2, PR #323
        // round 2). `stop()` is what moves `CaptureState.Error` back to `Idle`, and
        // `rebuildEngineIfIdle` returns false at its `!is Idle` check without touching anything --
        // so if this ran at the end of a test body instead, any earlier assertion failure would
        // leave the process-global companion on HIGH_FIDELITY with an errored engine for every
        // class that runs afterwards. There is no `forkEvery` in `app/build.gradle.kts`, so that is
        // the whole suite in one JVM: `@rev` injected a late failure here and got 13 failures, 12
        // of them collateral in `RecorderServiceRetentionWindowTest` and `SettingsViewModelTest`.
        // A real regression must name its own cause, not blow up three unrelated classes.
        RecorderService.engine.stop()
        RecorderService.maxRetentionMinutesProvider = { GENEROUS_CEILING_MINUTES }
        RecorderService.rebuildEngineIfIdle(
            newBufferDurationMinutes = AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES,
            newPreset = QualityPreset.DEFAULT,
        )
        RecorderService.acknowledgeResizeRefusal()
        RecorderService.maxRetentionMinutesProvider = { preset ->
            DeviceMemoryBudget.maxRetentionMinutes(
                config = preset.config(AudioConfig.RETENTION_WINDOW_MIN_MINUTES),
                maxHeapBytes = Runtime.getRuntime().maxMemory(),
                usedHeapBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            )
        }
    }

    @Test
    fun `a ceiling that shrinks between switchSettings and rebuildEngineIfIdle leaves the engine unrebuilt`() {
        val engineBefore = RecorderService.engine
        assertEquals(
            "precondition: the engine starts out at the default preset",
            QualityPreset.DEFAULT.config(WINDOW_MINUTES).sampleRateHz,
            RecorderService.captureConfig.sampleRateHz,
        )

        // Step 1, at the generous ceiling: the switch is approved and committed.
        val applied = RecorderService.switchSettings(
            newBufferDurationMinutes = WINDOW_MINUTES,
            newPreset = QualityPreset.HIGH_FIDELITY,
        )
        assertTrue("the switch must be approved at the generous ceiling", applied)
        assertEquals(
            "and the service must now report the new format as committed",
            44_100,
            RecorderService.captureConfig.sampleRateHz,
        )

        // Memory pressure arrives between the two calls `commitPending` makes back to back --
        // exactly the non-stationary live sample issue #317 documents.
        RecorderService.maxRetentionMinutesProvider = { TIGHT_CEILING_MINUTES }

        // Step 2: declines, silently, because the value it re-checks is now above the ceiling.
        val rebuilt = RecorderService.rebuildEngineIfIdle(
            newBufferDurationMinutes = WINDOW_MINUTES,
            newPreset = QualityPreset.HIGH_FIDELITY,
        )

        assertFalse("the rebuild must decline at the tightened ceiling", rebuilt)
        assertSame(
            "the same engine instance is still live, so it is still *constructed* with the old " +
                "format while the service reports the new one -- the window issue #322's audio " +
                "corruption used to live in, and which nothing repairs until a later commit " +
                "succeeds while Idle, or the process restarts",
            engineBefore,
            RecorderService.engine,
        )
        assertEquals(
            "the engine's active config did move, which is what made the two disagree",
            44_100,
            RecorderService.engine.activeConfig.sampleRateHz,
        )
    }

    @Test
    fun `a not-Idle capture state declines the rebuild the same way`() {
        // The other, non-racy route to the same window: the engine reports Error (mic taken by
        // another app, or the OS refusing foreground promotion), settings still commit, and the
        // rebuild declines on the state check rather than the ceiling.
        val engineBefore = RecorderService.engine
        RecorderService.engine.reportForegroundPromotionRefused(
            cc.machado.audioblackbox.audio.CaptureErrorReason.FOREGROUND_SERVICE_PROMOTION_REFUSED,
            "simulated: the OS refused to promote the service",
        )
        assertTrue(
            "precondition: capture state must be Error, not Idle",
            RecorderService.captureState.value is cc.machado.audioblackbox.audio.CaptureState.Error,
        )

        val applied = RecorderService.switchSettings(
            newBufferDurationMinutes = WINDOW_MINUTES,
            newPreset = QualityPreset.HIGH_FIDELITY,
        )
        val rebuilt = RecorderService.rebuildEngineIfIdle(
            newBufferDurationMinutes = WINDOW_MINUTES,
            newPreset = QualityPreset.HIGH_FIDELITY,
        )

        assertTrue("the switch itself is not gated on capture state", applied)
        assertFalse("the rebuild must decline while capture state is not Idle", rebuilt)
        assertSame("so the engine instance is again left unrebuilt", engineBefore, RecorderService.engine)
        // Restoring the companion is tearDown's job, not this method's -- see tearDown.
    }

    private companion object {
        const val WINDOW_MINUTES = 5
        const val GENEROUS_CEILING_MINUTES = 45
        /** Below [WINDOW_MINUTES], i.e. "not even the minimum window fits any more". */
        const val TIGHT_CEILING_MINUTES = 0
    }
}
