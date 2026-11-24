package cc.machado.audioblackbox.audio

import org.junit.Assert.*
import org.junit.Test

class IdleAllocationAdmissionTest {
    private class ReclaimBudget(private val afterReclaimMb: Long) : MemoryBudget {
        var samples = 0
        var reclaims = 0
        val caller = Thread.currentThread()
        override fun sample(): MemorySample {
            samples++
            return MemorySample(256L * 1024 * 1024, (if (reclaims == 0) 215L else afterReclaimMb) * 1024 * 1024)
        }
        override fun reclaim() {
            assertSame("reclaim must stay on the admission caller, off the capture thread", caller, Thread.currentThread())
            reclaims++
        }
    }

    /** Oracle: a reclaimable high-water mark must allow idle config admission after exactly
     * one reclaim and two samples; removing the re-sample must leave the old config in place. */
    @Test fun idleConfigProceedsAfterReclaim() {
        val budget = ReclaimBudget(20)
        val engine = AudioCaptureEngine(config = QualityPreset.VOICE.config(5))
        val requested = QualityPreset.HIGH_FIDELITY.config(5)
        assertEquals(SwitchConfigResult.Applied, engine.switchConfig(requested, budget))
        assertEquals(requested, engine.activeConfig)
        assertEquals(1, budget.reclaims)
        assertEquals(2, budget.samples)
        assertNull(engine.capacityBytes())
    }

    /** Oracle: start reaches microphone setup after reclaim, instead of reporting a false
     * allocation failure. The injected unsupported mic stops before launching capture. */
    @Test fun startProceedsAfterReclaim() {
        val budget = ReclaimBudget(20)
        var micChecks = 0
        val engine = AudioCaptureEngine(config = QualityPreset.VOICE.config(5),
            minBufferSizeProvider = { micChecks++; -1 })
        engine.start(budget)
        assertEquals(1, micChecks)
        assertEquals(CaptureErrorReason.UNSUPPORTED_CONFIG, (engine.state.value as? CaptureState.Error)?.reason)
        assertEquals(1, budget.reclaims)
        assertEquals(2, budget.samples)
    }

    /** Oracle: unreclaimed pressure still refuses both entry points, without looping or
     * touching the microphone, and idle config is preserved. */
    @Test fun persistentPressureRefusesAfterOneReclaim() {
        val engine = AudioCaptureEngine(config = QualityPreset.VOICE.config(5),
            minBufferSizeProvider = { error("must refuse before microphone setup") })
        val original = engine.activeConfig
        val idleBudget = ReclaimBudget(215)
        assertTrue(engine.switchConfig(QualityPreset.HIGH_FIDELITY.config(5), idleBudget)
            is SwitchConfigResult.BufferResizeRefused)
        assertEquals(original, engine.activeConfig)
        assertEquals(1, idleBudget.reclaims)
        assertEquals(2, idleBudget.samples)
        val startBudget = ReclaimBudget(215)
        engine.start(startBudget)
        assertEquals(CaptureErrorReason.BUFFER_ALLOCATION_FAILED, (engine.state.value as? CaptureState.Error)?.reason)
        assertEquals(1, startBudget.reclaims)
        assertEquals(2, startBudget.samples)
        assertNull(engine.capacityBytes())
    }

    /** Oracle: affordable admission samples once and never requests GC. */
    @Test fun affordableAdmissionDoesNotReclaim() {
        var samples = 0
        val budget = object : MemoryBudget {
            override fun sample(): MemorySample {
                samples++
                return MemorySample(256L * 1024 * 1024, 20L * 1024 * 1024)
            }
            override fun reclaim() = error("affordable allocation must not request GC")
        }
        val engine = AudioCaptureEngine(config = QualityPreset.VOICE.config(5), minBufferSizeProvider = { -1 })
        assertEquals(SwitchConfigResult.Applied, engine.switchConfig(QualityPreset.VOICE.config(10), budget))
        assertEquals(1, samples)
        engine.start(budget)
        assertEquals(2, samples)
        assertEquals(CaptureErrorReason.UNSUPPORTED_CONFIG, (engine.state.value as? CaptureState.Error)?.reason)
    }

    private val poor = MemoryBudget { MemorySample(256L * 1024 * 1024, 215L * 1024 * 1024) }

    /** Oracle: even the offered minimum must be refused without changing idle config. */
    @Test fun idleMinimumIsRefusedBeforeAcceptingConfig() {
        val engine = AudioCaptureEngine(config = QualityPreset.VOICE.config(5))
        val original = engine.activeConfig
        val result = engine.switchConfig(QualityPreset.HIGH_FIDELITY.config(5), poor)
        assertTrue("unfundable minimum must be refused", result is SwitchConfigResult.BufferResizeRefused)
        assertEquals(original, engine.activeConfig)
        assertNull(engine.capacityBytes())
    }

    /** Oracle: memory lost after admission produces the existing visible allocation error,
     * before microphone setup (which would instead report UNSUPPORTED_CONFIG). */
    @Test fun startRechecksMemoryAndReportsVisibleRefusal() {
        val engine = AudioCaptureEngine(config = QualityPreset.VOICE.config(5), minBufferSizeProvider = { -1 })
        assertEquals(SwitchConfigResult.Applied, engine.switchConfig(QualityPreset.HIGH_FIDELITY.config(5),
            MemoryBudget { MemorySample(256L * 1024 * 1024, 20L * 1024 * 1024) }))
        engine.start(poor)
        assertEquals(CaptureErrorReason.BUFFER_ALLOCATION_FAILED, (engine.state.value as? CaptureState.Error)?.reason)
        assertNull(engine.capacityBytes())
    }
}
