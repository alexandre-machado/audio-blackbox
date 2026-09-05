package cc.machado.audioblackbox.export

import android.net.Uri
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.FormatSegment
import cc.machado.audioblackbox.audio.GoertzelDetector
import cc.machado.audioblackbox.audio.ReadSinceResult
import cc.machado.audioblackbox.audio.RingBuffer
import cc.machado.audioblackbox.audio.ToneGenerator
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression tests for the forward-recording half of issue #322.
 *
 * Issue #194 introduced per-format segments and [PcmAudioConverter] but scoped the conversion to
 * the retro-export path: [PcmAudioConverter]'s only production caller was [BoundedExportReader].
 * [ForwardRecordingEngine] sampled the capture config once at session start, configured its
 * [StreamingAudioWriter] (and therefore the AAC `MediaFormat`) with it, and then fed raw
 * ring-buffer bytes straight through with no format check -- so any byte range recorded in a
 * different format went into the file unconverted, under a declaration that did not describe it.
 *
 * ## This needed no mid-session preset change to happen
 * The issue body assumed the trigger was a preset switch *during* a forward recording, and left
 * open whether that is even reachable from the UI. It is (nothing in `SettingsViewModel` or the
 * Settings route consults `forwardRecordingState`), but that turned out not to be the interesting
 * part: a forward recording always begins at `oldestCursor` and drains the retained past first
 * (issue #139), so it reads across whatever format boundaries the ring buffer *already* holds.
 * Switching preset and then starting a forward recording is enough, and that is the sequence
 * exercised below.
 *
 * Fixed by converting each drained chunk into the session's declared format, driven from the ring
 * buffer's own segment record -- the analogue of the export path, keeping one format per file
 * rather than re-configuring an open encoder mid-file.
 */
class ForwardRecordingFormatBoundaryTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val voice = AudioConfig(sampleRateHz = 16_000, channelCount = 1, bufferDurationMinutes = 1)
    private val hiFi = AudioConfig(sampleRateHz = 44_100, channelCount = 2, bufferDurationMinutes = 1)

    @Test
    fun `a forward recording across a format boundary writes only PCM in the format it declares`() {
        // 1.0s recorded at VOICE, then a preset switch, then 1.0s at HIGH_FIDELITY -- the same
        // 400Hz tone throughout, so the two halves are only distinguishable by whether their
        // format was honoured.
        val ring = RingBuffer(capacityBytes = 700_000, initialConfig = voice)
        ring.write(ToneGenerator.tone(TONE_HZ, 16_000, 1_000, channelCount = 1))
        ring.setFormat(hiFi)
        ring.write(ToneGenerator.tone(TONE_HZ, 44_100, 1_000, channelCount = 2))

        var writer: CapturingWriter? = null
        val engine = ForwardRecordingEngine(
            config = hiFi,
            configProvider = { hiFi },
            readSinceProvider = { cursor, maxBytes -> ring.readSince(cursor, maxBytes) },
            writeCursorProvider = { ring.writeCursor() },
            oldestCursorProvider = { ring.oldestCursor() },
            gapsProvider = { emptyList() },
            sink = FakeSink(),
            writerFactory = { _, cfg -> CapturingWriter(cfg).also { writer = it } },
            segmentsProvider = { ring.activeSegments() },
        )

        assertTrue(engine.start("probe.m4a") is ForwardRecordingState.Recording)
        val result = engine.stop()
        assertTrue("expected Success, got $result", result is ForwardRecordingState.Success)

        val declared = writer!!.config
        val pcm = writer!!.out.toByteArray()
        assertEquals("the session must declare the newest preset", 44_100 to 2, declared.sampleRateHz to declared.channelCount)

        // 2.0s of real audio, all of it in the declared format: 1.0s converted up from 16kHz mono
        // plus 1.0s already native. Tolerance is one target frame -- the resampler's fractional
        // phase remainder at the boundary. The retro export path lands the same one frame short of
        // its own plan (529 196 of 529 200). Unconverted chunks miss by far more than a frame:
        // before the fix the writer was fed 208 400 bytes instead of 352 800.
        val expected = 2L * hiFi.bytesPerSecond
        assertTrue(
            "writer was fed ${pcm.size} bytes, expected ~$expected -- everything the writer is " +
                "fed must already be in the format the writer declares",
            abs(pcm.size.toLong() - expected) <= hiFi.bytesPerFrame,
        )

        // Content oracle over exactly the byte range the mislabel used to occupy: 32 000 bytes is
        // 1.0s of 16kHz mono but only 181ms of 44.1kHz stereo. Read at the declared 44.1kHz that
        // range is a clean 400Hz tone once converted, and a 1102.5Hz (= 400 * 44100/16000) one if
        // the raw mono bytes went in untouched.
        val head = pcm.copyOfRange(0, minOf(MISLABEL_RANGE_BYTES, pcm.size))
        val onTarget = GoertzelDetector.energyAt(head, TONE_HZ, 44_100, 2)
        val offTarget = GoertzelDetector.energyAt(head, TONE_HZ * 44_100 / 16_000, 44_100, 2)
        assertTrue(
            "the retained past must still read as ${TONE_HZ}Hz at the declared rate, not 2.76x " +
                "fast (on-target $onTarget vs off-target $offTarget)",
            onTarget > offTarget * 100,
        )
    }

    @Test
    fun `a single-format forward recording is still fed byte-identical PCM`() {
        // The identity path must stay a pass-through: no conversion, no re-copying, no drift in
        // the byte count every existing ForwardRecordingEngine test asserts on.
        val ring = RingBuffer(capacityBytes = 200_000, initialConfig = voice)
        val tone = ToneGenerator.tone(TONE_HZ, 16_000, 1_000, channelCount = 1)
        ring.write(tone)

        var writer: CapturingWriter? = null
        val engine = ForwardRecordingEngine(
            config = voice,
            configProvider = { voice },
            readSinceProvider = { cursor, maxBytes -> ring.readSince(cursor, maxBytes) },
            writeCursorProvider = { ring.writeCursor() },
            oldestCursorProvider = { ring.oldestCursor() },
            gapsProvider = { emptyList() },
            sink = FakeSink(),
            writerFactory = { _, cfg -> CapturingWriter(cfg).also { writer = it } },
            segmentsProvider = { ring.activeSegments() },
        )

        assertTrue(engine.start("probe.m4a") is ForwardRecordingState.Recording)
        assertTrue(engine.stop() is ForwardRecordingState.Success)

        assertTrue(
            "a single-format session must reach the writer byte-identical, not round-tripped " +
                "through a converter",
            tone.contentEquals(writer!!.out.toByteArray()),
        )
    }

    @Test
    fun `readSince never returns a chunk spanning two formats`() {
        // [ForwardFormatReconciler] resolves one source format per chunk and converts the chunk as
        // a unit, so its correctness rests entirely on this guarantee from RingBuffer.readSince
        // (issue #194). An earlier draft of the fix re-clamped read sizes on the caller's side to
        // enforce it a second time; mutation testing showed that clamp could be deleted with no
        // test failing anywhere, because this guarantee means it can never bind. The duplicate was
        // removed and the guarantee it leaned on is pinned here instead -- so if it ever regresses,
        // this fails rather than the forward path silently misconverting a boundary chunk.
        val ring = RingBuffer(capacityBytes = 200_000, initialConfig = voice)
        ring.write(ByteArray(BOUNDARY_BYTES) { 1 })
        ring.setFormat(hiFi)
        ring.write(ByteArray(BOUNDARY_BYTES) { 2 })

        // A chunk size that divides neither the boundary offset nor the total, so a naive read
        // would straddle: 32 000 / 4 096 = 7.8125.
        var cursor = 0L
        var sawBoundaryChunk = false
        while (true) {
            val result = ring.readSince(cursor, 4_096)
            check(result is ReadSinceResult.Data) { "unexpected $result" }
            if (result.bytes.isEmpty()) break
            val distinctValues = result.bytes.toSet()
            assertEquals(
                "chunk at $cursor (${result.bytes.size} bytes) mixes audio from both formats: $distinctValues",
                1,
                distinctValues.size,
            )
            if (cursor + result.bytes.size == BOUNDARY_BYTES.toLong()) sawBoundaryChunk = true
            cursor = result.nextCursor
        }
        assertTrue(
            "the drain must have been made to stop exactly on the boundary, or this test proved nothing",
            sawBoundaryChunk,
        )
        assertEquals(2L * BOUNDARY_BYTES, cursor)
    }

    @Test
    fun `an unresolvable source format fails the recording instead of guessing one`() {
        // `@rev` finding 4 on PR #323. [ForwardFormatReconciler] resolves a chunk's source format
        // from the segment list *after* readSince has already returned the bytes, so a lap in
        // between leaves a chunk whose describing segment has been pruned. The first draft ended
        // `sourceAt` with `?: segs.first().config` -- it converted the chunk from the *oldest
        // surviving* format instead. That is a guess, and a wrong guess writes real audio
        // converted from the wrong source rate into a file that declares the target correctly:
        // quietly wrong audio, no error anywhere. Exactly the failure class issue #322 exists to
        // close, reintroduced one file over.
        //
        // `@rev` proposed resolving via RingBuffer.formatAt instead, as window-independent.
        // Measured, that is a no-op: oldestCursor() *is* oldestAvailableLocked(), so a segment
        // leaves activeSegments' window and is pruned at the same instant, and formatAt carries
        // the identical `?: segments.first().config` fallback. Both strategies agree at every lap
        // depth -- right while the covering segment survives, wrong once it does not. The API
        // choice was never the defect; the silent fallback was, so the fallback is gone.
        //
        // The interleaving is a race in production (the lap has to land between the read and the
        // resolve), so it is staged here rather than waited for: readSince serves the pre-lap
        // bytes while segmentsProvider reports the post-lap view. Racing it for real would mean a
        // timing-dependent test, which AGENTS.md 3 forbids and which this PR has already been
        // bitten by once (see CaptureFormatLabelTest's M4b).
        val ring = RingBuffer(capacityBytes = 200_000, initialConfig = voice)
        ring.write(ToneGenerator.tone(TONE_HZ, 16_000, 1_000, channelCount = 1))

        val firstRead = CountDownLatch(1)
        var writer: CapturingWriter? = null
        val engine = ForwardRecordingEngine(
            config = hiFi,
            configProvider = { hiFi },
            readSinceProvider = { cursor, maxBytes ->
                ring.readSince(cursor, maxBytes).also { firstRead.countDown() }
            },
            writeCursorProvider = { ring.writeCursor() },
            oldestCursorProvider = { ring.oldestCursor() },
            gapsProvider = { emptyList() },
            sink = FakeSink(),
            writerFactory = { _, cfg -> CapturingWriter(cfg).also { writer = it } },
            // The post-lap view: every surviving segment starts past the bytes being drained, so
            // nothing covers the cursor. Non-empty, so this is "the record exists but no longer
            // describes these bytes", not "there is no record" -- the legacy no-segments
            // constructor must keep passing bytes straight through, and does (test above).
            segmentsProvider = { listOf(FormatSegment(startOffset = LAPPED_PAST_BYTES, config = hiFi)) },
        )

        assertTrue(engine.start("probe.m4a") is ForwardRecordingState.Recording)
        // Synchronise on an observable event, never on elapsed time: the drain thread has entered
        // the live loop and taken a chunk. Without this the test can reach stop() first, which
        // short-circuits `while (!stopRequested)` and exercises the final-drain path instead --
        // a different, deliberately different, branch (covered by the next test).
        assertTrue("drain thread never read a chunk", firstRead.await(5, TimeUnit.SECONDS))
        // stop() joins the drain thread, so this is a real synchronisation point, not a wait.
        val finalState = engine.stop()

        assertTrue(
            "an unresolvable source format must surface as an error, not a guessed conversion -- got $finalState",
            finalState is ForwardRecordingState.Error,
        )
        assertEquals(
            ForwardRecordingFailureReason.CURSOR_LAPPED,
            (finalState as ForwardRecordingState.Error).reason,
        )
        // The point of the fix: nothing converted from a guessed format reached the file. Before
        // it, the writer was fed the whole second of 16kHz mono resampled as though it had been
        // recorded at 44.1kHz stereo.
        assertEquals(
            "no audio may be written once its recorded format is unknown",
            0,
            writer!!.out.size(),
        )
    }

    @Test
    fun `an unresolvable source format on the clean-stop path ends the file instead of failing it`() {
        // The asymmetry is deliberate. On the live path (test above) the recording is still in
        // flight and the honest outcome is an error. On the clean-stop path a complete,
        // correctly-labelled file has already been written and only the tail is unresolvable --
        // and this same loop already treats `Lapped` that way, via its `else` branch. Turning a
        // finished recording into an Error over a lost tail would destroy more than it protects.
        // What must not happen on either path, and no longer can, is writing that tail converted
        // from a guessed format.
        val ring = RingBuffer(capacityBytes = 200_000, initialConfig = voice)
        ring.write(ToneGenerator.tone(TONE_HZ, 16_000, 1_000, channelCount = 1))

        var writer: CapturingWriter? = null
        val engine = ForwardRecordingEngine(
            config = hiFi,
            configProvider = { hiFi },
            readSinceProvider = { cursor, maxBytes -> ring.readSince(cursor, maxBytes) },
            writeCursorProvider = { ring.writeCursor() },
            oldestCursorProvider = { ring.oldestCursor() },
            gapsProvider = { emptyList() },
            sink = FakeSink(),
            writerFactory = { _, cfg -> CapturingWriter(cfg).also { writer = it } },
            segmentsProvider = { listOf(FormatSegment(startOffset = LAPPED_PAST_BYTES, config = hiFi)) },
        )

        assertTrue(engine.start("probe.m4a") is ForwardRecordingState.Recording)
        val finalState = engine.stop()

        // `@rev` finding 1 on PR #323: an earlier version of this test asserted `Success`, which
        // made it a coin flip. Which branch runs depends on whether stop() beats the drain thread
        // to its first `while (!stopRequested)` check, and the engine offers no seam to decide
        // that -- the check is the first thing the thread executes, and gating it from stop() would
        // deadlock on stop()'s own join(). Rather than ship a test that fails ~sometimes with a
        // message reading like a product regression, this asserts the property that must hold on
        // *both* branches. The stop-path-specific outcome (Success rather than Error) is therefore
        // deliberately not pinned by a test; it is pinned by the code comment and by review.
        assertTrue(
            "an unresolvable format must reach a terminal state, not hang -- got $finalState",
            finalState is ForwardRecordingState.Success || finalState is ForwardRecordingState.Error,
        )
        assertEquals(
            "no audio may be written once its recorded format is unknown, on either drain path",
            0,
            writer!!.out.size(),
        )
    }

    @Test
    fun `a tail dropped on clean stop is durably logged as TAIL_TRUNCATED`() {
        // Issue #332 finding 3 (`@rev`/`@sec` on PR #323's last commit): the TAIL_TRUNCATED log is
        // the only record of a silently-shortened tail, and nothing guarded it. This deterministically
        // forces the *clean-stop* branch (as opposed to the live-loop branch, which errors instead of
        // logging -- see the test above) by draining exactly one small, resolvable chunk live, then
        // -- once the drain thread is parked waiting for more data -- swapping the segment record out
        // from under it and appending a second chunk, so the clean-stop drain finds unresolvable
        // bytes to log rather than racing for it.
        //
        // `@rev` round-2 finding 1 on PR #333: an earlier version of this test synchronized only on
        // the first chunk reaching the writer, then raced the segment swap / second write / stop()
        // against the live loop's own `wakeUpLatch.await(POLL_INTERVAL_MILLIS)` idle poll with no
        // synchronization between them -- if that 50ms poll timed out on its own first, the live
        // loop (not the clean-stop drain) would consume the second chunk and the test would assert
        // over an `Error(CURSOR_LAPPED)` instead of a `Success`. A hook fired inside `stop()` itself
        // turned out not to be enough either (caught in this test's own revision history): the
        // clean-stop drain's first read is un-retried, so blocking only `stop()`'s `join()` still let
        // the drain thread's clean-stop loop run its one read attempt -- and find nothing -- before
        // the test thread got a chance to write the second chunk. `onEnteringCleanStopDrain` closes
        // both windows at once: it fires on the drain thread exactly between the live loop's exit and
        // the clean-stop loop's first read, so blocking there until the segment swap and second write
        // are done makes it impossible -- not just unlikely -- for either loop to run before the
        // right one can see it.
        //
        // Mutation check: commenting out the `logExportError(...)` call at the TAIL_TRUNCATED site
        // makes this test fail (the log file stays empty); reverted after confirming.
        val ring = RingBuffer(capacityBytes = 200_000, initialConfig = voice)
        val firstChunk = ToneGenerator.tone(TONE_HZ, 16_000, 50, channelCount = 1)
        ring.write(firstChunk)

        val segments = java.util.concurrent.atomic.AtomicReference<List<FormatSegment>?>(
            listOf(FormatSegment(startOffset = 0L, config = voice))
        )
        // Signalled only once the first chunk has actually reached the writer -- i.e. after
        // `reconcile` has already resolved it against the *original* segment list -- not merely
        // after `readSince` returned bytes, which races the segment swap below against
        // `reconcile`'s own read of `segmentsProvider` (see this test's mutation history).
        val firstWrite = CountDownLatch(1)
        // Signalled from the drain thread the instant it has left the live loop for good and has
        // not yet made the clean-stop drain's first `readSinceProvider` call.
        val enteringCleanStop = CountDownLatch(1)
        // Released by the test thread once the segment swap and second write are done, so the
        // clean-stop drain's first read does not happen until after both have.
        val secondChunkReady = CountDownLatch(1)
        var writer: CapturingWriter? = null
        val errorLogFile = tempDir.newFile("export_errors.log")
        val engine = ForwardRecordingEngine(
            config = voice,
            configProvider = { voice },
            readSinceProvider = { cursor, maxBytes -> ring.readSince(cursor, maxBytes) },
            writeCursorProvider = { ring.writeCursor() },
            oldestCursorProvider = { ring.oldestCursor() },
            gapsProvider = { emptyList() },
            sink = FakeSink(),
            writerFactory = { _, cfg -> CapturingWriter(cfg) { firstWrite.countDown() }.also { writer = it } },
            segmentsProvider = { segments.get() },
            errorLogFile = errorLogFile,
            onEnteringCleanStopDrain = {
                enteringCleanStop.countDown()
                assertTrue(
                    "test thread never finished the segment swap / second write",
                    secondChunkReady.await(5, TimeUnit.SECONDS),
                )
            },
        )

        assertTrue(engine.start("probe.m4a") is ForwardRecordingState.Recording)
        assertTrue("drain thread never wrote the first chunk", firstWrite.await(5, TimeUnit.SECONDS))

        var finalState: ForwardRecordingState? = null
        val stopThread = Thread({ finalState = engine.stop() }, "test-stop-caller")
        stopThread.start()
        assertTrue(
            "drain thread never reached the clean-stop drain entry point",
            enteringCleanStop.await(5, TimeUnit.SECONDS),
        )

        // Now provably safe: the live loop has already exited for good (it cannot come back -- the
        // hook above only fires once, after that exit), and the clean-stop loop is blocked before
        // its first read. Simulate the segment describing new bytes being pruned out from under the
        // drain, and give it a second chunk that only the clean-stop path can now ever see.
        segments.set(listOf(FormatSegment(startOffset = LAPPED_PAST_BYTES, config = hiFi)))
        ring.write(ToneGenerator.tone(TONE_HZ, 16_000, 50, channelCount = 1))
        secondChunkReady.countDown()
        stopThread.join(TimeUnit.SECONDS.toMillis(5))
        assertTrue("stop() never returned", finalState != null)

        assertTrue(
            "the clean-stop path must still end in Success, tail dropped but file complete -- got $finalState",
            finalState is ForwardRecordingState.Success,
        )
        assertEquals(
            "only the first, resolvable chunk may reach the file",
            firstChunk.size,
            writer!!.out.size(),
        )

        flushErrorLogsForTest()
        val logContent = errorLogFile.readText()
        assertTrue(
            "a silently dropped tail must be durably logged as TAIL_TRUNCATED, got: $logContent",
            logContent.contains("TAIL_TRUNCATED"),
        )
    }

    @Test
    fun `a null segment report is unresolvable, not a licence to pass bytes through`() {
        // `@rev` finding 2 on PR #323. AudioCaptureEngine.activeSegments() returns *null* when its
        // ring buffer is gone -- a rebuild between two drain iterations, say. The call sites used
        // to write `{ engine.activeSegments() ?: emptyList() }`, laundering that null into an empty
        // list and therefore into "no record at all, single-format, pass through unconverted".
        // That is the guess this whole fix removes, reopened for precisely the case where the
        // engine knows least about what it holds. A provider that returns null now means
        // Unresolvable; only the absence of a provider means NoRecord.
        val ring = RingBuffer(capacityBytes = 200_000, initialConfig = voice)
        ring.write(ToneGenerator.tone(TONE_HZ, 16_000, 1_000, channelCount = 1))

        val firstRead = CountDownLatch(1)
        var writer: CapturingWriter? = null
        val engine = ForwardRecordingEngine(
            config = hiFi,
            configProvider = { hiFi },
            readSinceProvider = { cursor, maxBytes ->
                ring.readSince(cursor, maxBytes).also { firstRead.countDown() }
            },
            writeCursorProvider = { ring.writeCursor() },
            oldestCursorProvider = { ring.oldestCursor() },
            gapsProvider = { emptyList() },
            sink = FakeSink(),
            writerFactory = { _, cfg -> CapturingWriter(cfg).also { writer = it } },
            // Exactly what `engine.activeSegments()` yields once the ring buffer is gone.
            segmentsProvider = { null },
        )

        assertTrue(engine.start("probe.m4a") is ForwardRecordingState.Recording)
        assertTrue("drain thread never read a chunk", firstRead.await(5, TimeUnit.SECONDS))
        val finalState = engine.stop()

        assertEquals(
            "no audio may be written when the engine cannot say what format it holds",
            0,
            writer!!.out.size(),
        )
        assertTrue(
            "a null segment report must not be treated as a single-format pass-through -- got $finalState",
            finalState is ForwardRecordingState.Success || finalState is ForwardRecordingState.Error,
        )
    }

    private class FakeTarget : StreamingExportTarget {
        override val uri: Uri = org.mockito.kotlin.mock()
        override val fileDescriptor: FileDescriptor = FileDescriptor()
        private val discarded = ByteArrayOutputStream()
        override val outputStream: OutputStream get() = discarded
        override fun finish() = Unit
        override fun refinalizeMetadata() = Unit
        override fun close() = Unit
    }

    private class FakeSink : StreamingExportSink {
        override fun openStreaming(displayName: String, mimeType: String): StreamingExportTarget = FakeTarget()
    }

    /** Captures exactly the PCM the engine feeds a writer, alongside the format that writer was
     * configured with -- the two halves of the format-vs-content relationship under test. */
    private class CapturingWriter(
        val config: AudioConfig,
        private val onWrite: () -> Unit = {},
    ) : StreamingAudioWriter {
        val out = ByteArrayOutputStream()
        private var finished = false
        private var closed = false
        override val totalBytesWritten: Long get() = out.size().toLong()
        override val isSessionFinished: Boolean get() = finished
        override val isSessionClosed: Boolean get() = closed
        override fun write(pcmData: ByteArray, offset: Int, length: Int) {
            out.write(pcmData, offset, length)
            onWrite()
        }
        override fun writeGap(gapDurationMillis: Long) = Unit
        override fun finish() { finished = true }
        override fun close() { closed = true }
    }

    private companion object {
        const val TONE_HZ = 400.0
        const val MISLABEL_RANGE_BYTES = 32_000
        const val BOUNDARY_BYTES = 32_000

        /** Segment start far past anything the drain reads, so no segment covers the cursor. */
        const val LAPPED_PAST_BYTES = 1_000_000L
    }
}
