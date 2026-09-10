package cc.machado.audioblackbox.export

import android.media.AudioRecord
import android.net.Uri
import cc.machado.audioblackbox.audio.AudioCaptureEngine
import cc.machado.audioblackbox.audio.AudioConfig
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Regression coverage for issue #374: a pause gap that started before a forward-recording
 * session but ended after it must only contribute its *in-session* portion to the file.
 *
 * Deliberately does not hand-assemble a [cc.machado.audioblackbox.audio.PauseGap] with
 * timestamps chosen to match the fix's own arithmetic -- that would be a fixture asserted
 * against itself, which stays green even if the real producer of gaps changes shape. Instead
 * this drives a real [AudioCaptureEngine] (the actual `pause()`/`resume()` state machine
 * production wiring uses, per the [ForwardRecordingEngine.AudioCaptureEngine] constructor
 * overload) with a controllable fake clock, so the [cc.machado.audioblackbox.audio.PauseGap]
 * fed to [ForwardRecordingEngine] is the real thing `AudioCaptureEngine.resume()` produces, and
 * the assertion is on what the (real-contract) writer actually received.
 *
 * Three cases, matching the issue's acceptance criteria:
 *  - a gap entirely before the session start: excluded entirely (unchanged by this fix);
 *  - a gap entirely inside the session: written in full (unchanged by this fix);
 *  - a gap straddling the session start: only its in-session portion is written (the fix).
 */
class ForwardRecordingStraddlingGapTest {

    private val config = AudioConfig(sampleRateHz = 16_000, channelCount = 1, bufferDurationMinutes = 1)

    private class FakeStreamingTarget(
        override val uri: Uri = mock(),
        override val fileDescriptor: FileDescriptor = FileDescriptor(),
    ) : StreamingExportTarget {
        val out = ByteArrayOutputStream()
        override val outputStream: OutputStream get() = out
        override fun finish() {}
        override fun refinalizeMetadata() {}
        override fun close() {}
    }

    private class FakeStreamingSink : StreamingExportSink {
        override fun openStreaming(displayName: String, mimeType: String): StreamingExportTarget = FakeStreamingTarget()
    }

    /** Mirrors [StreamingAacWriter]'s own real-writer contract for [writeGap]: silence bytes are
     * derived from the gap duration at `config.bytesPerSecond`, exactly the arithmetic the
     * production `MediaCodec`-backed writer uses (that writer itself is instrumented-tier only --
     * see `docs/testing/tiers.md` -- so this is the same double every other Tier 0 test for this
     * class uses; what is real here, and what this test exists to exercise, is the *gap* fed to
     * it, produced by an actual `AudioCaptureEngine` pause/resume cycle rather than hand-picked
     * timestamps). */
    private class FakeStreamingAudioWriter(
        private val config: AudioConfig,
    ) : StreamingAudioWriter {
        var totalBytesFed = 0L
        var isFinished = false
        var isClosed = false
        val gapsInjectedMillis = mutableListOf<Long>()

        override val totalBytesWritten: Long get() = totalBytesFed
        override val isSessionFinished: Boolean get() = isFinished
        override val isSessionClosed: Boolean get() = isClosed

        override fun write(pcmData: ByteArray, offset: Int, length: Int) {
            totalBytesFed += length
        }

        override fun writeGap(gapDurationMillis: Long) {
            gapsInjectedMillis.add(gapDurationMillis)
            totalBytesFed += (gapDurationMillis * config.bytesPerSecond) / 1000L
        }

        override fun finish() {
            isFinished = true
        }

        override fun close() {
            isClosed = true
        }
    }

    private fun fakeAudioRecord(): AudioRecord {
        val record = mock<AudioRecord>()
        whenever(record.state).thenReturn(AudioRecord.STATE_INITIALIZED)
        whenever(record.recordingState).thenReturn(AudioRecord.RECORDSTATE_RECORDING)
        return record
    }

