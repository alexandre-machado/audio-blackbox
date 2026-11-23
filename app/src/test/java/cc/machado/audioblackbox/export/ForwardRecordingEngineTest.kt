package cc.machado.audioblackbox.export

import android.net.Uri
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.PauseGap
import cc.machado.audioblackbox.audio.ReadSinceResult
import cc.machado.audioblackbox.audio.RingBuffer
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ForwardRecordingEngine] (issue #54).
 *
 * Verifies live drain coordination, state transitions, lapped cursor error visibility, stream reset
 * handling, gap injection, and resource cleanup on error.
 */
class ForwardRecordingEngineTest {

    private class FakeStreamingTarget(
        override val uri: Uri = org.mockito.kotlin.mock(),
        override val fileDescriptor: FileDescriptor = FileDescriptor(),
    ) : StreamingExportTarget {
        val out = ByteArrayOutputStream()
        override val outputStream: OutputStream get() = out
        var finishCount = 0
        var finished = false
        var closed = false
        var refinalizeCallCount = 0
        var deleted = false
        var onClose: () -> Unit = {}

        override fun abort() { close(); deleted = true }

        override fun finish() {
            finishCount++
            finished = true
            close()
        }

        override fun refinalizeMetadata() {
            refinalizeCallCount++
        }

        override fun close() {
            closed = true
            onClose()
        }
    }

    private class FakeStreamingSink : StreamingExportSink {
        val targets = mutableListOf<FakeStreamingTarget>()
        var openCount = 0
        var shouldFail = false
        var lastTarget: FakeStreamingTarget? = null

        override fun openStreaming(displayName: String, mimeType: String): StreamingExportTarget {
            openCount++
            if (shouldFail) throw IOException("Disk full or permission denied")
            return FakeStreamingTarget().also { lastTarget = it; targets.add(it) }
        }
    }

    private class FakeStreamingAudioWriter(
        private val target: StreamingExportTarget,
        val config: AudioConfig,
    ) : StreamingAudioWriter {
        val out = ByteArrayOutputStream()
        var totalBytesFed = 0L
        var isFinished = false
        var isClosed = false
        val gapsInjected = mutableListOf<Long>()
        val gapPositions = mutableListOf<Pair<Int, Long>>()

        override val totalBytesWritten: Long get() = totalBytesFed
        override val isSessionFinished: Boolean get() = isFinished
        override val isSessionClosed: Boolean get() = isClosed

        override fun write(pcmData: ByteArray, offset: Int, length: Int) {
            check(!isClosed) { "Writer is closed" }
            check(!isFinished) { "Writer is finished" }
            out.write(pcmData, offset, length)
            totalBytesFed += length
        }

        override fun writeGap(gapDurationMillis: Long) {
            check(!isClosed) { "Writer is closed" }
            check(!isFinished) { "Writer is finished" }
            gapsInjected.add(gapDurationMillis)
            gapPositions.add(out.size() to gapDurationMillis)
            val silenceBytes = (gapDurationMillis * config.bytesPerSecond) / 1000L
            totalBytesFed += silenceBytes
        }

        override fun finish() {
            isFinished = true
            close()
        }

        override fun close() {
            isClosed = true
        }
    }

    private fun createEngine(
        config: AudioConfig = AudioConfig(sampleRateHz = 16_000, channelCount = 1),
        readSinceProvider: (Long, Int) -> ReadSinceResult? = { cursor, _ -> ReadSinceResult.Data(ByteArray(0), cursor, cursor, 0L) },
        writeCursorProvider: () -> Long? = { 0L },
        oldestCursorProvider: () -> Long? = { 0L },
        gapsProvider: () -> List<PauseGap> = { emptyList() },
        sink: StreamingExportSink = FakeStreamingSink(),
        writerFactory: (StreamingExportTarget, AudioConfig) -> StreamingAudioWriter = { target, cfg -> FakeStreamingAudioWriter(target, cfg) },
    ) = ForwardRecordingEngine(
        exportFloorAdvancerProvider = { null },
        config = config,
        readSinceProvider = readSinceProvider,
        writeCursorProvider = writeCursorProvider,
        oldestCursorProvider = oldestCursorProvider,
        gapsProvider = gapsProvider,
        sink = sink,
        writerFactory = writerFactory,
    )

    @Test
    fun `normal start and stop drains all PCM and transitions to Success`() {
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        val buffer = RingBuffer(capacityBytes = 80_000, bytesPerSecond = config.bytesPerSecond)

        val pcm = ByteArray(3200) { (it % 128).toByte() }
        buffer.write(pcm)

        val sink = FakeStreamingSink()
        var createdWriter: FakeStreamingAudioWriter? = null
        val engine = createEngine(
            config = config,
            readSinceProvider = { cursor, maxBytes -> buffer.readSince(cursor, maxBytes) },
            writeCursorProvider = { buffer.writeCursor() },
            oldestCursorProvider = { buffer.oldestCursor() },
            sink = sink,
            writerFactory = { target, cfg -> FakeStreamingAudioWriter(target, cfg).also { createdWriter = it } },
        )

        val startResult = engine.start()
        assertTrue("start should transition to Recording", startResult is ForwardRecordingState.Recording)

        val pcm2 = ByteArray(1600) { (it % 128).toByte() }
        buffer.write(pcm2)

        val stopResult = engine.stop()
        assertTrue("stop should transition to Success: $stopResult", stopResult is ForwardRecordingState.Success)
        val success = stopResult as ForwardRecordingState.Success
        assertEquals(4800L, success.bytesWritten)

        assertEquals(1, sink.openCount)
        assertTrue("target must be finished", sink.lastTarget?.finished == true)
        assertTrue("target must be closed", sink.lastTarget?.closed == true)
        assertTrue("writer must be finished", createdWriter?.isFinished == true)
        // Regression coverage for issue #140: stop() must re-finalize the MediaStore row's
        // metadata, not just finish the container. Before the fix, nothing ever called
        // target.refinalizeMetadata(), so this would be 0.
        assertTrue(
            "stop() must re-finalize MediaStore metadata at least once, was ${sink.lastTarget?.refinalizeCallCount}",
            (sink.lastTarget?.refinalizeCallCount ?: 0) >= 1,
        )
    }

    @Test
    fun `start always drains the retained past, producing a recording longer than the live-only elapsed audio`() {
        // Issue #139 regression pin: forward recording has exactly one mode now -- it always
        // drains whatever the ring buffer already retains before continuing live. A regression
        // back to forward-only (dropping the oldest-cursor drain silently) would make the
        // resulting file's byte count converge on exactly the live-only bytes below instead of
        // exceeding them.
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        val buffer = RingBuffer(capacityBytes = 80_000, bytesPerSecond = config.bytesPerSecond)

        // Audio already retained in the ring buffer before this forward session ever starts --
        // the "past" a forward-only session would never have captured.
        val pastPcm = ByteArray(6400) { (it % 128).toByte() }
        buffer.write(pastPcm)

        val sink = FakeStreamingSink()
        val engine = createEngine(
            config = config,
            readSinceProvider = { cursor, maxBytes -> buffer.readSince(cursor, maxBytes) },
            writeCursorProvider = { buffer.writeCursor() },
            oldestCursorProvider = { buffer.oldestCursor() },
            sink = sink,
        )

        val startResult = engine.start()
        assertTrue("start should transition to Recording", startResult is ForwardRecordingState.Recording)

        // Audio written live, after start() already returned -- what a forward-only session would
        // have captured on its own.
        val livePcm = ByteArray(3200) { (it % 128).toByte() }
        buffer.write(livePcm)

        val stopResult = engine.stop()
        assertTrue("stop should transition to Success: $stopResult", stopResult is ForwardRecordingState.Success)
        val bytesWritten = (stopResult as ForwardRecordingState.Success).bytesWritten

        assertTrue(
            "recording ($bytesWritten bytes) must be longer than the live-only audio " +
                "(${livePcm.size} bytes) -- forward recording must always include the retained past",
            bytesWritten > livePcm.size,
        )
    }

    @Test
    fun `lapped cursor error transitions to Error with CURSOR_LAPPED and closes target`() {
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        val sink = FakeStreamingSink()
        val readLatch = CountDownLatch(1)

        var readCallCount = 0
        val engine = createEngine(
            config = config,
            readSinceProvider = { cursor, maxBytes ->
                readCallCount++
                if (readCallCount == 1) {
                    ReadSinceResult.Data(ByteArray(100), 0L, 100L, 0L)
                } else {
                    readLatch.countDown()
                    ReadSinceResult.Lapped(requestedCursor = 100L, oldestAvailableCursor = 10_000L, lostBytes = 9_900L)
                }
            },
            writeCursorProvider = { 0L },
            sink = sink,
        )

        val startResult = engine.start()
        assertTrue("should start in Recording", startResult is ForwardRecordingState.Recording)

        assertTrue("readSince should be called and lapped", readLatch.await(5, TimeUnit.SECONDS))

        var state = engine.state.value
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (state !is ForwardRecordingState.Error && System.nanoTime() < deadline) {
            Thread.sleep(10)
            state = engine.state.value
        }

        assertTrue("state must be Error: $state", state is ForwardRecordingState.Error)
        val error = state as ForwardRecordingState.Error
        assertEquals(ForwardRecordingFailureReason.CURSOR_LAPPED, error.reason)
        assertTrue("error message should mention lost bytes", error.message.contains("9900"))
        assertTrue("target must be closed to preserve partial file", sink.lastTarget?.closed == true)
    }

    @Test
    fun `stream reset error transitions to Error with STREAM_RESET`() {
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        val sink = FakeStreamingSink()
        val readLatch = CountDownLatch(1)

        val engine = createEngine(
            config = config,
            readSinceProvider = { cursor, maxBytes ->
                readLatch.countDown()
                ReadSinceResult.StreamReset(requestedCursor = 5000L, currentCursor = 0L)
            },
            writeCursorProvider = { 5000L },
            sink = sink,
        )

        engine.start()
        assertTrue("readSince should be called", readLatch.await(5, TimeUnit.SECONDS))

        var state = engine.state.value
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (state !is ForwardRecordingState.Error && System.nanoTime() < deadline) {
            Thread.sleep(10)
            state = engine.state.value
        }

        assertTrue("state must be Error: $state", state is ForwardRecordingState.Error)
        val error = state as ForwardRecordingState.Error
        assertEquals(ForwardRecordingFailureReason.STREAM_RESET, error.reason)
    }

    @Test
    fun `sink open failure transitions to Error with SINK_OPEN_FAILED`() {
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        val sink = FakeStreamingSink().apply { shouldFail = true }

        val engine = createEngine(
            config = config,
            sink = sink,
        )

        val result = engine.start()
        assertTrue("must return Error on sink failure", result is ForwardRecordingState.Error)
        val error = result as ForwardRecordingState.Error
        assertEquals(ForwardRecordingFailureReason.SINK_OPEN_FAILED, error.reason)
    }

    @Test
    fun `concurrent start returns FORWARD_RECORDING_ALREADY_IN_PROGRESS`() {
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        val sink = FakeStreamingSink()

        val engine = createEngine(
            config = config,
            sink = sink,
        )

        val first = engine.start()
        assertTrue(first is ForwardRecordingState.Recording)

        val second = engine.start()
        assertTrue(second is ForwardRecordingState.Error)
        val error = second as ForwardRecordingState.Error
        assertEquals(ForwardRecordingFailureReason.FORWARD_RECORDING_ALREADY_IN_PROGRESS, error.reason)

        engine.stop()
    }

    @Test
    fun `start when capture inactive returns CAPTURE_NOT_ACTIVE`() {
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        val sink = FakeStreamingSink()

        val engine = createEngine(
            config = config,
            readSinceProvider = { _, _ -> null },
            writeCursorProvider = { null },
            oldestCursorProvider = { null },
            sink = sink,
        )

        val result = engine.start()
        assertTrue(result is ForwardRecordingState.Error)
        val error = result as ForwardRecordingState.Error
        assertEquals(ForwardRecordingFailureReason.CAPTURE_NOT_ACTIVE, error.reason)
    }

    @Test
    fun `interruption pause gaps during live session are injected into writer`() {
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        val buffer = RingBuffer(capacityBytes = 80_000, bytesPerSecond = config.bytesPerSecond)
        val gaps = mutableListOf<PauseGap>()
        val sink = FakeStreamingSink()
        var createdWriter: FakeStreamingAudioWriter? = null

        val engine = createEngine(
            config = config,
            readSinceProvider = { cursor, maxBytes -> buffer.readSince(cursor, maxBytes) },
            writeCursorProvider = { buffer.writeCursor() },
            oldestCursorProvider = { buffer.oldestCursor() },
            gapsProvider = { synchronized(gaps) { gaps.toList() } },
            sink = sink,
            writerFactory = { target, cfg ->
                FakeStreamingAudioWriter(target, cfg).also { createdWriter = it }
            },
        )

        engine.start()

        buffer.write(ByteArray(1600))
        Thread.sleep(50)

        val now = System.currentTimeMillis()
        synchronized(gaps) {
            gaps.add(PauseGap(now, now + 2000L))
        }
        Thread.sleep(50)

        buffer.write(ByteArray(1600))
        Thread.sleep(50)

        engine.stop()

        assertTrue("gap must be injected into writer", createdWriter?.gapsInjected?.contains(2000L) == true)
    }

    @Test
    fun `cancel transitions to CANCELLED and closes target without throwing`() {
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        val sink = FakeStreamingSink()

        val engine = createEngine(
            config = config,
            sink = sink,
        )

        engine.start()
        val cancelResult = engine.cancel()
        assertTrue("cancel must return Error(CANCELLED): $cancelResult", cancelResult is ForwardRecordingState.Error)
        val error = cancelResult as ForwardRecordingState.Error
        assertEquals(ForwardRecordingFailureReason.CANCELLED, error.reason)
        assertTrue("target must be closed", sink.lastTarget?.closed == true)
    }

    @Test
    fun `acknowledgeTerminalState resets state from Success or Error to Idle`() {
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        val sink = FakeStreamingSink()

        val engine = createEngine(
            config = config,
            sink = sink,
        )

        engine.start()
        engine.stop()
        assertTrue("state must be Success", engine.state.value is ForwardRecordingState.Success)

        engine.acknowledgeTerminalState()
        assertTrue("state must reset to Idle", engine.state.value is ForwardRecordingState.Idle)
    }

    @Test
    fun `forward recording on saturated ring buffer recovers from leading edge lap when writer advances during sink open`() {
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        val buffer = RingBuffer(capacityBytes = 10_000, bytesPerSecond = config.bytesPerSecond)

        // Saturated buffer: write 10_000 bytes so buffer is at full capacity (oldestCursor = 0, writeCursor = 10_000)
        buffer.write(ByteArray(10_000) { 1 })

        val sink = object : StreamingExportSink {
            var target: FakeStreamingTarget? = null
            override fun openStreaming(displayName: String, mimeType: String): StreamingExportTarget {
                // Simulate capture thread writing into saturated buffer while sink is opening:
                // Advances oldestCursor by 1280 bytes so initial sampled cursor (0) is lapped!
                buffer.write(ByteArray(1280) { 2 })
                return FakeStreamingTarget().also { target = it }
            }
        }

        var createdWriter: FakeStreamingAudioWriter? = null
        val engine = createEngine(
            config = config,
            readSinceProvider = { cursor, maxBytes -> buffer.readSince(cursor, maxBytes) },
            writeCursorProvider = { buffer.writeCursor() },
            oldestCursorProvider = { buffer.oldestCursor() },
            sink = sink,
            writerFactory = { target, cfg -> FakeStreamingAudioWriter(target, cfg).also { createdWriter = it } },
        )

        val startResult = engine.start()
        assertTrue("start should transition to Recording despite leading edge lap, got $startResult", startResult is ForwardRecordingState.Recording)

        // Write additional audio during live recording
        buffer.write(ByteArray(1600) { 3 })

        val stopResult = engine.stop()
        assertTrue("stop should transition to Success, got $stopResult", stopResult is ForwardRecordingState.Success)
        val success = stopResult as ForwardRecordingState.Success
        // 10_000 (surviving capacity) + 1600 (live written) = 11_600 bytes
        assertEquals(11_600L, success.bytesWritten)
        assertTrue("target must be finished", sink.target?.finished == true)
        assertTrue("writer must be finished", createdWriter?.isFinished == true)
    }
    /** Oracle: real ring bytes must all reach file 1 before commit, then exactly once across
     * both files. Inject bytes at the boundary sample, on the drain thread, so the live loop
     * cannot consume them first and mask a missing checkpoint drain. 4,800 < 80,000 capacity. */
    @Test(timeout = 10_000)
    fun checkpointCommitsBoundaryAndContinuesWithoutGapOrOverlap() {
        val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
        val buffer = RingBuffer(80_000, config.bytesPerSecond)
        val first = ByteArray(3200) { (it * 17).toByte() }
        val second = ByteArray(1600) { (it * 31).toByte() }
        val sink = FakeStreamingSink()
        val writers = mutableListOf<FakeStreamingAudioWriter>()
        var sampled = false
        val engine = ForwardRecordingEngine(
            config = config,
            readSinceProvider = buffer::readSince,
            writeCursorProvider = {
                check(!sampled)
                sampled = true
                buffer.write(first)
                buffer.writeCursor()
            },
            oldestCursorProvider = buffer::oldestCursor,
            exportFloorAdvancerProvider = { { buffer.advanceExportFloor(it); Unit } },
            gapsProvider = { emptyList() },
            sink = sink,
            writerFactory = { target, cfg ->
                FakeStreamingAudioWriter(target, cfg).also { writers.add(it) }
            },
            clock = { 1234567890000L },
        )
        engine.start("first.m4a")
        try {
            val result = engine.checkpoint()
            assertTrue("checkpoint must keep recording: $result", result is ForwardRecordingState.Recording)
            assertEquals(2, sink.openCount)
            assertArrayEquals("checkpoint file must include every byte through boundary", first, writers[0].out.toByteArray())
            assertEquals(1, sink.targets[0].finishCount)
            assertTrue(sink.targets[0].refinalizeCallCount > 0)
            assertFalse(sink.targets[1].finished)
            assertEquals(3200L, buffer.exportFloor())
            val live = result as ForwardRecordingState.Recording
            assertEquals("first.m4a", live.lastCheckpoint?.displayName)
            assertEquals(3200L, live.lastCheckpoint?.bytesWritten)
            assertEquals(ForwardRecordingEngine.generateDisplayName(java.util.Date(1234567890000L)), live.displayName)
            buffer.write(second)
            assertTrue(engine.stop() is ForwardRecordingState.Success)
            assertArrayEquals("rotation must neither drop nor duplicate PCM", first + second,
                writers[0].out.toByteArray() + writers[1].out.toByteArray())
            assertEquals(4800L, buffer.exportFloor())
            assertEquals(listOf(1, 1), sink.targets.map { it.finishCount })
        } finally {
            engine.stop()
        }
    }

    /** Oracle: continuity loss in checkpoint salvages A, accounts its PCM and retains the
     * exact live failure reason; no second file is opened after unrecoverable loss. */
    @Test(timeout = 10_000)
    fun checkpointLappedSalvagesFileAndReason() = checkpointFailure(
        ReadSinceResult.Lapped(8L, 12L, 4L), ForwardRecordingFailureReason.CURSOR_LAPPED)

    /** Oracle: a reset in checkpoint must finish A, never turn into WRITE_FAILED. */
    @Test(timeout = 10_000)
    fun checkpointResetSalvagesFileAndReason() = checkpointFailure(
        ReadSinceResult.StreamReset(8L, 0L), ForwardRecordingFailureReason.STREAM_RESET)

    /** Oracle: capture disappearing at the checkpoint boundary still commits existing PCM. */
    @Test(timeout = 10_000)
    fun checkpointNullCursorSalvagesFileAndReason() = checkpointFailure(
        null, ForwardRecordingFailureReason.CAPTURE_NOT_ACTIVE)

    private fun checkpointFailure(result: ReadSinceResult?, reason: ForwardRecordingFailureReason) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sink = FakeStreamingSink()
        val writers = mutableListOf<FakeStreamingAudioWriter>()
        val floor = java.util.concurrent.atomic.AtomicLong(-1L)
        var checkpointDrain = false
        var firstRead = true
        val engine = ForwardRecordingEngine(
            oldestCursorProvider = { 0L },
            writeCursorProvider = { checkpointDrain = true; if (result == null) null else 16L },
            readSinceProvider = { cursor, _ ->
                if (firstRead) {
                    firstRead = false
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    ReadSinceResult.Data(byteArrayOf(1, 2, 3, 4), 0L, 4L, 0L)
                } else if (checkpointDrain && cursor == 4L && result != null) {
                    ReadSinceResult.Data(byteArrayOf(5, 6, 7, 8), 4L, 8L, 8L)
                } else if (checkpointDrain) result
                else ReadSinceResult.Data(byteArrayOf(), cursor, cursor, 0L)
            },
            exportFloorAdvancerProvider = { { floor.set(it) } },
            gapsProvider = { emptyList() }, sink = sink,
            writerFactory = { target, cfg -> FakeStreamingAudioWriter(target, cfg).also { writers.add(it) } },
        )
        engine.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val completion = requireNotNull(engine.requestCheckpoint())
        release.countDown()
        assertTrue("checkpoint must release its waiter", completion.await(5, TimeUnit.SECONDS))
        assertTrue("saved writer must be finished", writers.single().isFinished)
        assertEquals("saved target must be finished", 1, sink.targets.single().finishCount)
        val savedBytes = if (result == null) 4 else 8
        assertArrayEquals(ByteArray(savedBytes) { (it + 1).toByte() }, writers.single().out.toByteArray())
        assertEquals("only saved bytes advance the floor", savedBytes.toLong(), floor.get())
        assertEquals(reason, (engine.state.value as ForwardRecordingState.Error).reason)
        assertEquals(1, sink.openCount)
    }

    /** Oracle: initial Lapped recovers from oldest surviving PCM even inside a checkpoint,
     * preserving #204/#218 and continuing into B. */
    @Test(timeout = 10_000)
    fun checkpointInitialLappedRecovers() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writers = mutableListOf<FakeStreamingAudioWriter>()
        var checkpointDrain = false
        var firstRead = true
        val engine = ForwardRecordingEngine(
            oldestCursorProvider = { 0L },
            writeCursorProvider = { checkpointDrain = true; 8L },
            readSinceProvider = { cursor, _ ->
                if (firstRead) {
                    firstRead = false; entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    ReadSinceResult.Data(byteArrayOf(), 0L, 0L, 0L)
                } else if (checkpointDrain && cursor == 0L) ReadSinceResult.Lapped(0L, 4L, 4L)
                else if (cursor == 4L) ReadSinceResult.Data(byteArrayOf(5, 6, 7, 8), 4L, 8L, 0L)
                else ReadSinceResult.Data(byteArrayOf(), cursor, cursor, 0L)
            },
            exportFloorAdvancerProvider = { null }, gapsProvider = { emptyList() },
            sink = FakeStreamingSink(),
            writerFactory = { target, cfg -> FakeStreamingAudioWriter(target, cfg).also { writers.add(it) } },
        )
        engine.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val completion = requireNotNull(engine.requestCheckpoint())
        release.countDown()
        assertTrue(completion.await(5, TimeUnit.SECONDS))
        assertTrue(engine.state.value is ForwardRecordingState.Recording)
        engine.stop()
        assertEquals(2, writers.size)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), writers[0].out.toByteArray())
    }

    /** Oracle: an unused B row is deleted on writer construction failure; A stays committed. */
    @Test(timeout = 10_000)
    fun checkpointWriterFailureDeletesOrphan() {
        val sink = FakeStreamingSink()
        val engine = createEngine(sink = sink, writerFactory = { target, cfg ->
            if (sink.openCount == 2) throw IOException("encoder unavailable")
            FakeStreamingAudioWriter(target, cfg)
        })
        engine.start("A.m4a")
        val result = engine.checkpoint() as ForwardRecordingState.Error
        assertEquals(ForwardRecordingFailureReason.SINK_OPEN_FAILED, result.reason)
        assertEquals(1, sink.targets[0].finishCount)
        assertFalse(sink.targets[0].deleted)
        assertTrue("unused B row must be deleted", sink.targets[1].deleted)
        assertTrue(sink.targets[1].closed)
    }

    /** Oracle: a late old-session finally cannot release/drop the new session's pending Save
     * or clear the thread that stop() must join. */
    @Test(timeout = 10_000)
    fun oldDrainCannotClearNewCheckpoint() {
        val oldClosing = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val newReading = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)
        val oldThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val sink = FakeStreamingSink()
        val engine = createEngine(sink = sink,
            readSinceProvider = { cursor, _ ->
                if (sink.openCount == 1) {
                    oldThread.set(Thread.currentThread()); null
                } else {
                    newReading.countDown()
                    check(releaseNew.await(5, TimeUnit.SECONDS))
                    ReadSinceResult.Data(byteArrayOf(), cursor, cursor, 0L)
                }
            },
            writerFactory = { target, cfg ->
                if (sink.openCount == 1) (target as FakeStreamingTarget).onClose = {
                    oldClosing.countDown(); check(releaseOld.await(5, TimeUnit.SECONDS))
                }
                FakeStreamingAudioWriter(target, cfg)
            })
        engine.start("old.m4a")
        assertTrue(oldClosing.await(5, TimeUnit.SECONDS))
        engine.start("new.m4a")
        assertTrue(newReading.await(5, TimeUnit.SECONDS))
        val completion = requireNotNull(engine.requestCheckpoint())
        releaseOld.countDown()
        oldThread.get().join(5000)
        assertFalse("old drain must have exited", oldThread.get().isAlive)
        assertEquals("old finally must not release new checkpoint", 1L, completion.count)
        releaseNew.countDown()
        assertTrue("new checkpoint must still rotate", completion.await(5, TimeUnit.SECONDS))
        assertEquals(3, sink.openCount)
        assertTrue(engine.stop() is ForwardRecordingState.Success)
        assertEquals(1, sink.targets[2].finishCount)
    }

    /** Oracle: stop winning after registration releases the Save waiter and finishes only A. */
    @Test(timeout = 10_000)
    fun registeredCheckpointLosesToStop() = registeredCheckpointLoses(false)

    /** Oracle: cancel winning after registration releases the Save waiter without rotation. */
    @Test(timeout = 10_000)
    fun registeredCheckpointLosesToCancel() = registeredCheckpointLoses(true)

    private fun registeredCheckpointLoses(cancel: Boolean) {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopping = CountDownLatch(1)
        val sink = FakeStreamingSink()
        val engine = ForwardRecordingEngine(
            readSinceProvider = { cursor, _ ->
                reading.countDown(); check(release.await(5, TimeUnit.SECONDS))
                ReadSinceResult.Data(byteArrayOf(), cursor, cursor, 0L)
            }, writeCursorProvider = { 0L },
            exportFloorAdvancerProvider = { null }, gapsProvider = { emptyList() }, sink = sink,
            writerFactory = { target, cfg -> FakeStreamingAudioWriter(target, cfg) },
            onStopRequested = { stopping.countDown() },
        )
        engine.start()
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        val completion = requireNotNull(engine.requestCheckpoint())
        assertTrue("repeated Save taps must coalesce", completion === engine.requestCheckpoint())
        val stopper = Thread { if (cancel) engine.cancel() else engine.stop() }
        stopper.start()
        assertTrue(stopping.await(5, TimeUnit.SECONDS))
        release.countDown()
        assertTrue("registered Save waiter must be released", completion.await(5, TimeUnit.SECONDS))
        stopper.join(5000)
        assertFalse(stopper.isAlive)
        assertEquals(1, sink.openCount)
        assertEquals(if (cancel) 0 else 1, sink.targets.single().finishCount)
        if (cancel) assertEquals(ForwardRecordingFailureReason.CANCELLED,
            (engine.state.value as ForwardRecordingState.Error).reason)
        else assertTrue(engine.state.value is ForwardRecordingState.Success)
    }

    /** Oracle: completed pre-boundary silence lands in A before post-pause drained PCM. */
    @Test(timeout = 10_000)
    fun checkpointGapBeforeBoundary() = checkpointGapPlacement(
        PauseGap(1400L, 1600L), PauseGap(1400L, 1600L), 200L, 0L)

    /** Oracle: post-boundary silence is written only in B, before B's PCM. */
    @Test(timeout = 10_000)
    fun checkpointGapAfterBoundary() = checkpointGapPlacement(
        null, PauseGap(2200L, 2400L), 0L, 200L)

    /** Oracle: an open pause's first 200ms land in A and its remaining 300ms in B;
     * no pre-boundary silence is replayed when the completed gap is published. */
    @Test(timeout = 10_000)
    fun checkpointGapSpanningBoundary() = checkpointGapPlacement(
        PauseGap(1800L, 2000L), PauseGap(1800L, 2300L), 200L, 300L)

    private fun checkpointGapPlacement(atBoundary: PauseGap?, completed: PauseGap, a: Long, b: Long) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val now = java.util.concurrent.atomic.AtomicLong(1000L)
        val gaps = java.util.concurrent.atomic.AtomicReference<List<PauseGap>>(emptyList())
        val sink = FakeStreamingSink()
        val writers = mutableListOf<FakeStreamingAudioWriter>()
        var first = true
        val engine = ForwardRecordingEngine(
            oldestCursorProvider = { 0L },
            writeCursorProvider = { gaps.set(listOfNotNull(atBoundary)); 8L },
            readSinceProvider = { cursor, maxBytes ->
                if (first) {
                    first = false; entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    ReadSinceResult.Data(byteArrayOf(1, 2, 3, 4), 0L, 4L, 0L)
                } else {
                    val end = if (sink.openCount == 1) 8L else 12L
                    val size = minOf(maxBytes.toLong(), end - cursor).toInt()
                    ReadSinceResult.Data(ByteArray(size) { (cursor + it + 1).toByte() }, cursor, cursor + size, 0L)
                }
            },
            exportFloorAdvancerProvider = { null }, sink = sink,
            gapsProvider = { gaps.get().filter { it == completed } },
            checkpointGapsProvider = { gaps.get() },
            clock = now::get,
            writerFactory = { target, cfg ->
                if (sink.openCount == 2) gaps.set(listOf(completed))
                FakeStreamingAudioWriter(target, cfg).also { writers.add(it) }
            },
        )
        engine.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        now.set(2000L)
        val completion = requireNotNull(engine.requestCheckpoint())
        release.countDown()
        assertTrue(completion.await(5, TimeUnit.SECONDS))
        assertTrue(engine.stop() is ForwardRecordingState.Success)
        assertEquals("A silence must precede its checkpoint PCM", if (a == 0L) emptyList() else listOf(4 to a), writers[0].gapPositions)
        assertEquals("B must contain only its own pause portion", if (b == 0L) emptyList() else listOf(0 to b), writers[1].gapPositions)
        assertEquals("total silence must be conserved", a + b, writers.sumOf { it.gapsInjected.sum() })
        assertArrayEquals(ByteArray(8) { (it + 1).toByte() }, writers[0].out.toByteArray())
        assertArrayEquals(byteArrayOf(9, 10, 11, 12), writers[1].out.toByteArray())
    }

    /** Oracle: an idle checkpoint opens no target and preserves the exact state. */
    @Test(timeout = 10_000)
    fun checkpointWhileIdleIsNoOp() {
        val sink = FakeStreamingSink()
        val engine = createEngine(sink = sink)
        assertEquals(ForwardRecordingState.Idle, engine.checkpoint())
        assertEquals(0, sink.openCount)
    }

    /** Oracle: failed rotation cannot uncommit file 1, nor report continued recording. */
    @Test(timeout = 10_000)
    fun checkpointOpenFailureKeepsCommittedFile() {
        val sink = FakeStreamingSink()
        val engine = createEngine(sink = sink)
        engine.start("saved.m4a")
        sink.shouldFail = true
        val result = engine.checkpoint()
        assertTrue(result is ForwardRecordingState.Error)
        result as ForwardRecordingState.Error
        assertEquals(ForwardRecordingFailureReason.SINK_OPEN_FAILED, result.reason)
        assertTrue(result.message.contains("saved.m4a"))
        assertEquals(1, sink.targets.single().finishCount)
        assertTrue(sink.targets.single().closed)
        assertTrue(sink.targets.single().refinalizeCallCount > 0)
    }

    /** Oracle: once stop has entered its final drain, a competing checkpoint is a no-op;
     * neither call deadlocks and the writer/target are finalized exactly once. */
    @Test(timeout = 10_000)
    fun checkpointRacingStopFinalizesOnce() {
        val enteredStop = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val sink = FakeStreamingSink()
        val engine = ForwardRecordingEngine(
            readSinceProvider = { cursor, _ -> ReadSinceResult.Data(byteArrayOf(), cursor, cursor, 0L) },
            writeCursorProvider = { 0L },
            exportFloorAdvancerProvider = { null },
            gapsProvider = { emptyList() },
            sink = sink,
            writerFactory = { target, cfg -> FakeStreamingAudioWriter(target, cfg) },
            onEnteringCleanStopDrain = {
                enteredStop.countDown()
                check(releaseStop.await(5, TimeUnit.SECONDS))
            },
        )
        engine.start()
        val stopThread = Thread { try { engine.stop() } finally { stopped.countDown() } }
        stopThread.start()
        try {
            assertTrue("stop must enter drain", enteredStop.await(5, TimeUnit.SECONDS))
            assertTrue(engine.checkpoint() is ForwardRecordingState.Recording)
        } finally {
            releaseStop.countDown()
        }
        assertTrue("stop must complete without deadlock", stopped.await(5, TimeUnit.SECONDS))
        assertTrue(engine.state.value is ForwardRecordingState.Success)
        assertEquals(1, sink.openCount)
        assertEquals(1, sink.targets.single().finishCount)
        // Oracle: the clean-stop finish and finally paths must not both settle metadata.
        // No PCM is written here, so periodic mid-recording refinalization cannot contribute.
        assertEquals(1, sink.targets.single().refinalizeCallCount)
    }

    /** Oracle: rotating a resampled stream produces exactly the same PCM as one uninterrupted
     * engine run. A completed pause gap is injected once, even when it remains in the provider. */
    @Test(timeout = 10_000)
    fun checkpointPreservesConverterPhaseAndHandledGaps() {
        fun record(rotate: Boolean): Pair<ByteArray, List<Long>> {
            val source = AudioConfig(sampleRateHz = 44_100, channelCount = 1)
            val output = AudioConfig(sampleRateHz = 16_000, channelCount = 1)
            val ring = RingBuffer(capacityBytes = 80_000, initialConfig = source)
            val first = ByteArray(4410) { (it * 13).toByte() }
            val second = ByteArray(4410) { (it * 7).toByte() }
            val writers = mutableListOf<FakeStreamingAudioWriter>()
            val gaps = java.util.concurrent.CopyOnWriteArrayList<PauseGap>()
            ring.write(first)
            val engine = ForwardRecordingEngine(
                config = output,
                readSinceProvider = ring::readSince,
                oldestCursorProvider = ring::oldestCursor,
                writeCursorProvider = ring::writeCursor,
                exportFloorAdvancerProvider = { { ring.advanceExportFloor(it); Unit } },
                gapsProvider = { gaps.toList() },
                segmentsProvider = ring::activeSegments,
                sink = FakeStreamingSink(),
                clock = { 1000L },
                writerFactory = { target, cfg -> FakeStreamingAudioWriter(target, cfg).also { writers.add(it) } },
            )
            engine.start()
            gaps.add(PauseGap(1100L, 1200L))
            try {
                if (rotate) assertTrue(engine.checkpoint() is ForwardRecordingState.Recording)
                ring.write(second)
                assertTrue(engine.stop() is ForwardRecordingState.Success)
                return writers.fold(byteArrayOf()) { bytes, writer -> bytes + writer.out.toByteArray() } to
                    writers.flatMap { it.gapsInjected }
            } finally {
                engine.stop()
            }
        }
        val whole = record(false)
        val rotated = record(true)
        assertTrue("conversion must produce audio", whole.first.isNotEmpty())
        assertArrayEquals("rotation must preserve resampling phase and held boundary frame", whole.first, rotated.first)
        assertEquals(listOf(100L), whole.second)
        assertEquals(whole.second, rotated.second)
    }
}
