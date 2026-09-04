package cc.machado.audioblackbox.export

import android.net.Uri
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.GoertzelDetector
import cc.machado.audioblackbox.audio.ReadSinceResult
import cc.machado.audioblackbox.audio.RingBuffer
import cc.machado.audioblackbox.audio.ToneGenerator
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.OutputStream
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    private class CapturingWriter(val config: AudioConfig) : StreamingAudioWriter {
        val out = ByteArrayOutputStream()
        private var finished = false
        private var closed = false
        override val totalBytesWritten: Long get() = out.size().toLong()
        override val isSessionFinished: Boolean get() = finished
        override val isSessionClosed: Boolean get() = closed
        override fun write(pcmData: ByteArray, offset: Int, length: Int) = out.write(pcmData, offset, length)
        override fun writeGap(gapDurationMillis: Long) = Unit
        override fun finish() { finished = true }
        override fun close() { closed = true }
    }

    private companion object {
        const val TONE_HZ = 400.0
        const val MISLABEL_RANGE_BYTES = 32_000
        const val BOUNDARY_BYTES = 32_000
    }
}
