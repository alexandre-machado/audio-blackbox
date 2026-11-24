package cc.machado.audioblackbox.audio

import org.junit.Assert.*
import org.junit.Test

class IdleAllocationAdmissionTest {
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
