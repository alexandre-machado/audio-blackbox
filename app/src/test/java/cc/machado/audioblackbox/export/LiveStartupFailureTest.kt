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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.*

class LiveStartupFailureTest {
    // Oracle: the real sink deletes the already-published row on journal failure, even if close
    // fails. No fake target implements the cleanup being tested.
    @Test fun journalFailureDeletesPublishedRow() = withSink({ _, _ -> throw IOException("journal") }) { sink, resolver, uri ->
        val target = sink.openStreaming("blackbox_test.ogg", "audio/ogg")
        try { target.prepare(); fail("Journal failure must surface") } catch (_: IOException) {}
        verify(resolver).delete(uri, null, null)
    }

    // Oracle: failure constructing the writer must invoke the real sink's row deletion, not close
    // alone. Opus remains selected and the engine returns its specific availability failure.
    @Test fun unavailableWriterDeletesPublishedRowAndReportsSpecificError() = withSink({ _, _ -> }) { sink, resolver, uri ->
        val engine = engine(sink) { _, _ -> throw OpusUnavailableException() }
        val result = engine.start()
        assertEquals(ForwardRecordingFailureReason.OPUS_UNAVAILABLE, (result as ForwardRecordingState.Error).reason)
        verify(resolver).delete(uri, null, null)
    }

    // Oracle: a journal blocked on real synchronization cannot hold up start() or write audio
    // before it completes. No wall-clock performance threshold or sleep is used.
    @Test fun journalRunsOffStartPathBeforeFirstAudio() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        withSink({ _, _ -> entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }) { sink, _, _ ->
            val writer = mock<StreamingAudioWriter>()
            val engine = engine(sink) { _, _ -> writer }
            try {
                assertTrue(engine.start() is ForwardRecordingState.Recording)
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                verify(writer, never()).write(any(), any(), any())
            } finally { release.countDown(); engine.stop() }
        }
    }

    private fun engine(sink: StreamingExportSink, factory: (StreamingExportTarget, AudioConfig) -> StreamingAudioWriter): ForwardRecordingEngine {
        val config = AudioConfig()
        val ring = RingBuffer(6400, config.bytesPerSecond).apply { write(ByteArray(3200)) }
        return ForwardRecordingEngine(config = config, readSinceProvider = ring::readSince,
            writeCursorProvider = ring::writeCursor, oldestCursorProvider = ring::oldestCursor,
            exportFloorAdvancerProvider = { null }, gapsProvider = { emptyList() }, sink = sink,
            writerFactory = factory)
    }

    private fun withSink(register: (Context, Uri) -> Unit, test: (MediaStoreSink, ContentResolver, Uri) -> Unit) {
        val context = mock<Context>()
        val resolver = mock<ContentResolver>()
        val uri = mock<Uri>()
        val pfd = mock<ParcelFileDescriptor>()
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(resolver.insert(any(), any())).thenReturn(uri)
        whenever(resolver.openFileDescriptor(eq(uri), any())).thenReturn(pfd)
        whenever(pfd.fileDescriptor).thenReturn(FileDescriptor())
        doThrow(IOException("close failed")).whenever(pfd).close()
        Mockito.mockStatic(MediaStore.Audio.Media::class.java).use { media ->
            media.`when`<Uri> { MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) }.thenReturn(uri)
            Mockito.mockConstruction(ContentValues::class.java).use {
                test(MediaStoreSink(context, register), resolver, uri)
            }
        }
    }
}