    /** Same seam [cc.machado.audioblackbox.audio.AudioCaptureEngineTest] uses: `getMinBufferSize`
     * is static, so it needs a thread-local Mockito static mock rather than a constructor seam. */
    private fun <T> withMinBufferSizeMocked(value: Int = 4096, body: () -> T): T {
        val staticMock = Mockito.mockStatic(AudioRecord::class.java)
        staticMock.`when`<Int> {
            AudioRecord.getMinBufferSize(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
        }.thenReturn(value)
        try {
            return body()
        } finally {
            staticMock.close()
        }
    }

    /** Sets up a real, running [AudioCaptureEngine] on a fake, caller-controlled clock and a
     * mocked (never-reads-real-audio) `AudioRecord`, plus a [ForwardRecordingEngine] wired to it
     * through the production [ForwardRecordingEngine.AudioCaptureEngine] constructor -- the same
     * collaboration `RecorderService` uses, just with a fake sink/writer standing in for
     * `MediaStore`/`MediaCodec` (see this class's own doc for why those two are the only fakes
     * here). */
    private class Harness(
        val clockMillis: AtomicLong,
        val captureEngine: AudioCaptureEngine,
        val forwardEngine: ForwardRecordingEngine,
    ) {
        // Assigned by writerFactory only once ForwardRecordingEngine.start() actually opens a
        // session, so this is read only after start() below -- never at harness construction.
        lateinit var writer: FakeStreamingAudioWriter
    }

    private fun harness(config: AudioConfig, startAtMillis: Long): Harness {
        val clockMillis = AtomicLong(startAtMillis)
        val clock = { clockMillis.get() }
        val captureEngine = AudioCaptureEngine(
            config = config,
            clock = clock,
            audioRecordFactory = { _, _ -> fakeAudioRecord() },
        )
        captureEngine.start()

        lateinit var h: Harness
        val forwardEngine = ForwardRecordingEngine(
            engine = captureEngine,
            sink = FakeStreamingSink(),
            config = config,
            writerFactory = { _, cfg -> FakeStreamingAudioWriter(cfg).also { h.writer = it } },
            clock = clock,
        )
        h = Harness(clockMillis, captureEngine, forwardEngine)
        return h
    }

    @Test
    fun `gap entirely before session start is excluded`() = withMinBufferSizeMocked {
        val h = harness(config, startAtMillis = 0L)
        try {
            // Real pause/resume cycle, fully before the forward session starts: [100, 900) -> 800ms.
            h.clockMillis.set(100L)
            h.captureEngine.pause()
            h.clockMillis.set(900L)
            h.captureEngine.resume()

            h.clockMillis.set(1_000L)
            val startResult = h.forwardEngine.start()
            assertTrue("start should transition to Recording: $startResult", startResult is ForwardRecordingState.Recording)

            h.clockMillis.set(1_100L)
            val stopResult = h.forwardEngine.stop()
            assertTrue("stop should transition to Success: $stopResult", stopResult is ForwardRecordingState.Success)

            assertEquals(
                "a gap that ended before the session started must not be written at all",
                emptyList<Long>(),
                h.writer.gapsInjectedMillis,
            )
        } finally {
            h.captureEngine.stop()
        }
    }

    @Test
    fun `gap entirely inside session is written whole`() = withMinBufferSizeMocked {
        val h = harness(config, startAtMillis = 0L)
        try {
            h.clockMillis.set(1_000L)
            val startResult = h.forwardEngine.start()
            assertTrue("start should transition to Recording: $startResult", startResult is ForwardRecordingState.Recording)

            // Real pause/resume cycle, fully inside the session: [1_600, 2_100) -> 500ms.
            h.clockMillis.set(1_600L)
            h.captureEngine.pause()
            h.clockMillis.set(2_100L)
            h.captureEngine.resume()

            h.clockMillis.set(2_200L)
            val stopResult = h.forwardEngine.stop()
            assertTrue("stop should transition to Success: $stopResult", stopResult is ForwardRecordingState.Success)

            assertEquals(
                "a gap entirely inside the session must be written in full, unclipped",
                listOf(500L),
                h.writer.gapsInjectedMillis,
            )
        } finally {
            h.captureEngine.stop()
        }
    }

    @Test
    fun `gap straddling session start writes only its in-session portion`() = withMinBufferSizeMocked {
        val h = harness(config, startAtMillis = 0L)
        try {
            // Pause begins well before the session starts.
            h.clockMillis.set(500L)
            h.captureEngine.pause()

            // Session starts while the pause is still in progress -- sessionStartMillis = 1_500.
            h.clockMillis.set(1_500L)
            val startResult = h.forwardEngine.start()
            assertTrue("start should transition to Recording: $startResult", startResult is ForwardRecordingState.Recording)

            // Resume closes the gap after the session has started: real gap is [500, 1_800) ->
            // 1_300ms total, of which only the last 300ms (1_500..1_800) falls inside the session.
            h.clockMillis.set(1_800L)
            h.captureEngine.resume()

            h.clockMillis.set(1_900L)
            val stopResult = h.forwardEngine.stop()
            assertTrue("stop should transition to Success: $stopResult", stopResult is ForwardRecordingState.Success)

            // Quantified error this issue is about: before the fix, this asserted 1_300L (the
            // full, unclipped gap) -- 1_000ms of pre-session silence written into a file that
            // should not contain it. After the fix, only the 300ms in-session portion is written.
            assertEquals(
                "a gap straddling the session start must be clipped to its in-session portion " +
                    "(1_800 - 1_500 = 300ms), not written with its full, unclipped 1_300ms duration",
                listOf(300L),
                h.writer.gapsInjectedMillis,
            )
        } finally {
            h.captureEngine.stop()
        }
    }
}
