package cc.machado.audioblackbox.service

import android.media.AudioRecord
import cc.machado.audioblackbox.audio.AudioCaptureEngine
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.export.ExportState
import cc.machado.audioblackbox.export.ExportSink
import cc.machado.audioblackbox.export.ExportTarget
import cc.machado.audioblackbox.export.WavPayloadEncoder
import cc.machado.audioblackbox.export.WavWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Proves issue #385's saturated-buffer startup headroom actually reaches a real device, by going
 * through [buildExportEngine] -- the exact function [RecorderService.exportEngine] calls -- rather
 * than [ExportEngine]'s constructor directly the way every other test in this repo does.
 *
 * `@rev` BLOCK review on PR #386: `RecorderService.kt`'s production `exportEngine` called
 * [ExportEngine]'s primary constructor without wiring `capacityBytesProvider`, so it silently kept
 * that parameter's old `{ null }` default and #385's headroom never actually ran on a shipped
 * build, despite every unit test for [ExportEngine] passing -- because every one of those tests
 * (see [cc.machado.audioblackbox.export.ExportEngineTest] and its siblings) exercises
 * [ExportEngine] directly, never through [RecorderService]'s own assembly path. This test closes
 * exactly that gap: it drives a real [AudioCaptureEngine] (the [AudioCaptureEngine.audioRecordFactory]
 * seam plus Mockito's inline mock maker fakes `AudioRecord`, same pattern as
 * [cc.machado.audioblackbox.audio.AudioCaptureEngineTest] -- no Robolectric needed), saturates its
 * real [cc.machado.audioblackbox.audio.RingBuffer], and asserts the exported payload is short by
 * exactly the startup-headroom margin -- which only happens if [buildExportEngine] actually wired
 * `capacityBytesProvider` through to a live [AudioCaptureEngine.capacityBytes] call.
 */
class BuildExportEngineWiringTest {

    // sampleRateHz=100, channelCount=1, PCM_16: bytesPerFrame=2, bytesPerSecond=200.
    // bufferDurationMinutes=1 -> capacityBytes = 200 * 60 = 12_000. Small enough to saturate with
    // a handful of reads, well inside typical test speed, no real-time audio pacing involved.
    private val config = AudioConfig(sampleRateHz = 100, channelCount = 1, bufferDurationMinutes = 1)
    private val capacityBytes = config.totalBufferBytes.toInt() // 12_000
    private val minBufferSize = 1_000 // multiple of bytesPerFrame(2); 12 reads exactly saturate it
    private val readsToSaturate = capacityBytes / minBufferSize // 12

    private class FakeTarget : ExportTarget {
        val buffer = ByteArrayOutputStream()
        override val outputStream: OutputStream = buffer
        override fun commit() {}
        override fun abort() {}
    }

    private class FakeSink(private val target: FakeTarget) : ExportSink {
        override fun open(displayName: String, mimeType: String): ExportTarget = target
    }

    private val pendingStaticMocks = mutableListOf<org.mockito.MockedStatic<AudioRecord>>()

    private fun mockMinBufferSize(value: Int) {
        val staticMock = Mockito.mockStatic(AudioRecord::class.java)
        staticMock.`when`<Int> {
            AudioRecord.getMinBufferSize(any(), any(), any())
        }.thenReturn(value)
        pendingStaticMocks.add(staticMock)
    }

    private fun withMinBufferSizeMocked(value: Int, body: () -> Unit) {
        mockMinBufferSize(value)
        try {
            body()
        } finally {
            pendingStaticMocks.forEach { it.close() }
            pendingStaticMocks.clear()
        }
    }

    private fun awaitWriteCursorAtLeast(engine: AudioCaptureEngine, target: Long, timeoutMillis: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val cursor = engine.writeCursor()
            if (cursor != null && cursor >= target) return
            Thread.sleep(1)
        }
        fail("timed out waiting for writeCursor >= $target, last value was ${engine.writeCursor()}")
    }

    @Test
    fun `RecorderService's real ExportEngine wiring applies the issue 385 startup headroom on a saturated buffer`() =
        withMinBufferSizeMocked(minBufferSize) {
            val record = mock<AudioRecord>()
            whenever(record.state).thenReturn(AudioRecord.STATE_INITIALIZED)
            whenever(record.recordingState).thenReturn(AudioRecord.RECORDSTATE_RECORDING)

            // Writes exactly `readsToSaturate` chunks of `minBufferSize` (saturating the buffer to
            // exactly `capacityBytes`), then freezes forever (read() returns 0, a normal "no new
            // data yet" idle poll per AudioCaptureEngine.captureLoop -- no write happens, no
            // further cursor movement) so the buffer is genuinely static while export() runs: this
            // test asserts an exact byte count, and a live concurrent writer racing the drain would
            // make that assertion flaky for reasons unrelated to what this test is proving.
            val reads = AtomicInteger(0)
            whenever(record.read(any<ByteArray>(), any(), any())).thenAnswer {
                if (reads.getAndIncrement() < readsToSaturate) minBufferSize else 0
            }

            val engine = AudioCaptureEngine(config = config, audioRecordFactory = { _, _ -> record })
            engine.start()
            try {
                awaitWriteCursorAtLeast(engine, capacityBytes.toLong())
                // The buffer is saturated and frozen (further read()s return 0) -- writeCursor
                // cannot move again, so there is no race left to wait out.

                val target = FakeTarget()
                val exportEngine = buildExportEngine(
                    engine = engine,
                    config = config,
                    sink = FakeSink(target),
                    payloadEncoder = WavPayloadEncoder,
                    minExportDurationMillis = 0L,
                    errorLogFile = null,
                    configProvider = { config },
                )

                val result = exportEngine.export(durationMillis = 60_000L, minutesLabel = 1)

                assertTrue("expected Success, got $result", result is ExportState.Success)
                val payloadBytes = target.buffer.toByteArray().size - WavWriter.HEADER_SIZE_BYTES
                // 200 B/s * 250ms startup headroom = 50 bytes (already frame-aligned). This can
                // only be non-zero if buildExportEngine's capacityBytesProvider genuinely reaches
                // AudioCaptureEngine.capacityBytes() and reports the buffer as saturated -- exactly
                // the wiring `@rev`'s BLOCK review found missing in RecorderService.
                assertEquals(
                    "expected the saturated-buffer startup headroom (50 bytes) to be discarded " +
                        "through RecorderService's real ExportEngine wiring",
                    capacityBytes - 50,
                    payloadBytes,
                )
            } finally {
                engine.stop()
            }
        }
}
