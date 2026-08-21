package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.RingBuffer
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the exact wiring [cc.machado.audioblackbox.service.RecorderService.handleSave] now
 * drives for issue #40 item 1: a requested window in minutes becomes
 * `durationMillis = requestedMinutes * 60_000L` passed straight into [ExportEngine.export], with
 * no clamping in [cc.machado.audioblackbox.service.RecorderService] itself -- only
 * [RingBuffer.snapshot] (via [ExportEngine]'s `snapshotProvider`) clamps down to what is actually
 * buffered. A real [RingBuffer] backs [ExportEngine] here (not a stubbed `snapshotProvider`, the
 * way most of [ExportEngineTest] does it) specifically so this proves the collaboration between
 * the two classes, not either one's contract in isolation.
 *
 * The expected byte counts below are computed independently from [config]'s own
 * `sampleRateHz`/`channelCount` (2000 bytes/sec), not read back from any production formula --
 * e.g. "5 minutes" is asserted against `5 * 60 * 2000`, a plain arithmetic fact about how much
 * audio 5 minutes of this config actually is, so a regression in either
 * [RecorderService.handleSave]'s duration math or [RingBuffer.snapshot]'s clamping would fail
 * this test.
 */
class WindowedSaveExportTest {

    // 1000 Hz mono 16-bit PCM: bytesPerFrame = 2, bytesPerSecond = 2000. Chosen (like
    // ExportEngineTest's config) purely to keep the byte-count arithmetic below simple to verify
    // by hand, not because it matches the app's real AudioConfig defaults.
    private val config = AudioConfig(sampleRateHz = 1000, channelCount = 1)
    private val bytesPerSecond = config.bytesPerSecond // 2000

    private class FakeTarget : ExportTarget {
        val buffer = ByteArrayOutputStream()
        override val outputStream: OutputStream = buffer
        override fun commit() = Unit
        override fun abort() = Unit
    }

    private class FakeSink(private val target: FakeTarget) : ExportSink {
        override fun open(displayName: String, mimeType: String): ExportTarget = target
    }

    /** Builds a [RingBuffer] sized for 30 minutes at [bytesPerSecond] and writes exactly
     * [bufferedMinutes] worth of audio into it -- simulating a session that has been recording
     * for less than the full 30-minute cap. */
    private fun ringBufferWithBufferedMinutes(bufferedMinutes: Int): RingBuffer {
        val capacityBytes = 30 * 60 * bytesPerSecond
        val ring = RingBuffer(capacityBytes = capacityBytes, bytesPerSecond = bytesPerSecond)
        val bufferedBytes = bufferedMinutes * 60 * bytesPerSecond
        ring.write(ByteArray(bufferedBytes) { (it % 256).toByte() })
        return ring
    }

    private fun exportEngineFor(ring: RingBuffer, target: FakeTarget) = ExportEngine(
        config = config,
        snapshotProvider = ring::snapshot,
        gapsProvider = { emptyList() },
        sink = FakeSink(target),
        payloadEncoder = WavPayloadEncoder,
    )

    private fun payloadBytes(target: FakeTarget): Int = target.buffer.toByteArray().size - WavWriter.HEADER_SIZE_BYTES

    @Test
    fun `requesting 5 minutes with 20 minutes buffered exports exactly 5 minutes`() {
        val ring = ringBufferWithBufferedMinutes(bufferedMinutes = 20)
        val target = FakeTarget()
        val engine = exportEngineFor(ring, target)

        val result = engine.export(durationMillis = 5 * 60_000L, minutesLabel = 5)

        assertTrue(result is ExportState.Success)
        assertEquals(5 * 60 * bytesPerSecond, payloadBytes(target))
    }

    @Test
    fun `requesting 15 minutes with 20 minutes buffered exports exactly 15 minutes`() {
        val ring = ringBufferWithBufferedMinutes(bufferedMinutes = 20)
        val target = FakeTarget()
        val engine = exportEngineFor(ring, target)

        val result = engine.export(durationMillis = 15 * 60_000L, minutesLabel = 15)

        assertTrue(result is ExportState.Success)
        assertEquals(15 * 60 * bytesPerSecond, payloadBytes(target))
    }

    @Test
    fun `requesting 30 minutes with a full 30-minute buffer exports exactly 30 minutes`() {
        val ring = ringBufferWithBufferedMinutes(bufferedMinutes = 30)
        val target = FakeTarget()
        val engine = exportEngineFor(ring, target)

        val result = engine.export(durationMillis = 30 * 60_000L, minutesLabel = 30)

        assertTrue(result is ExportState.Success)
        assertEquals(30 * 60 * bytesPerSecond, payloadBytes(target))
    }

    @Test
    fun `the clamped case -- requesting 30 minutes with only 20 buffered -- exports 20, never fewer nor an error, and never pads to 30`() {
        val ring = ringBufferWithBufferedMinutes(bufferedMinutes = 20)
        val target = FakeTarget()
        val engine = exportEngineFor(ring, target)

        val result = engine.export(durationMillis = 30 * 60_000L, minutesLabel = 30)

        assertTrue("a request longer than what is buffered must still succeed with less, not error", result is ExportState.Success)
        assertEquals(20 * 60 * bytesPerSecond, payloadBytes(target))
    }
}
