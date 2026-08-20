package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.AudioSnapshot
import cc.machado.audioblackbox.audio.PauseGap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Orchestration tests for [ExportEngine] (issue #5): snapshot-null/empty surfaces a real error
 * (never a silent no-op), a sink-open failure surfaces as an error and never calls commit, and a
 * cancelled export aborts the sink instead of committing a partial file.
 */
class ExportEngineTest {

    private val config = AudioConfig(sampleRateHz = 1000, channelCount = 1)

    private class FakeTarget : ExportTarget {
        val buffer = ByteArrayOutputStream()
        var committed = false
        var aborted = false
        override val outputStream: OutputStream = buffer
        override fun commit() {
            committed = true
        }
        override fun abort() {
            aborted = true
        }
    }

    private class FakeSink(private val target: FakeTarget, private val failOpen: Boolean = false) : ExportSink {
        var openedWith: String? = null
        var openCount = 0
        override fun open(displayName: String): ExportTarget {
            openCount++
            if (failOpen) throw IOException("insert rejected")
            openedWith = displayName
            return target
        }
    }

    @Test
    fun `null snapshot surfaces NO_AUDIO_BUFFERED, never a silent no-op`() {
        val target = FakeTarget()
        val sink = FakeSink(target)
        val engine = ExportEngine(config, snapshotProvider = { null }, gapsProvider = { emptyList() }, sink = sink)

        val result = engine.export(durationMillis = 1000, minutesLabel = 1)

        assertTrue(result is ExportState.Error)
        assertEquals(ExportFailureReason.NO_AUDIO_BUFFERED, (result as ExportState.Error).reason)
        assertTrue("must not have opened a sink for nothing to export", sink.openedWith == null)
    }

    @Test
    fun `empty snapshot data surfaces NO_AUDIO_BUFFERED`() {
        val target = FakeTarget()
        val sink = FakeSink(target)
        val engine = ExportEngine(
            config,
            snapshotProvider = { AudioSnapshot(ByteArray(0), 0L) },
            gapsProvider = { emptyList() },
            sink = sink,
        )

        val result = engine.export(durationMillis = 1000, minutesLabel = 1)

        assertTrue(result is ExportState.Error)
        assertEquals(ExportFailureReason.NO_AUDIO_BUFFERED, (result as ExportState.Error).reason)
    }

    @Test
    fun `sink open failure surfaces SINK_OPEN_FAILED and never commits`() {
        val target = FakeTarget()
        val sink = FakeSink(target, failOpen = true)
        val engine = ExportEngine(
            config,
            snapshotProvider = { AudioSnapshot(ByteArray(1000), 0L) },
            gapsProvider = { emptyList() },
            sink = sink,
        )

        val result = engine.export(durationMillis = 1000, minutesLabel = 1)

        assertTrue(result is ExportState.Error)
        assertEquals(ExportFailureReason.SINK_OPEN_FAILED, (result as ExportState.Error).reason)
        assertTrue(!target.committed)
    }

    @Test
    fun `successful export writes header plus payload and commits, never aborts`() {
        val target = FakeTarget()
        val sink = FakeSink(target)
        val rawData = ByteArray(1000) { 7 }
        val engine = ExportEngine(
            config,
            snapshotProvider = { AudioSnapshot(rawData, 0L) },
            gapsProvider = { emptyList() },
            sink = sink,
        )

        val result = engine.export(durationMillis = 1000, minutesLabel = 1)

        assertTrue(result is ExportState.Success)
        assertTrue(target.committed)
        assertTrue(!target.aborted)
        val written = target.buffer.toByteArray()
        assertEquals(WavWriter.HEADER_SIZE_BYTES + 1000, written.size)
        // Filename encodes the capture window start (timezone-independent check: the format and
        // the "1min" suffix, not a hardcoded epoch string that would only match in UTC).
        val name = requireNotNull(sink.openedWith)
        assertTrue(name.startsWith("blackbox_"))
        assertTrue(name.endsWith("_1min.wav"))
        assertTrue(name.matches(Regex("blackbox_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_1min\\.wav")))
    }

    @Test
    fun `cancel arriving mid-export aborts the sink instead of committing`() {
        val target = FakeTarget()
        val sink = FakeSink(target)
        val rawData = ByteArray(1_000_000) { 3 } // large enough to span multiple write chunks
        lateinit var engine: ExportEngine
        engine = ExportEngine(
            config,
            // Simulates a concurrent caller invoking cancel() while the snapshot/gap-fill work is
            // already under way, the same way a real cancel button would race the background
            // export thread -- export()'s own reset-on-entry happens before this runs, so this
            // cancel() call is the one that actually takes effect.
            snapshotProvider = { engine.cancel(); AudioSnapshot(rawData, 0L) },
            gapsProvider = { emptyList() },
            sink = sink,
        )

        val result = engine.export(durationMillis = 1_000_000, minutesLabel = 1)

        assertTrue(result is ExportState.Error)
        assertEquals(ExportFailureReason.CANCELLED, (result as ExportState.Error).reason)
        assertTrue(target.aborted)
        assertTrue(!target.committed)
    }

    @Test
    fun `a concurrent export call while one is in flight is rejected without touching the sink`() {
        val target = FakeTarget()
        val sink = FakeSink(target)
        lateinit var engine: ExportEngine
        var reentrantResult: ExportState? = null
        engine = ExportEngine(
            config,
            // Simulates a second ACTION_SAVE dispatch racing in while this export is still
            // mid-flight (double-tap on the notification's Save action, or an OS-redelivered
            // Intent -- the scenario `@sec` flagged for RecorderService.handleSave()). export()
            // sets _state to Exporting before calling snapshotProvider, so by the time this runs
            // the outer call has already claimed the "in progress" slot; this recursive call must
            // observe that and bail out instead of racing cancelRequested/_state with it.
            snapshotProvider = {
                reentrantResult = engine.export(durationMillis = 1000, minutesLabel = 1)
                AudioSnapshot(ByteArray(1000), 0L)
            },
            gapsProvider = { emptyList() },
            sink = sink,
        )

        val result = engine.export(durationMillis = 1000, minutesLabel = 1)

        assertTrue("outer (first) export must still succeed", result is ExportState.Success)
        assertTrue(reentrantResult is ExportState.Error)
        assertEquals(
            ExportFailureReason.EXPORT_ALREADY_IN_PROGRESS,
            (reentrantResult as ExportState.Error).reason,
        )
        // The rejected call must never reach the sink -- only the outer export's single open()
        // call, never a second one that could produce a duplicate MediaStore row.
        assertEquals(1, sink.openCount)
    }

    @Test
    fun `padding requests extra raw audio equal to the sum of gap durations`() {
        var requestedDurationMillis = -1L
        val target = FakeTarget()
        val sink = FakeSink(target)
        val gaps = listOf(PauseGap(100L, 200L), PauseGap(300L, 450L)) // 100ms + 150ms = 250ms total
        val engine = ExportEngine(
            config,
            snapshotProvider = { requested ->
                requestedDurationMillis = requested
                AudioSnapshot(ByteArray(1000), 0L)
            },
            gapsProvider = { gaps },
            sink = sink,
        )

        engine.export(durationMillis = 1000, minutesLabel = 1)

        assertEquals(1250L, requestedDurationMillis)
    }
}
