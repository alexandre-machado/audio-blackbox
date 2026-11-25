package cc.machado.audioblackbox.export

import android.media.MediaCodecList
import android.media.MediaCodec
import android.media.MediaFormat
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.RingBuffer
import cc.machado.audioblackbox.settings.SaveAudioFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import cc.machado.audioblackbox.ui.settings.SettingsViewModel
import cc.machado.audioblackbox.ui.settings.nextRecordingFormatNotice
import cc.machado.audioblackbox.R
import org.mockito.Mockito
import org.mockito.kotlin.*

class SaveAudioFormatWiringTest {
    private val config = AudioConfig()
    private fun ring() = RingBuffer(64_000, config.bytesPerSecond).apply { write(ByteArray(3200) { 42 }) }

    // Oracle: changing preference during sink.open cannot change this file's MIME or encoder.
    @Test fun saveSnapshotsTheProductionSelectionAndAdvancesFloorOnlyAfterCommit() {
        var selected = SaveAudioFormat.OPUS
        val ring = ring()
        var committed = false
        val output = ByteArrayOutputStream()
        val target = object : ExportTarget {
            override val outputStream = output
            override fun commit() { committed = true }
            override fun abort() = error("unexpected abort")
        }
        val sink = savingSink { name, mime ->
            assertTrue(name.endsWith(".ogg"))
            assertEquals("audio/ogg", mime)
            selected = SaveAudioFormat.AAC
            target
        }
        Mockito.mockConstruction(OpusPayloadEncoder::class.java) { encoder, _ ->
            whenever(encoder.mimeType).thenReturn("audio/ogg")
            whenever(encoder.fileExtension).thenReturn("ogg")
            doAnswer { call ->
                val source = call.getArgument<PayloadChunkSource>(2)
                while (true) {
                    val bytes = source.nextChunk() ?: break
                    call.getArgument<java.io.OutputStream>(3).write(bytes)
                }
                null
            }.whenever(encoder).encode(any(), any(), any(), any(), any())
        }.use { encoders ->
            val selector = RecordingFormatSelector({ selected }, File(System.getProperty("java.io.tmpdir")))
            val engine = engine(ring, sink, selector::saveEncoder)
            assertTrue(engine.export(100, 0) is ExportState.Success)
            assertTrue(committed)
            assertArrayEquals(ByteArray(3200) { 42 }, output.toByteArray())
            verify(encoders.constructed().single()).encode(any(), eq(3200L), any(), any(), any())
            assertEquals(3200L, ring.exportFloor())
            assertTrue(selector.saveEncoder() is AacPayloadEncoder)
        }
    }

    // Oracle: a restored OPUS choice with no matching codec must abort the real export and
    // preserve every unsaved byte. It cannot choose AAC or report a successful file.
    @Test fun unavailableOpusAbortsWithoutFallbackOrFloorAdvance() {
        val temp = java.nio.file.Files.createTempDirectory("opus-failure").toFile()
        try {
            val format = mock<MediaFormat>()
            Mockito.mockStatic(MediaFormat::class.java).use { formats ->
                formats.`when`<MediaFormat> { MediaFormat.createAudioFormat("audio/opus", 16_000, 1) }.thenReturn(format)
                Mockito.mockConstruction(MediaCodecList::class.java) { codecs, _ ->
                    whenever(codecs.findEncoderForFormat(format)).thenReturn(null)
                }.use {
                    assertFalse(OpusEncoding.available(config))
                    val selector = RecordingFormatSelector({ SaveAudioFormat.OPUS }, temp)
                    val ring = ring()
                    var aborts = 0
                    var commits = 0
                    val sink = savingSink { name, mime ->
                        assertTrue(name.endsWith(".ogg"))
                        assertEquals("audio/ogg", mime)
                        object : ExportTarget {
                            override val outputStream = ByteArrayOutputStream()
                            override fun commit() { commits++ }
                            override fun abort() { aborts++ }
                        }
                    }
                    val result = engine(ring, sink, selector::saveEncoder).export(100, 0)
                    assertTrue("$result", result is ExportState.Error)
                    assertTrue((result as ExportState.Error).message.contains("Opus unavailable on this device."))
                    assertEquals(ExportFailureReason.OPUS_UNAVAILABLE, result.reason)
                    val liveTarget = mock<StreamingExportTarget> {
                        on { fileDescriptor } doReturn FileDescriptor()
                    }
                    val live = ForwardRecordingEngine(config = config,
                        readSinceProvider = ring::readSince, writeCursorProvider = ring::writeCursor,
                        oldestCursorProvider = ring::oldestCursor, exportFloorAdvancerProvider = { null },
                        gapsProvider = { emptyList() }, sink = streamingSink { _, mime ->
                            assertEquals("audio/ogg", mime); liveTarget
                        }, liveEncodingProvider = {
                            val encoding = selector.liveEncoding()
                            encoding.copy(createWriter = { target, cfg ->
                                // Mockito static/construction scopes belong to the drain thread.
                                Mockito.mockStatic(MediaFormat::class.java).use { workerFormats ->
                                    workerFormats.`when`<MediaFormat> {
                                        MediaFormat.createAudioFormat("audio/opus", 16_000, 1)
                                    }.thenReturn(format)
                                    Mockito.mockConstruction(MediaCodecList::class.java) { codecs, _ ->
                                        whenever(codecs.findEncoderForFormat(format)).thenReturn(null)
                                    }.use { encoding.createWriter(target, cfg) }
                                }
                            })
                        })
                    live.start()
                    assertEquals(ForwardRecordingFailureReason.OPUS_UNAVAILABLE,
                        (live.stop() as ForwardRecordingState.Error).reason)
                    verify(liveTarget).abortUnstarted()
                    assertEquals(1, aborts)
                    assertEquals(0, commits)
                    assertEquals(0L, ring.exportFloor())
                    assertEquals(3200L, ring.bufferedBytes())
                    assertTrue(temp.listFiles()!!.isEmpty())
                    verify(format, atLeastOnce()).setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
                }
            }
        } finally { temp.deleteRecursively() }
    }

