package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.RingBuffer
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Corrected per `@techlead` adjudication on PR #43: an earlier version of this doc claimed this
 * class exercised the wiring "the same way `handleSave()` now drives them". It does not, and
 * cannot from a plain JVM unit test: `RecorderService.handleSave` is `private` and hosted on an
 * Android `Service`, reachable only through `onStartCommand`/a real or instrumented `Intent`
 * dispatch. Claiming that coverage here would have been worse than no comment at all -- the next
 * person reading it would trust it and skip writing the real test.
 *
 * ## What this class actually proves
 * [ExportEngine]/[RingBuffer] correctly collaborate on a requested window in minutes: a real
 * [RingBuffer] (not stubbed cursor/read providers, the way most of [ExportEngineTest] does it)
 * backs [ExportEngine], and requesting `durationMillis = requestedMinutes * 60_000L` for 5, 15,
 * and 30 minutes -- plus the case where the request exceeds what is buffered -- produces exactly
 * the expected number of bytes, computed independently from [config]'s own
 * `sampleRateHz`/`channelCount` (2000 bytes/sec: e.g. "5 minutes" is asserted against
 * `5 * 60 * 2000`, a plain arithmetic fact about how much audio 5 minutes of this config actually
 * is), not read back from any production formula.
 *
 * ## What this class does *not* prove, and where that gap actually lives
 * The intent -> `RecorderService.onStartCommand` -> `handleSave()` -> `exportEngine.export(...)`
 * wiring -- i.e. that a real `ACTION_SAVE` Intent actually reaches this same
 * [ExportEngine]/[RingBuffer] collaboration -- is untested by this class and, as far as this PR
 * goes, untested anywhere else either. Closing that gap needs either `RecorderService`'s
 * Service-hosted logic to be reachable from a plain unit test (a production seam this PR does not
 * introduce) or an instrumented test dispatching a real `Intent`.
 *
 * The 5/15/30-minute values exercised below predate issue #121 (which retired the dashboard's
 * chip selector that used to request exactly those windows) and are kept as arbitrary durations
 * to exercise [ExportEngine]'s own clamp-to-buffered behavior -- see
 * `RecorderService.resolveSavedMinutes` for where issue #121 moved the "label the file honestly"
 * concern this class's requested-vs-buffered mismatch used to stand in for.
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
        readSinceProvider = { cursor, maxBytes -> ring.readSince(cursor, maxBytes) },
        writeCursorProvider = { ring.writeCursor() },
        oldestCursorProvider = { ring.oldestCursor() },
        estimateTimestampProvider = { offset -> ring.estimateTimestamp(offset) },
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
