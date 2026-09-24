package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.PauseGap
import cc.machado.audioblackbox.audio.RingBuffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private companion object {
        const val WAV_HEADER_BYTES = 44
    }

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
        // issue #385: wired to the real ring by default, same as the other cursor providers above,
        // so tests exercise the same saturated-vs-not distinction production wiring does.
        capacityBytesProvider: () -> Int? = { ring.capacityBytes },
        estimateTimestampProvider: (Long) -> Long? = { offset -> ring.estimateTimestamp(offset) },
    ): ExportEngine = ExportEngine(
        config = config,
        readSinceProvider = { cursor, maxBytes -> ring.readSince(cursor, maxBytes) },
        writeCursorProvider = writeCursorProvider,
        oldestCursorProvider = { ring.oldestCursor() },
        capacityBytesProvider = capacityBytesProvider,
        exportFloorAdvancerProvider = { null },
        estimateTimestampProvider = estimateTimestampProvider,
        gapsProvider = gapsProvider,
        sink = sink,
        payloadEncoder = payloadEncoder,
    )

    // `@rev` review on PR #386 (issue #385): defaulting this to `capacityBytes = byteCount` would
    // silently saturate every call site that doesn't override it -- through `engineFor`'s real
    // `capacityBytesProvider`, that means ExportEngine's issue #385 startup headroom would kick in
    // and quietly discard some of the oldest written bytes on every test that doesn't ask for
    // saturation on purpose, exactly the "saturation masking an undercount" trap AGENTS.md §2
    // documents. Defaulting well above `byteCount` instead keeps every existing call site
    // genuinely unsaturated (byte-exact) unless it explicitly asks otherwise, the way the three
    // headroom-specific tests below already do (they build their own saturated `RingBuffer`
    // directly instead of going through this helper).
    private fun ringWithBytes(byteCount: Int, fillValue: Byte = 7, capacityBytes: Int = maxOf(byteCount * 10, 1)): RingBuffer {
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
    fun `segmentsProvider reporting null surfaces NO_AUDIO_BUFFERED instead of guessing targetConfig`() {
        // Issue #332 (`@rev`/`@sec` finding 2 on PR #323's last commit): a live `segmentsProvider`
        // that reports null (its record is gone, e.g. `AudioCaptureEngine.activeSegments()` once
        // the ring buffer is torn down mid-export) must not collapse to `emptyList()` the same way
        // a legacy no-provider caller's absence-by-construction does -- that collapse is what let
        // `BoundedExportPlanner.plan` synthesize "the whole window was recorded in targetConfig"
        // out of nothing, the #322 guess itself, one frame inward from the sites already fixed.
        // Mutation check: replacing the fix's `?: return Error(...)` with the old
        // `?: emptyList()` makes this test fail (the export would succeed instead of erroring).
        val target = FakeTarget()
        val sink = FakeSink(target)
        val ring = ringWithBytes(1000)
        val engine = ExportEngine(
            config = config,
            readSinceProvider = { cursor, maxBytes -> ring.readSince(cursor, maxBytes) },
            writeCursorProvider = { ring.writeCursor() },
            oldestCursorProvider = { ring.oldestCursor() },
            // issue #385: this test is about the segmentsProvider-null distinction, not the
            // saturated-buffer startup headroom -- `{ null }` keeps its pre-#385 behavior exactly.
            capacityBytesProvider = { null },
            exportFloorAdvancerProvider = { null },
            estimateTimestampProvider = { offset -> ring.estimateTimestamp(offset) },
            gapsProvider = { emptyList() },
            sink = sink,
            payloadEncoder = WavPayloadEncoder,
            segmentsProvider = { null },
        )

        val result = engine.export(durationMillis = 1000, minutesLabel = 1)

        assertTrue("expected an Error, got $result", result is ExportState.Error)
        assertEquals(ExportFailureReason.NO_AUDIO_BUFFERED, (result as ExportState.Error).reason)
        assertTrue("must not have opened a sink for a resolution failure", sink.openedWith == null)
    }

    @Test
    fun `no segmentsProvider at all (legacy constructor) still exports single-format, byte-identical PCM`() {
        // The other half of the same distinction: absence *by construction* (no provider) is not
        // an error -- it is the legacy single-format contract, and must keep passing bytes through
        // unconverted exactly as before.
        val target = FakeTarget()
        val sink = FakeSink(target)
        val fillValue: Byte = 42
        val ring = ringWithBytes(1000, fillValue = fillValue)
        val engine = ExportEngine(
            config = config,
            readSinceProvider = { cursor, maxBytes -> ring.readSince(cursor, maxBytes) },
            writeCursorProvider = { ring.writeCursor() },
            oldestCursorProvider = { ring.oldestCursor() },
            // issue #385: this test is about the legacy no-segmentsProvider passthrough, not the
            // saturated-buffer startup headroom -- `{ null }` keeps its pre-#385 behavior exactly.
            capacityBytesProvider = { null },
            exportFloorAdvancerProvider = { null },
            estimateTimestampProvider = { offset -> ring.estimateTimestamp(offset) },
            gapsProvider = { emptyList() },
            sink = sink,
            payloadEncoder = WavPayloadEncoder,
        )

        val result = engine.export(durationMillis = 1000, minutesLabel = 1)

        assertTrue("expected Success, got $result", result is ExportState.Success)
        val pcm = target.buffer.toByteArray().copyOfRange(WAV_HEADER_BYTES, target.buffer.size())
        assertTrue("PCM must be byte-identical, single-format passthrough", pcm.all { it == fillValue })
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
        // Capacity well above what's written: this test is about the header/commit/filename
        // shape of a plain successful export, not about the saturated-buffer startup headroom
        // (issue #385, covered by its own tests below) -- an unsaturated buffer keeps the byte
        // count below exact and untouched by that margin.
        val ring = ringWithBytes(1000, capacityBytes = 10_000)
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
    fun `a non-null secondsLabel overrides minutesLabel in the filename -- issue #129 sub-minute follow-up`() {
        val target = FakeTarget()
        val sink = FakeSink(target)
        val ring = ringWithBytes(1000)
        val engine = engineFor(ring, sink)

        // minutesLabel = 0 is what RecorderService.handleSave() actually passes for a sub-minute
        // save (resolveSavedMinutes floors to 0); secondsLabel is what must win in the filename --
        // a `_0min.wav` name here would be the exact regression this test pins against.
        val result = engine.export(durationMillis = 1000, minutesLabel = 0, secondsLabel = 45)

        assertTrue(result is ExportState.Success)
        val name = requireNotNull(sink.openedWith)
        assertTrue("expected a _45s suffix, not _0min -- got: $name", name.endsWith("_45s.wav"))
        assertFalse("secondsLabel must fully replace minutesLabel in the name, not just append -- got: $name", name.contains("min"))
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
            // issue #385: this test is about gap backfill, not the saturated-buffer startup
            // headroom -- `{ null }` keeps its pre-#385 behavior exactly (the ring here is built
            // saturated on purpose, for unrelated reasons -- see the comment above).
            capacityBytesProvider = { null },
            exportFloorAdvancerProvider = { null },
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

    // issue #385: this trio replaces the old
    // `export on saturated ring buffer fails loudly if leading edge is lapped during sink open`,
    // which asserted the exact regression this issue fixes. That test was not deleted silently --
    // #351 (`0ce3a28`/`ff26751`) had deliberately removed #204's leading-edge lap recovery because
    // it could return a zero-byte payload / corrupt WAV, and its replacement test asserted the
    // resulting failure as the intended behavior. The fix here does not resurrect that recovery
    // path (no retry/partial-read branch is reintroduced anywhere in the drain): it removes the
    // race at its source by giving the drain a fixed, real headroom before the first byte is ever
    // read, so a small lap during sink/encoder open simply never reaches `readSince` in the first
    // place. A lap big enough to exceed that headroom -- a genuinely different problem, e.g. a
    // stalled encoder -- still has no recovery path and still fails loud
    // (`still fails loudly when the lap exceeds startup headroom` below), so #351's guarantee is
    // unchanged.

    @Test
    fun `export on saturated ring buffer survives a lap within startup headroom during sink open`() {
        val target = FakeTarget()
        val ring = RingBuffer(capacityBytes = 10_000, bytesPerSecond = config.bytesPerSecond)
        // Saturated buffer: buffered bytes == capacity, so oldestCursor is live and can be evicted
        // as new audio arrives -- issue #385's precondition for the race to exist at all.
        ring.write(ByteArray(10_000) { 1 })

        val sink = object : ExportSink {
            override fun open(displayName: String, mimeType: String): ExportTarget {
                // Simulate the capture thread writing into the saturated buffer while the sink is
                // opening: advances oldestCursor by 100 bytes -- well inside ExportEngine's
                // 500-byte (250ms at this config's 2000 B/s) startup headroom. Without the fix,
                // this alone lapped the plan's startCursor and failed the whole Save (this test
                // used to assert exactly that failure -- see the comment above).
                ring.write(ByteArray(100) { 2 })
                return target
            }
        }

        val engine = engineFor(ring, sink)
        val result = engine.export(durationMillis = 10_000, minutesLabel = 1)

        assertTrue(
            "export must survive a lap the startup headroom is sized to absorb, got $result",
            result is ExportState.Success,
        )
        val payloadBytes = target.buffer.toByteArray().size - WavWriter.HEADER_SIZE_BYTES
        assertTrue("must have written real audio, not an empty/corrupt payload", payloadBytes > 0)
    }

    @Test
    fun `export on saturated ring buffer still fails loudly when the lap exceeds startup headroom`() {
        val target = FakeTarget()
        val ring = RingBuffer(capacityBytes = 10_000, bytesPerSecond = config.bytesPerSecond)
        ring.write(ByteArray(10_000) { 1 })

        val sink = object : ExportSink {
            override fun open(displayName: String, mimeType: String): ExportTarget {
                // 600 bytes: more than the 500-byte startup headroom can absorb. This must still
                // fail loud -- #385's headroom only removes the leading-edge race the sink/encoder
                // open causes, it is not a general lap recovery, and #351's guarantee (never a
                // silent zero-byte/corrupt file on a genuine lap) must hold regardless.
                ring.write(ByteArray(600) { 2 })
                return target
            }
        }

        val engine = engineFor(ring, sink)
        val result = engine.export(durationMillis = 10_000, minutesLabel = 1)

        assertTrue("export should still fail loudly when the lap exceeds headroom, got $result", result is ExportState.Error)
        assertEquals(ExportFailureReason.CURSOR_LAPPED, (result as ExportState.Error).reason)
        assertFalse("must never commit a partial/corrupt file", target.committed)
        assertTrue("must abort the pending sink row", target.aborted)
    }

    @Test
    fun `export from an unsaturated buffer discards no audio to startup headroom`() {
        val target = FakeTarget()
        val sink = FakeSink(target)
        // Capacity well above what's written: oldestCursor is pinned at the session start and
        // cannot move during this drain, so there is no race to guard against, and the fix must
        // not discard any audio to a margin that has nothing to protect (issue #385 refinement 1).
        val ring = ringWithBytes(byteCount = 2000, capacityBytes = 100_000)
        val engine = engineFor(ring, sink)

        val result = engine.export(durationMillis = 10_000, minutesLabel = 1)

        assertTrue(result is ExportState.Success)
        val payloadBytes = target.buffer.toByteArray().size - WavWriter.HEADER_SIZE_BYTES
        assertEquals("no margin should be applied to an unsaturated buffer", 2000, payloadBytes)
    }

    /** Same pattern/locale `ExportEngine.filenameFor` uses (its `FILENAME_TIMESTAMP_PATTERN` is
     * private) -- formats a known epoch into the filename's timestamp segment so a test can prove
     * *which* instant the filename was anchored on, at the only resolution the public filename
     * exposes (whole seconds). Standard `SimpleDateFormat` formatting of an independently-chosen
     * constant, not a recomputation of the margin arithmetic under test. */
    private fun filenameTimestamp(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(epochMillis))

    @Test
    fun `filename timestamp is re-anchored by the margin's own duration when estimateTimestampProvider can't resolve the shifted startCursor`() {
        // `@sec` review on PR #386 (issue #385): estimateTimestampProvider(startCursor) returning
        // null (only possible if capture stops concurrently, but must still be handled) used to
        // silently fall back to the pre-margin windowStart -- pointing the filename up to
        // marginBytes' worth of time *before* the first byte this export actually reads. It must
        // instead fall back to windowStart advanced by exactly the margin's own duration.
        val target = FakeTarget()
        val sink = FakeSink(target)
        val ring = RingBuffer(capacityBytes = 10_000, bytesPerSecond = config.bytesPerSecond)
        ring.write(ByteArray(10_000) { 1 }) // saturated: rawLength == capacityBytes, margin applies

        // Chosen so the margin's duration (500 bytes at this config's 2000 B/s = 250ms) crosses a
        // whole-second boundary: base ends in .750, +250ms lands exactly on the next second, so
        // the filename's seconds digit (its only visible resolution) proves which value won.
        val base = 1_700_000_000_750L
        val engine = engineFor(
            ring,
            sink,
            estimateTimestampProvider = { cursor -> if (cursor == ring.oldestCursor()) base else null },
        )

        val result = engine.export(durationMillis = 10_000, minutesLabel = 1)

        assertTrue("expected Success, got $result", result is ExportState.Success)
        val expectedTimestamp = filenameTimestamp(base + 250L)
        val actualName = requireNotNull(sink.openedWith)
        assertTrue(
            "expected filename anchored at base + margin duration ($expectedTimestamp), got $actualName",
            actualName.contains(expectedTimestamp),
        )
    }

    @Test
    fun `filename timestamp is unaffected by the margin fallback when the buffer is unsaturated`() {
        val target = FakeTarget()
        val sink = FakeSink(target)
        // Unsaturated: no margin is applied, so estimateTimestampProvider is only ever consulted
        // once, for oldestCursor -- the shifted-startCursor fallback path is never reached at all.
        val ring = ringWithBytes(byteCount = 2000, capacityBytes = 100_000)
        val base = 1_700_000_000_750L
        val engine = engineFor(
            ring,
            sink,
            estimateTimestampProvider = { cursor -> if (cursor == ring.oldestCursor()) base else null },
        )

        val result = engine.export(durationMillis = 10_000, minutesLabel = 1)

        assertTrue("expected Success, got $result", result is ExportState.Success)
        val expectedTimestamp = filenameTimestamp(base)
        val actualName = requireNotNull(sink.openedWith)
        assertTrue(
            "expected filename anchored at the original base, unchanged ($expectedTimestamp), got $actualName",
            actualName.contains(expectedTimestamp),
        )
    }

    @Test
    fun `export respects minExportDurationMillis before completing`() {
        val target = FakeTarget()
        val sink = FakeSink(target)
        val ring = ringWithBytes(1000)
        val engine = ExportEngine(
            config = config,
            readSinceProvider = { cursor, maxBytes -> ring.readSince(cursor, maxBytes) },
            writeCursorProvider = { ring.writeCursor() },
            oldestCursorProvider = { ring.oldestCursor() },
            // issue #385: this test is about minExportDurationMillis timing, not the
            // saturated-buffer startup headroom -- `{ null }` keeps its pre-#385 behavior exactly.
            capacityBytesProvider = { null },
            exportFloorAdvancerProvider = { null },
            estimateTimestampProvider = { ring.estimateTimestamp(it) },
            gapsProvider = { emptyList() },
            sink = sink,
            payloadEncoder = WavPayloadEncoder,
            minExportDurationMillis = 50L,
        )

        val startTime = System.currentTimeMillis()
        val result = engine.export(durationMillis = 1000, minutesLabel = 1)
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue(result is ExportState.Success)
        assertTrue("Export duration should be at least minExportDurationMillis (50ms), took $elapsed ms", elapsed >= 40L)
    }
}