    // Oracle: the real Live engine opens the selected container and keeps its original writer
    // even when the preference changes during the MediaStore-open boundary.
    @Test fun liveSnapshotsMimeSuffixAndWriterThenNextSessionUsesAac() {
        var selected = SaveAudioFormat.OPUS
        val selector = RecordingFormatSelector({ selected }, File(System.getProperty("java.io.tmpdir")))
        val ring = ring()
        val opened = mutableListOf<Pair<String, String>>()
        val target = mock<StreamingExportTarget> {
            on { fileDescriptor } doReturn FileDescriptor()
        }
        val sink = streamingSink { name, mime ->
            opened += name to mime
            selected = SaveAudioFormat.AAC
            target
        }
        val opus = mutableListOf<StreamingOpusWriter>()
        val aac = mutableListOf<StreamingAdtsWriter>()
                val engine = ForwardRecordingEngine(config = config,
                    readSinceProvider = ring::readSince, writeCursorProvider = ring::writeCursor,
                    oldestCursorProvider = ring::oldestCursor, exportFloorAdvancerProvider = { null },
                    gapsProvider = { emptyList() }, sink = sink, liveEncodingProvider = {
                        val encoding = selector.liveEncoding()
                        encoding.copy(createWriter = { destination, cfg ->
                            Mockito.mockConstruction(StreamingOpusWriter::class.java).use { opusMocks ->
                                Mockito.mockConstruction(StreamingAdtsWriter::class.java).use { aacMocks ->
                                    encoding.createWriter(destination, cfg).also {
                                        opus += opusMocks.constructed()
                                        aac += aacMocks.constructed()
                                    }
                                }
                            }
                        })
                    })
                assertTrue(engine.start() is ForwardRecordingState.Recording)
                assertTrue(engine.stop() is ForwardRecordingState.Success)
                assertEquals("audio/ogg", opened[0].second)
                assertTrue(opened[0].first.endsWith(".ogg"))
                verify(opus.single(), atLeastOnce()).write(any(), any(), any())
                assertTrue(aac.isEmpty())
                assertTrue(engine.start() is ForwardRecordingState.Recording)
                assertTrue(engine.stop() is ForwardRecordingState.Success)
                assertEquals("audio/aac-adts", opened[1].second)
                assertTrue(opened[1].first.endsWith(".aac"))
                verify(aac.single(), atLeastOnce()).write(any(), any(), any())
    }

