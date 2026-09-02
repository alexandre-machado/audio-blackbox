package cc.machado.audioblackbox.audio

import android.media.AudioRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for [AudioCaptureEngine.switchConfig]'s all-or-nothing refusal (issue #272).
 *
 * ## The oracle
 * When a live session's buffer resize is refused (given an injected [MemoryBudget] the test
 * controls), [switchConfig] must return [SwitchConfigResult.BufferResizeRefused], leave
 * [AudioCaptureEngine.activeConfig] exactly as it was before the call, leave the capture state
 * untouched (still [CaptureState.Recording], never [CaptureState.Error] -- a refused resize is not
 * a capture failure), and leave every byte already buffered intact. Verified by mutation: forcing
 * [AudioCaptureEngine.switchConfig]'s `outcome is ResizeOutcome.Refused` branch to never trigger
 * (treating every outcome as applied) flips [refused a resize leaves activeConfig and the buffer
 * completely untouched] from green to red, because `activeConfig` and `capacityBytes` then
 * observably change.
 */
class AudioCaptureEngineSwitchConfigTest {

    private val startConfig = AudioConfig(sampleRateHz = 16_000, channelCount = 1, bufferDurationMinutes = 1)

    private fun fakeAudioRecord(): AudioRecord {
        val record = mock<AudioRecord>()
        whenever(record.state).thenReturn(AudioRecord.STATE_INITIALIZED)
        whenever(record.recordingState).thenReturn(AudioRecord.RECORDSTATE_RECORDING)
        return record
    }

    private fun withMinBufferSizeMocked(value: Int = 4096, body: () -> Unit) {
        val staticMock = Mockito.mockStatic(AudioRecord::class.java)
        staticMock.`when`<Int> {
            AudioRecord.getMinBufferSize(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
        }.thenReturn(value)
        try {
            body()
        } finally {
            staticMock.close()
        }
    }

    private fun awaitState(
        engine: AudioCaptureEngine,
        predicate: (CaptureState) -> Boolean,
        timeoutMillis: Long = 2_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (predicate(engine.state.value)) return
            Thread.sleep(1)
        }
        org.junit.Assert.fail("timed out waiting for expected state, last state was ${engine.state.value}")
    }

    @Test
    fun `a refused resize leaves activeConfig, capture state, and buffered audio completely untouched`() =
        withMinBufferSizeMocked {
            val record = fakeAudioRecord()
            val engine = AudioCaptureEngine(config = startConfig, audioRecordFactory = { _, _ -> record })
            engine.start()
            awaitState(engine, { it is CaptureState.Recording })

            val originalConfig = engine.activeConfig

            val alwaysRefuse = MemoryBudget { MemorySample(maxHeapBytes = 1L, usedHeapBytes = 1L) }
            val biggerConfig = AudioConfig(sampleRateHz = 16_000, channelCount = 1, bufferDurationMinutes = 90)

            val result = engine.switchConfig(biggerConfig, memoryBudget = alwaysRefuse)

            assertTrue("expected BufferResizeRefused, got $result", result is SwitchConfigResult.BufferResizeRefused)
            assertEquals("activeConfig must be unchanged by a refused switch", originalConfig, engine.activeConfig)
            assertTrue(
                "capture must still be Recording, not Error, after a refused resize",
                engine.state.value is CaptureState.Recording,
            )

            engine.stop()
        }

    @Test
    fun `switchConfig applies normally when the budget allows it`() = withMinBufferSizeMocked {
        val record = fakeAudioRecord()
        val engine = AudioCaptureEngine(config = startConfig, audioRecordFactory = { _, _ -> record })
        engine.start()
        awaitState(engine, { it is CaptureState.Recording })

        val generousBudget = MemoryBudget { MemorySample(maxHeapBytes = 4L * 1024 * 1024 * 1024, usedHeapBytes = 0L) }
        val newConfig = AudioConfig(sampleRateHz = 16_000, channelCount = 1, bufferDurationMinutes = 2)

        val result = engine.switchConfig(newConfig, memoryBudget = generousBudget)

        assertEquals(SwitchConfigResult.Applied, result)
        assertEquals(newConfig, engine.activeConfig)

        engine.stop()
    }
}
