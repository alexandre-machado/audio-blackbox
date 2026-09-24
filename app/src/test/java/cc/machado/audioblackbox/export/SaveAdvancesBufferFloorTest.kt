package cc.machado.audioblackbox.export

import android.net.Uri
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.PauseGap
import cc.machado.audioblackbox.audio.RingBuffer
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #410: after a *successful* save, the next save starts where the previous file ended.
 *
 * Oracle: the PCM payload bytes each save actually committed to its sink, compared against the
 * exact byte patterns written into a real [RingBuffer], plus the ring's own reported floor/buffered
 * state. Each write uses a distinct byte pattern, so any overlap between two consecutive files, or
 * any audio lost between them, shows up as a byte mismatch. Total writes stay well below capacity,
 * so ring saturation cannot be what separates the two files (AGENTS.md §2, trap 2), and the
 * #385 startup headroom never engages (it only applies to a saturated buffer).
 *
 * Mutation-verified (see the PR body): removing the `floorAdvancer?.invoke(writeCursor)` call in
 * `ExportEngine.runExport` fails the overlap/UI tests; advancing it regardless of outcome fails
 * the failure/cancel tests.
 */
class SaveAdvancesBufferFloorTest {

    // 1000 Hz mono 16-bit = 2000 bytes/s.
    private val config = AudioConfig(sampleRateHz = 1000, channelCount = 1)

    private companion object {
        const val WAV_HEADER_BYTES = 44
        const val CAPACITY_BYTES = 100_000
        const val WHOLE_BUFFER_MILLIS = 60_000L
    }

    private fun pattern(size: Int, base: Int): ByteArray = ByteArray(size) { ((it % 50) + base).toByte() }

    private fun newRing() = RingBuffer(capacityBytes = CAPACITY_BYTES, bytesPerSecond = config.bytesPerSecond)