    // Oracle: a preference change must not relabel the engine or Settings, replace the AAC
    // writer, or rename its target. Stop removes the qualifier; only the next start uses Opus.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aacLivePreferenceSwitchIsOnlyAppliedToNextSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val selected = java.util.concurrent.atomic.AtomicReference(SaveAudioFormat.AAC)
        val selector = RecordingFormatSelector(selected::get, File(System.getProperty("java.io.tmpdir")))
        val ring = ring()
        val opened = mutableListOf<Pair<String, String>>()
        val aac = mutableListOf<StreamingAdtsWriter>()
        val opus = mutableListOf<StreamingOpusWriter>()
        val target = mock<StreamingExportTarget> { on { fileDescriptor } doReturn FileDescriptor() }
        val engine = ForwardRecordingEngine(config = config,
            readSinceProvider = ring::readSince, writeCursorProvider = ring::writeCursor,
            oldestCursorProvider = ring::oldestCursor, exportFloorAdvancerProvider = { null },
            gapsProvider = { emptyList() }, sink = streamingSink { name, mime ->
                opened += name to mime
                target
            }, liveEncodingProvider = {
                val encoding = selector.liveEncoding()
                encoding.copy(createWriter = { destination, cfg ->
                    Mockito.mockConstruction(StreamingAdtsWriter::class.java).use { aacMocks ->
                        Mockito.mockConstruction(StreamingOpusWriter::class.java).use { opusMocks ->
                            encoding.createWriter(destination, cfg).also {
                                aac += aacMocks.constructed()
                                opus += opusMocks.constructed()
                            }
                        }
                    }
                })
            })
        val store = androidx.lifecycle.ViewModelStore()
        var collector: kotlinx.coroutines.Job? = null
        try {
            val vm = SettingsViewModel(forwardRecordingState = engine.state)
            store.put("settings", vm)
            collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.runningLiveFormat.collect {} }
            assertTrue(engine.start() is ForwardRecordingState.Recording)
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) {
                    engine.state.first { it is ForwardRecordingState.Recording && it.bytesWritten == 3200L }
                }
            }
            runCurrent()
            assertNull(nextRecordingFormatNotice(selected.get(), vm.runningLiveFormat.value))
            selected.set(SaveAudioFormat.OPUS)
            ring.write(ByteArray(3200) { 7 })
            kotlinx.coroutines.withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(5_000) {
                    engine.state.first { it is ForwardRecordingState.Recording && it.bytesWritten == 6400L }
                }
            }
            runCurrent()
            assertEquals(SaveAudioFormat.AAC, (engine.state.value as ForwardRecordingState.Recording).format)
            assertEquals(R.string.settings_format_next_recording_aac,
                nextRecordingFormatNotice(selected.get(), vm.runningLiveFormat.value))
            val saved = engine.stop() as ForwardRecordingState.Success
            runCurrent()
            assertNull(vm.runningLiveFormat.value)
            assertNull(nextRecordingFormatNotice(selected.get(), vm.runningLiveFormat.value))
            assertTrue(saved.displayName.endsWith(".aac"))
            assertEquals("audio/aac-adts", opened.single().second)
            verify(aac.single(), atLeastOnce()).write(any(), any(), any())
            assertTrue(opus.isEmpty())
            assertEquals(SaveAudioFormat.OPUS, (engine.start() as ForwardRecordingState.Recording).format)
            runCurrent()
            assertEquals(SaveAudioFormat.OPUS, vm.runningLiveFormat.value)
            assertNull(nextRecordingFormatNotice(selected.get(), vm.runningLiveFormat.value))
            assertTrue((engine.stop() as ForwardRecordingState.Success).displayName.endsWith(".ogg"))
            assertEquals("audio/ogg", opened[1].second)
            verify(opus.single(), atLeastOnce()).write(any(), any(), any())
            assertEquals(1, aac.size)
        } finally {
            engine.stop()
            collector?.cancel()
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    // Oracle: discovery success is not encode success. A real configure-path exception must
    // delete the pending export, release the codec and keep the unsaved floor at zero.
    @Test fun configureFailureAbortsAndReleasesWithoutFloorAdvance() {
        val directory = java.nio.file.Files.createTempDirectory("opus-configure-failure").toFile()
        try {
            val format = mock<MediaFormat>()
            val codec = mock<MediaCodec>()
            doThrow(IllegalStateException("configure failed")).whenever(codec)
                .configure(eq(format), isNull<android.view.Surface>(), isNull<android.media.MediaCrypto>(),
                    eq(MediaCodec.CONFIGURE_FLAG_ENCODE))
            Mockito.mockStatic(MediaFormat::class.java).use { formats ->
                formats.`when`<MediaFormat> { MediaFormat.createAudioFormat("audio/opus", 16_000, 1) }.thenReturn(format)
                Mockito.mockStatic(MediaCodec::class.java).use { factory ->
                    factory.`when`<MediaCodec> { MediaCodec.createByCodecName("test.opus") }.thenReturn(codec)
                    Mockito.mockConstruction(MediaCodecList::class.java) { codecs, _ ->
                        whenever(codecs.findEncoderForFormat(format)).thenReturn("test.opus")
                    }.use {
                        val ring = ring()
                        var aborted = false
                        val sink = savingSink { _, _ -> object : ExportTarget {
                            override val outputStream = ByteArrayOutputStream()
                            override fun commit() = error("A failed encoder cannot commit")
                            override fun abort() { aborted = true }
                        } }
                        val selector = RecordingFormatSelector({ SaveAudioFormat.OPUS }, directory)
                        val result = engine(ring, sink, selector::saveEncoder).export(100, 0)
                        assertTrue("$result", result is ExportState.Error)
                        assertTrue((result as ExportState.Error).message.contains("configure failed"))
                        assertTrue(aborted)
                        assertEquals(0L, ring.exportFloor())
                        assertEquals(3200L, ring.bufferedBytes())
                        verify(codec).release()
                        assertTrue(directory.listFiles()!!.isEmpty())
                    }
                }
            }
        } finally { directory.deleteRecursively() }
    }

    private fun engine(ring: RingBuffer, sink: ExportSink, provider: () -> PayloadEncoder) = ExportEngine(
        config = config, readSinceProvider = ring::readSince, writeCursorProvider = ring::writeCursor,
        oldestCursorProvider = ring::oldestCursor, capacityBytesProvider = { ring.capacityBytes },
        exportFloorAdvancerProvider = { ring::advanceExportFloor }, estimateTimestampProvider = ring::estimateTimestamp,
        gapsProvider = { emptyList() }, sink = sink, payloadEncoder = WavPayloadEncoder,
        payloadEncoderProvider = provider,
    )

    // Oracle: discovery must use the encoder's rate and total bitrate, not the capture rate.
    @Test fun capabilityProbeUsesPostResampleConfiguration() {
        for ((input, channels, output, bitrate) in listOf(
            listOf(16_000, 1, 16_000, 24_000), listOf(32_000, 1, 48_000, 32_000),
            listOf(44_100, 2, 48_000, 96_000))) {
            val format = mock<MediaFormat>()
            Mockito.mockStatic(MediaFormat::class.java).use { formats ->
                formats.`when`<MediaFormat> { MediaFormat.createAudioFormat("audio/opus", output, channels) }
                    .thenReturn(format)
                Mockito.mockConstruction(MediaCodecList::class.java) { codecs, construction ->
                    assertEquals(listOf(MediaCodecList.REGULAR_CODECS), construction.arguments())
                    whenever(codecs.findEncoderForFormat(format)).thenReturn("test.opus.encoder")
                }.use { codecs ->
                    assertTrue(OpusEncoding.available(AudioConfig(sampleRateHz = input, channelCount = channels)))
                    verify(format).setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    verify(codecs.constructed().single()).findEncoderForFormat(format)
                }
            }
        }
    }

    // Oracle: real encoder metadata preserves AAC defaults and exposes Ogg without MIME relabeling.
    @Test fun actualEncodersAndLiveSelectionsExposeMatchingContainers() {
        var selected = SaveAudioFormat.AAC
        val selector = RecordingFormatSelector({ selected }, File(System.getProperty("java.io.tmpdir")))
        assertEquals("m4a", selector.saveEncoder().fileExtension)
        assertEquals("audio/mp4", selector.saveEncoder().mimeType)
        assertEquals("aac", selector.liveEncoding().extension)
        assertEquals("audio/aac-adts", selector.liveEncoding().mimeType)
        selected = SaveAudioFormat.OPUS
        assertEquals("ogg", selector.saveEncoder().fileExtension)
        assertEquals("audio/ogg", selector.saveEncoder().mimeType)
        assertEquals("ogg", selector.liveEncoding().extension)
        assertEquals("audio/ogg", selector.liveEncoding().mimeType)
        for (mime in listOf("audio/ogg", "audio/opus", "application/ogg", "audio/x-ogg")) {
            assertTrue(StreamingOpusWriter.accepts(mime, "blackbox.bin"))
        }
        assertTrue(StreamingOpusWriter.accepts("application/octet-stream", "blackbox.OGG"))
        assertFalse(StreamingOpusWriter.accepts("audio/mp4", "blackbox.m4a"))
    }

    private fun savingSink(open: (String, String) -> ExportTarget) = object : ExportSink {
        override fun open(displayName: String, mimeType: String) = open.invoke(displayName, mimeType)
    }
    private fun streamingSink(open: (String, String) -> StreamingExportTarget) = object : StreamingExportSink {
        override fun openStreaming(displayName: String, mimeType: String) = open.invoke(displayName, mimeType)
    }
}
