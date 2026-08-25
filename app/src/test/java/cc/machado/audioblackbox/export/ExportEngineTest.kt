package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.PauseGap
import cc.machado.audioblackbox.audio.RingBuffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Orchestration tests for [ExportEngine] (issue #5): no-buffered-audio surfaces a real error
 * (never a silent no-op), a sink-open failure surfaces as an error and never calls commit, and a
 * cancelled export aborts the sink instead of committing a partial file.
 *
 * Backed by a real [RingBuffer] plus [ExportEngine]'s cursor-based
 * `readSinceProvider`/`writeCursorProvider`/`oldestCursorProvider`/`estimateTimestampProvider`
 * seams (issue #72's bounded cursor drain, replacing the old whole-window `snapshotProvider`) --
 * this is deliberately the same collaboration production wiring uses (see
 * [cc.machado.audioblackbox.service.RecorderService]'s `exportEngine`), just with an in-memory
 * [ExportSink] instead of `MediaStore`.
 *
 * Uses [WavPayloadEncoder] throughout (not the production default [AacPayloadEncoder], which is
 * `MediaCodec`/`MediaMuxer`-backed and covered by the instrumented tier instead -- see
 * `docs/testing/tiers.md`): everything asserted here is [ExportEngine]'s own orchestration, which
 * does not depend on which concrete [PayloadEncoder] is plugged in. `filenameExtensionMatchesEncoder`
 * below is the one test that specifically proves the filename/MIME-type wiring is driven by
 * whatever [PayloadEncoder] is injected (issue #32), not hardcoded to `.wav`.
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
        var openedWithMimeType: String? = null
        var openCount = 0
        override fun open(displayName: String, mimeType: String): ExportTarget {
            openCount++
            if (failOpen) throw IOException("insert rejected")
            openedWith = displayName
            openedWithMimeType = mimeType
            return target
        }
    }

    /** Builds a [RingBuffer]-backed [ExportEngine], the same cursor-based collaboration production
     * wiring uses. [gapsProvider] is the one seam most tests below hook a side effect onto (e.g.
     * `cancel()`, a reentrant `export()` call, a thrown exception) -- it is the first provider
     * [ExportEngine.runExport] calls after fixing the cursor window, mirroring where the old
     * `snapshotProvider`-based tests hooked the same side effects (that provider used to be called
     * first; now the cursor reads are cheap and side-effect-free, so the same "first thing this
     * export actually does real work with" role now belongs to [gapsProvider]). */
    private fun engineFor(
        ring: RingBuffer,
        sink: ExportSink,
        payloadEncoder: PayloadEncoder = WavPayloadEncoder,
        writeCursorProvider: () -> Long? = { ring.writeCursor() },
        gapsProvider: () -> List<PauseGap> = { emptyList() },
    ): ExportEngine = ExportEngine(
        config = config,
        readSinceProvider = { cursor, maxBytes -> ring.readSince(cursor, maxBytes) },
        writeCursorProvider = writeCursorProvider,
        oldestCursorProvider = { ring.oldestCursor() },
        estimateTimestampProvider = { offset -> ring.estimateTimestamp(offset) },
        gapsProvider = gapsProvider,
        sink = sink,
        payloadEncoder = payloadEncoder,
    )

    private fun ringWithBytes(byteCount: Int, fillValue: Byte = 7, capacityBytes: Int = maxOf(byteCount, 1)): RingBuffer {
        val ring = RingBuffer(capacityBytes = capacityBytes, bytesPerSecond = config.bytesPerSecond)
        if (byteCount > 0) ring.write(ByteArray(byteCount) { fillValue })
        return ring
    }

    @Test
    fun `capture not running surfaces NO_AUDIO_BUFFERED, never a silent no-op`() {
        val target = FakeTarget()
        val sink = FakeSink(target)
        val ring = ringWithBytes(1000)
        val engine = engineFor(ring, sink, writeCursorProvider = { null })

        val result = engine.export(durationMillis = 1000, minutesLabel = 1)

        assertTrue(result is ExportState.Error)
        assertEquals(ExportFailureReason.NO_AUDIO_BUFFERED, (result as ExportState.Error).reason)
        assertTrue("must not have opened a sink for nothing to export", sink.openedWith == null)
    }

    @Test
    fun `nothing buffered yet surfaces NO_AUDIO_BUFFERED`() {
        val target = FakeTarget()
        val sink = FakeSink(target)
        val ring = ringWithBytes(0, capacityBytes = 1000)
        val engine = engineFor(ring, sink)

        val result = engine.export(durationMillis = 1000, minutesLabel = 1)

        assertTrue(result is ExportState.Error)
        assertEquals(ExportFailureReason.NO_AUDIO_BUFFERED, (result as ExportState.Error).reason)
    }

    @Test
    fun `sink open failure surfaces SINK_OPEN_FAILED and never commits`() {
        val target = FakeTarget()
        val sink = FakeSink(target, failOpen = true)
        val ring = ringWithBytes(1000)
        val engine = engineFor(ring, sink)

        val result = engine.export(durationMillis = 1000, minutesLabel = 1)

        assertTrue(result is ExportState.Error)
        assertEquals(ExportFailureReason.SINK_OPEN_FAILED, (result as ExportState.Error).reason)
        assertTrue(!target.committed)
    }

    @Test
    fun `successful export writes header plus payload and commits, never aborts`() {
        val target = FakeTarget()
        val sink = FakeSink(target)
        val ring = ringWithBytes(1000)
        val engine = engineFor(ring, sink)

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
        // Large enough to span multiple drain chunks (default chunk size is 4096 bytes).
        val ring = ringWithBytes(1_000_000, fillValue = 3)
        lateinit var engine: ExportEngine
        engine = engineFor(
            ring,
            sink,
            // Simulates a concurrent caller invoking cancel() while the plan/gap work is already
            // under way, the same way a real cancel button would race the background export
            // thread -- export()'s own reset-on-entry happens before this runs, so this cancel()
            // call is the one that actually takes effect.
            gapsProvider = { engine.cancel(); emptyList() },
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
        val ring = ringWithBytes(1000)
        lateinit var engine: ExportEngine
        var reentrantResult: ExportState? = null
        engine = engineFor(
            ring,
            sink,
            // Simulates a second ACTION_SAVE dispatch racing in while this export is still
            // mid-flight (double-tap on the notification's Save action, or an OS-redelivered
            // Intent -- the scenario `@sec` flagged for RecorderService.handleSave()). export()
            // sets _state to Exporting before calling gapsProvider, so by the time this runs the
            // outer call has already claimed the "in progress" slot; this recursive call must
            // observe that and bail out instead of racing cancelRequested/_state with it.
            gapsProvider = {
                reentrantResult = engine.export(durationMillis = 1000, minutesLabel = 1)
                emptyList()
            },
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
    fun `an unexpected non-IOException during export surfaces as an error and does not strand the engine`() {
        val target = FakeTarget()
        val sink = FakeSink(target)
        val ring = ringWithBytes(1000)
        var shouldThrow = true
        val engine = engineFor(
            ring,
            sink,
            // Simulates a genuinely unexpected failure (a future regression, an OOM, ...) from
            // somewhere inside runExport() -- gapsProvider() is called right after the cursor
            // window is fixed, before any PCM has been touched.
            gapsProvider = {
                if (shouldThrow) throw IllegalStateException("boom") else emptyList()
            },
        )

        val result = engine.export(durationMillis = 1000, minutesLabel = 1)

        assertTrue("a non-IOException must surface as an Error, never escape export()", result is ExportState.Error)
        assertEquals(ExportFailureReason.UNEXPECTED_FAILURE, (result as ExportState.Error).reason)
        assertTrue("state must reflect the same error, not be stranded on Exporting", engine.state.value is ExportState.Error)

        // The real user-visible consequence of a stranded Exporting state: every later export()
        // call gets permanently rejected with EXPORT_ALREADY_IN_PROGRESS. Prove a normal export
        // right after the failure still succeeds.
        shouldThrow = false
        val retry = engine.export(durationMillis = 1000, minutesLabel = 1)
        assertTrue(
            "a stranded Exporting state would reject this with EXPORT_ALREADY_IN_PROGRESS -- " +
                "export() must still be usable after an unexpected failure",
            retry is ExportState.Success,
        )
    }

    @Test
    fun `acknowledgeTerminalState resets a terminal outcome to Idle so it cannot linger forever`() {
        val target = FakeTarget()
        val sink = FakeSink(target)
        val ring = ringWithBytes(1000)
        val engine = engineFor(ring, sink)

        val result = engine.export(durationMillis = 1000, minutesLabel = 1)
        assertTrue(result is ExportState.Success)
        // The outcome must remain visible immediately after export() returns -- nothing should
        // have cleared it yet.
        assertTrue("terminal outcome must be visible before being acknowledged", engine.state.value is ExportState.Success)

        engine.acknowledgeTerminalState()

        assertEquals(
            "once acknowledged, a terminal outcome must not linger and be reasserted by an " +
                "unrelated later refresh",
            ExportState.Idle,
            engine.state.value,
        )
    }

    @Test
    fun `acknowledgeTerminalState is a no-op while an export is in flight`() {
        val target = FakeTarget()
        val sink = FakeSink(target)
        val ring = ringWithBytes(1000)
        lateinit var engine: ExportEngine
        engine = engineFor(
            ring,
            sink,
            gapsProvider = {
                // Mid-export, state is Exporting -- an acknowledge racing in here (e.g. a delayed
                // acknowledge from a previous export still pending) must not clear it.
                engine.acknowledgeTerminalState()
                assertEquals(ExportState.Exporting, engine.state.value)
                emptyList()
            },
        )

        engine.export(durationMillis = 1000, minutesLabel = 1)
    }

    @Test
    fun `gaps within the exported window are backfilled with silence without shortening the file`() {
        // 1000 Hz mono 16-bit PCM: bytesPerFrame = 2, bytesPerSecond = 2000.
        val target = FakeTarget()
        val sink = FakeSink(target)
        // 2500ms of raw (gap-free) audio-time buffered, starting at wall-clock 0 (fixed via
        // estimateTimestampProvider below, so the gap timestamps chosen here are exact byte
        // offsets rather than depending on RingBuffer's real-clock markers).
        val ring = ringWithBytes(byteCount = 5000, capacityBytes = 5000)
        // Two gaps landing inside the last requested second [1500ms, 2500ms): 100ms + 150ms of
        // real elapsed time that produced zero raw bytes. Without padding, a 1000ms export
        // covering that stretch would come back short by exactly that much; the bounded plan
        // compensates by drawing on buffered audio further back instead of trimming to less than
        // the requested duration (issue #72's "request extra raw audio up front" intent, now
        // expressed as "use the whole buffered window" -- see ExportEngine.runExport's doc).
        val gaps = listOf(PauseGap(1600L, 1700L), PauseGap(1800L, 1950L))
        val engine = ExportEngine(
            config = config,
            readSinceProvider = { cursor, maxBytes -> ring.readSince(cursor, maxBytes) },
            writeCursorProvider = { ring.writeCursor() },
            oldestCursorProvider = { ring.oldestCursor() },
            estimateTimestampProvider = { 0L },
            gapsProvider = { gaps },
            sink = sink,
            payloadEncoder = WavPayloadEncoder,
        )

        val result = engine.export(durationMillis = 1000, minutesLabel = 1)

        assertTrue(result is ExportState.Success)
        val payloadBytes = target.buffer.toByteArray().size - WavWriter.HEADER_SIZE_BYTES
        assertEquals(
            "the requested 1000ms (2000 bytes) must come back whole, not short by the 250ms of " +
                "silence the two gaps needed",
            1000 * config.bytesPerSecond / 1000,
            payloadBytes,
        )
    }

    /** A minimal non-WAV [PayloadEncoder] fake, so [filenameExtensionMatchesEncoder] proves the
     * filename/MIME-type wiring reads from whatever encoder is injected rather than being
     * hardcoded to WAV's `.wav`/`audio/wav` -- the exact wiring bug that would let a production
     * `.m4a` file be created with a `.wav` filename or vice versa. */
    private class FakeEncoder : PayloadEncoder {
        override val mimeType: String = "audio/x-fake"
        override val fileExtension: String = "fake"
        var encodeCalls = 0
        override fun encode(
            config: AudioConfig,
            totalPayloadBytes: Long,
            chunks: PayloadChunkSource,
            out: OutputStream,
            isCancelled: () -> Boolean,
        ) {
            encodeCalls++
            while (true) {
                val chunk = chunks.nextChunk() ?: break
                out.write(chunk)
            }
        }
    }

    @Test
    fun `filename extension and sink MIME type follow the injected PayloadEncoder, not a hardcoded format`() {
        val target = FakeTarget()
        val sink = FakeSink(target)
        val ring = ringWithBytes(100)
        val encoder = FakeEncoder()
        val engine = engineFor(ring, sink, payloadEncoder = encoder)

        val result = engine.export(durationMillis = 1000, minutesLabel = 5)

        assertTrue(result is ExportState.Success)
        assertEquals(1, encoder.encodeCalls)
        assertEquals("audio/x-fake", sink.openedWithMimeType)
        val name = requireNotNull(sink.openedWith)
        assertTrue("expected the injected encoder's extension, got $name", name.endsWith("_5min.fake"))
    }
}
