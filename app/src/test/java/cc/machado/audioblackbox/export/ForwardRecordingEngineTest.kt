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
        var finished = false
        var closed = false
        var refinalizeCallCount = 0

        override fun finish() {
            finished = true
            close()
        }

        override fun refinalizeMetadata() {
            refinalizeCallCount++
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeStreamingSink : StreamingExportSink {
        var openCount = 0
        var shouldFail = false
        var lastTarget: FakeStreamingTarget? = null

        override fun openStreaming(displayName: String, mimeType: String): StreamingExportTarget {
            openCount++
            if (shouldFail) throw IOException("Disk full or permission denied")
            return FakeStreamingTarget().also { lastTarget = it }
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
}
