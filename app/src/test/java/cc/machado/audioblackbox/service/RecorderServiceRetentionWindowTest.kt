package cc.machado.audioblackbox.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
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

    @Test
    fun `rebuildEngineIfIdle at a non-default capacity is reflected by every public mirror, not just one`() {
        val engineBefore = RecorderService.engine

        val applied = RecorderService.rebuildEngineIfIdle(60)

        assertTrue("engine was Idle, rebuild must succeed", applied)
        assertEquals(60, RecorderService.bufferDurationMinutes)
        assertEquals(60, RecorderService.captureConfig.bufferDurationMinutes)
        assertEquals(60, RecorderService.bufferDurationMinutesFlow.value)
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
    fun `rebuildEngineIfIdle rejects a capacity outside the bounded options and changes nothing`() {
        RecorderService.rebuildEngineIfIdle(30)
        val before = RecorderService.bufferDurationMinutes

        var thrown = false
        try {
            RecorderService.rebuildEngineIfIdle(45)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }

        assertTrue("45 is not one of AudioConfig.RETENTION_WINDOW_OPTIONS_MINUTES", thrown)
        assertEquals(before, RecorderService.bufferDurationMinutes)
    }
}