    /** Records every committed file's bytes; aborted files are never recorded. */
    private class RecordingSink(
        var failOpen: Boolean = false,
        var failWrite: Boolean = false,
    ) : ExportSink {
        val committed = mutableListOf<ByteArray>()
        var aborts = 0
        var opens = 0

        override fun open(displayName: String, mimeType: String): ExportTarget {
            opens++
            if (failOpen) throw IOException("insert rejected")
            val buffer = ByteArrayOutputStream()
            val stream: OutputStream = if (failWrite) {
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("disk full")
                    override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
                }
            } else {
                buffer
            }
            return object : ExportTarget {
                override val outputStream: OutputStream = stream
                override fun commit() {
                    committed += buffer.toByteArray()
                }
                override fun abort() {
                    aborts++
                }
            }
        }
    }

    /** Same cursor-based collaboration production wiring uses, including the floor advancer bound
     * to the real ring. [gapsProvider] runs after the export has fixed its window, which makes it
     * the seam for "something happens while this save is in flight". */
    private fun engineFor(
        ring: RingBuffer,
        sink: ExportSink,
        gapsProvider: () -> List<PauseGap> = { emptyList() },
    ): ExportEngine = ExportEngine(
        config = config,
        readSinceProvider = { cursor, maxBytes -> ring.readSince(cursor, maxBytes) },
        writeCursorProvider = { ring.writeCursor() },
        oldestCursorProvider = { ring.oldestCursor() },
        capacityBytesProvider = { ring.capacityBytes },
        exportFloorAdvancerProvider = { { cursor -> ring.advanceExportFloor(cursor) } },
        estimateTimestampProvider = { offset -> ring.estimateTimestamp(offset) },
        gapsProvider = gapsProvider,
        sink = sink,
        payloadEncoder = WavPayloadEncoder,
    )

    private fun pcmOf(file: ByteArray): ByteArray = file.copyOfRange(WAV_HEADER_BYTES, file.size)

    @Test
    fun `second save contains only audio captured after the first file ended - no overlap`() {
        val ring = newRing()
        val sink = RecordingSink()
        val engine = engineFor(ring, sink)
        val first = pattern(4000, 1)
        val second = pattern(1200, 60)

        ring.write(first)
        val r1 = engine.export(WHOLE_BUFFER_MILLIS, minutesLabel = 1)
        assertTrue("first save must succeed: $r1", r1 is ExportState.Success)

        ring.write(second)
        val r2 = engine.export(WHOLE_BUFFER_MILLIS, minutesLabel = 1)
        assertTrue("second save must succeed: $r2", r2 is ExportState.Success)

        assertEquals(2, sink.committed.size)
        assertArrayEquals("first file = everything buffered", first, pcmOf(sink.committed[0]))
        assertArrayEquals("second file = only post-first-save audio", second, pcmOf(sink.committed[1]))
        assertEquals(5200L, ring.exportFloor())
    }

    @Test
    fun `audio captured while a save is encoding stays available for the next save`() {
        // The cut point is the window the file actually contains (writeCursor when the window was
        // fixed), not wherever the writer has reached by the time the save finishes.
        val ring = newRing()
        val sink = RecordingSink()
        val before = pattern(4000, 1)
        val during = pattern(800, 60)
        var writeDuringSave = true
        val engine = engineFor(ring, sink, gapsProvider = {
            if (writeDuringSave) {
                writeDuringSave = false
                ring.write(during)
            }
            emptyList()
        })

        ring.write(before)
        val r1 = engine.export(WHOLE_BUFFER_MILLIS, minutesLabel = 1)
        assertTrue("$r1", r1 is ExportState.Success)
        assertArrayEquals(before, pcmOf(sink.committed[0]))
        assertEquals(4000L, ring.exportFloor())
        assertEquals(800L, ring.bufferedBytes())

        val r2 = engine.export(WHOLE_BUFFER_MILLIS, minutesLabel = 1)
        assertTrue("$r2", r2 is ExportState.Success)
        assertArrayEquals(during, pcmOf(sink.committed[1]))
    }

    @Test
    fun `saving again with nothing new hits NO_AUDIO_BUFFERED and opens no file`() {
        val ring = newRing()
        val sink = RecordingSink()
        val engine = engineFor(ring, sink)
        ring.write(pattern(4000, 1))
        assertTrue(engine.export(WHOLE_BUFFER_MILLIS, minutesLabel = 1) is ExportState.Success)

        val again = engine.export(WHOLE_BUFFER_MILLIS, minutesLabel = 1)

        assertTrue("$again", again is ExportState.Error)
        assertEquals(ExportFailureReason.NO_AUDIO_BUFFERED, (again as ExportState.Error).reason)
        assertEquals("no zero-length file may be opened", 1, sink.opens)
        assertEquals(1, sink.committed.size)
    }

    @Test
    fun `sink open failure leaves the floor unchanged and a retry gets the same audio`() {
        val ring = newRing()
        val sink = RecordingSink(failOpen = true)
        val engine = engineFor(ring, sink)
        val audio = pattern(4000, 1)
        ring.write(audio)

        val failed = engine.export(WHOLE_BUFFER_MILLIS, minutesLabel = 1)
        assertEquals(ExportFailureReason.SINK_OPEN_FAILED, (failed as ExportState.Error).reason)
        assertEquals(0L, ring.exportFloor())
        assertEquals(4000L, ring.bufferedBytes())

        sink.failOpen = false
        val retry = engine.export(WHOLE_BUFFER_MILLIS, minutesLabel = 1)
        assertTrue("$retry", retry is ExportState.Success)
        assertArrayEquals(audio, pcmOf(sink.committed.single()))
    }

    @Test
    fun `write failure leaves the floor unchanged`() {
        val ring = newRing()
        val sink = RecordingSink(failWrite = true)
        val engine = engineFor(ring, sink)
        ring.write(pattern(4000, 1))

        val failed = engine.export(WHOLE_BUFFER_MILLIS, minutesLabel = 1)

        assertEquals(ExportFailureReason.WRITE_FAILED, (failed as ExportState.Error).reason)
        assertEquals(1, sink.aborts)
        assertEquals(0L, ring.exportFloor())
        assertEquals(0L, ring.oldestCursor())
        assertEquals(4000L, ring.bufferedBytes())
    }

    @Test
    fun `cancelled save leaves the floor unchanged`() {
        val ring = newRing()
        val sink = RecordingSink()
        lateinit var engine: ExportEngine
        engine = engineFor(ring, sink, gapsProvider = {
            engine.cancel()
            emptyList()
        })
        ring.write(pattern(4000, 1))

        val cancelled = engine.export(WHOLE_BUFFER_MILLIS, minutesLabel = 1)

        assertEquals(ExportFailureReason.CANCELLED, (cancelled as ExportState.Error).reason)
        assertTrue(sink.committed.isEmpty())
        assertEquals(0L, ring.exportFloor())
        assertEquals(4000L, ring.bufferedBytes())
    }

    @Test
    fun `lapped save leaves the floor unchanged`() {
        // The one test here that saturates the ring on purpose: the writer overruns the save's
        // window after it was fixed, so the drain reports CURSOR_LAPPED.
        val ring = newRing()
        val sink = RecordingSink()
        var overrun = true
        val engine = engineFor(ring, sink, gapsProvider = {
            if (overrun) {
                overrun = false
                ring.write(pattern(CAPACITY_BYTES + 10_000, 60))
            }
            emptyList()
        })
        ring.write(pattern(4000, 1))

        val failed = engine.export(WHOLE_BUFFER_MILLIS, minutesLabel = 1)

        assertEquals(ExportFailureReason.CURSOR_LAPPED, (failed as ExportState.Error).reason)
        assertTrue(sink.committed.isEmpty())
        assertEquals(0L, ring.exportFloor())
    }

    @Test
    fun `a save whose stream was cleared before it committed cannot move the new stream's floor`() {
        // The floor cursor from the old stream (4000) lies past the restarted stream's write head,
        // so advanceExportFloor must ignore it rather than clamp it onto unsaved new audio.
        val ring = newRing()
        val sink = RecordingSink()
        var restart = true
        val engine = engineFor(ring, sink, gapsProvider = {
            if (restart) {
                restart = false
                ring.clear()
                ring.write(pattern(1000, 60))
            }
            emptyList()
        })
        ring.write(pattern(4000, 1))

        engine.export(WHOLE_BUFFER_MILLIS, minutesLabel = 1)

        assertEquals(0L, ring.exportFloor())
        assertEquals(1000L, ring.bufferedBytes())
    }

    @Test
    fun `buffered duration a UI reads drops to zero right after a successful save`() {
        // The value Dashboard/notification poll is RingBuffer.bufferedDurationMillis via
        // AudioCaptureEngine; it must reflect what a save would now contain (AGENTS.md §5, #175).
        val ring = newRing()
        val sink = RecordingSink()
        val engine = engineFor(ring, sink)
        ring.write(pattern(4000, 1))
        assertEquals(2000L, ring.bufferedDurationMillis())

        assertTrue(engine.export(WHOLE_BUFFER_MILLIS, minutesLabel = 1) is ExportState.Success)

        assertEquals(0L, ring.bufferedDurationMillis())
        ring.write(pattern(1000, 60))
        assertEquals(500L, ring.bufferedDurationMillis())
    }

    // ---- Forward recording: same rule, the floor goes to the last byte the file persisted. ----

    private class FakeStreamingTarget : StreamingExportTarget {
        override val uri: Uri = org.mockito.kotlin.mock()
        override val fileDescriptor: FileDescriptor = FileDescriptor()
        override val outputStream: OutputStream = ByteArrayOutputStream()
        override fun finish() {}
        override fun refinalizeMetadata() {}
        override fun close() {}
    }

    private class CollectingWriter : StreamingAudioWriter {
        val pcm = ByteArrayOutputStream()
        private var finished = false
        private var closed = false
        override val totalBytesWritten: Long get() = pcm.size().toLong()
        override val isSessionFinished: Boolean get() = finished
        override val isSessionClosed: Boolean get() = closed
        override fun write(pcmData: ByteArray, offset: Int, length: Int) {
            pcm.write(pcmData, offset, length)
        }
        override fun writeGap(gapDurationMillis: Long) {}
        override fun finish() {
            finished = true
        }
        override fun close() {
            closed = true
        }
    }

    private fun forwardEngineFor(ring: RingBuffer, writerSink: (CollectingWriter) -> Unit): ForwardRecordingEngine =
        ForwardRecordingEngine(
            config = config,
            readSinceProvider = { cursor, maxBytes -> ring.readSince(cursor, maxBytes) },
            writeCursorProvider = { ring.writeCursor() },
            oldestCursorProvider = { ring.oldestCursor() },
            exportFloorAdvancerProvider = { { cursor -> ring.advanceExportFloor(cursor) } },
            gapsProvider = { emptyList() },
            sink = object : StreamingExportSink {
                override fun openStreaming(displayName: String, mimeType: String): StreamingExportTarget =
                    FakeStreamingTarget()
            },
            writerFactory = { _, _ -> CollectingWriter().also(writerSink) },
        )

    @Test
    fun `successful forward recording advances the floor to the last byte it persisted`() {
        val ring = newRing()
        var writer: CollectingWriter? = null
        val forward = forwardEngineFor(ring) { writer = it }
        val past = pattern(3000, 1)
        val live = pattern(1000, 60)
        ring.write(past)

        assertTrue(forward.start() is ForwardRecordingState.Recording)
        ring.write(live)
        val result = forward.stop()

        assertTrue("$result", result is ForwardRecordingState.Success)
        assertArrayEquals(past + live, writer!!.pcm.toByteArray())
        assertEquals(4000L, ring.exportFloor())
        assertEquals(0L, ring.bufferedBytes())
    }

    @Test
    fun `forward recording after a save starts at the save's cut, not at the oldest byte`() {
        val ring = newRing()
        val saveEngine = engineFor(ring, RecordingSink())
        var writer: CollectingWriter? = null
        val forward = forwardEngineFor(ring) { writer = it }
        ring.write(pattern(3000, 1))
        assertTrue(saveEngine.export(WHOLE_BUFFER_MILLIS, minutesLabel = 1) is ExportState.Success)
        val after = pattern(1000, 60)
        ring.write(after)

        assertTrue(forward.start() is ForwardRecordingState.Recording)
        val result = forward.stop()

        assertTrue("$result", result is ForwardRecordingState.Success)
        assertArrayEquals(after, writer!!.pcm.toByteArray())
    }

    @Test
    fun `cancelled forward recording leaves the floor unchanged`() {
        val ring = newRing()
        val forward = forwardEngineFor(ring) {}
        ring.write(pattern(3000, 1))

        assertTrue(forward.start() is ForwardRecordingState.Recording)
        val result = forward.cancel()

        assertTrue("$result", result is ForwardRecordingState.Error)
        assertEquals(ForwardRecordingFailureReason.CANCELLED, (result as ForwardRecordingState.Error).reason)
        assertEquals(0L, ring.exportFloor())
        assertEquals(3000L, ring.bufferedBytes())
    }
}
