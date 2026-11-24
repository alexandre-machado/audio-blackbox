package cc.machado.audioblackbox.export

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.RingBuffer
import java.io.FileDescriptor
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.*

class LiveStartupFailureTest {
    // Oracle: the real sink deletes the pending row on journal failure, even if close
    // fails. No fake target implements the cleanup being tested.
    @Test fun journalFailureDeletesPendingRowWithoutPublishing() = withSink({ _, _ -> throw IOException("journal") }) { sink, resolver, uri ->
        val target = sink.openStreaming("blackbox_test.ogg", "audio/ogg")
        try { target.prepare(); fail("Journal failure must surface") } catch (_: IOException) {}
        verify(resolver).delete(uri, null, null)
        verify(resolver, never()).update(any(), any(), anyOrNull(), anyOrNull())
    }

    // Oracle: failure constructing the writer must invoke the real sink's row deletion, not close
    // alone. Opus remains selected and the engine returns its specific availability failure.
    @Test fun unavailableWriterDeletesPublishedRowAndReportsSpecificError() = withSink({ _, _ -> }) { sink, resolver, uri ->
        val engine = engine(sink) { _, _ -> throw OpusUnavailableException() }
        engine.start()
        engine.stop() // joins the drain worker, including startup failure
        val result = engine.state.value
        assertEquals(ForwardRecordingFailureReason.OPUS_UNAVAILABLE, (result as ForwardRecordingState.Error).reason)
        verify(resolver).delete(uri, null, null)
    }

    // Oracle: start must synchronously journal and publish the real sink before returning
    // Recording. Moving preparation back after the Recording transition fails even if the
    // drain worker wins the race: registration refuses an already-announced session.
    @Test fun startPublishesAacAfterJournalBeforeReturningRecording() {
        lateinit var engine: ForwardRecordingEngine
        var journaled = false
        withSink({ _, _ ->
            check(engine.state.value !is ForwardRecordingState.Recording) { "Recording announced before publication" }
            journaled = true
        }) { sink, resolver, uri ->
            val writer = mock<StreamingAudioWriter>()
            engine = engine(sink) { _, _ -> writer }
            whenever(resolver.update(eq(uri), any(), anyOrNull(), anyOrNull())).thenAnswer {
                assertTrue("Durable journal must precede publication", journaled)
                1
            }
            try {
                assertTrue(engine.start() is ForwardRecordingState.Recording)
                verify(resolver).update(eq(uri), any(), anyOrNull(), anyOrNull())
            } finally { engine.stop() }
        }
    }

    // Oracle: a failed journal cannot report Recording or leave its unpublished row behind.
    @Test fun startReportsJournalFailureWithoutPublishing() = withSink({ _, _ -> throw IOException("journal") }) { sink, resolver, uri ->
        val engine = engine(sink) { _, _ -> error("Writer must not be constructed") }
        val result = engine.start()
        assertEquals(ForwardRecordingFailureReason.SINK_OPEN_FAILED, (result as ForwardRecordingState.Error).reason)
        verify(resolver, atLeastOnce()).delete(uri, null, null)
        verify(resolver, never()).update(any(), any(), anyOrNull(), anyOrNull())
    }

    private fun engine(sink: StreamingExportSink, factory: (StreamingExportTarget, AudioConfig) -> StreamingAudioWriter): ForwardRecordingEngine {
        val config = AudioConfig()
        val ring = RingBuffer(6400, config.bytesPerSecond).apply { write(ByteArray(3200)) }
        return ForwardRecordingEngine(config = config, readSinceProvider = ring::readSince,
            writeCursorProvider = ring::writeCursor, oldestCursorProvider = ring::oldestCursor,
            exportFloorAdvancerProvider = { null }, gapsProvider = { emptyList() }, sink = sink,
            writerFactory = factory)
    }

    private fun withSink(register: (Context, Uri) -> Unit, test: (StreamingExportSink, ContentResolver, Uri) -> Unit) {
        val context = mock<Context>()
        val resolver = mock<ContentResolver>()
        val uri = mock<Uri>()
        val pfd = mock<ParcelFileDescriptor>()
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(resolver.insert(any(), any())).thenReturn(uri)
        whenever(resolver.openFileDescriptor(eq(uri), any())).thenReturn(pfd)
        whenever(pfd.fileDescriptor).thenReturn(FileDescriptor())
        doThrow(IOException("close failed")).whenever(pfd).close()
        // Mockito static/construction scopes belong to the thread performing provider work.
        // Keep the real sink under test when the engine moves preparation onto its worker.
        fun <T> withProviderMocks(block: () -> T): T =
            Mockito.mockStatic(MediaStore.Audio.Media::class.java).use { media ->
                media.`when`<Uri> { MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) }.thenReturn(uri)
                Mockito.mockConstruction(ContentValues::class.java).use { block() }
            }
        val realSink = MediaStoreSink(context, register)
        val sink = object : StreamingExportSink {
            override fun openStreaming(displayName: String, mimeType: String): StreamingExportTarget {
                val target = withProviderMocks { realSink.openStreaming(displayName, mimeType) }
                return object : StreamingExportTarget by target {
                    override fun prepare() = withProviderMocks { target.prepare() }
                }
            }
        }
        test(sink, resolver, uri)
    }
}
