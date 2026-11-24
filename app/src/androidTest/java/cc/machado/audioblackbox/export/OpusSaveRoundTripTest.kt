package cc.machado.audioblackbox.export

import android.media.MediaScannerConnection
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.RingBuffer
import cc.machado.audioblackbox.settings.SaveAudioFormat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpusSaveRoundTripTest {
    /** Oracle: every preset traverses the production Save encoder and sink; the scanned row
     * retains an Ogg name/MIME and the saved payload decodes to PCM, without mocked codecs. */
    @Test fun everyPresetSavesScansAndDecodes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((rate, channels) in listOf(16_000 to 1, 32_000 to 1, 44_100 to 2)) {
            val config = AudioConfig(sampleRateHz = rate, channelCount = channels)
            val pcm = ToneGenerator.tone(440.0, rate, 2000, channels)
            val ring = RingBuffer(pcm.size * 2, config.bytesPerSecond).apply { write(pcm) }
            val sink = MediaStoreSink(context)
            val selector = RecordingFormatSelector({ SaveAudioFormat.OPUS }, context.cacheDir)
            val name = "blackbox_save_roundtrip_${System.nanoTime()}.ogg"
            val engine = ExportEngine(config = config,
                readSinceProvider = ring::readSince, writeCursorProvider = ring::writeCursor,
                oldestCursorProvider = ring::oldestCursor, capacityBytesProvider = { ring.capacityBytes },
                exportFloorAdvancerProvider = { ring::advanceExportFloor },
                estimateTimestampProvider = ring::estimateTimestamp, gapsProvider = { emptyList() },
                sink = object : ExportSink {
                    override fun open(displayName: String, mimeType: String): ExportTarget {
                        assertTrue(displayName.endsWith(".ogg"))
                        return sink.open(name, mimeType)
                    }
                }, payloadEncoder = WavPayloadEncoder, payloadEncoderProvider = selector::saveEncoder)
            val copy = File(context.cacheDir, name)
            try {
                val result = engine.export(2000, 0)
                assertTrue("$result", result is ExportState.Success)
                val uri = sink.queryRecordings().single { it.displayName == name }.uri
                val path = context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA),
                    null, null, null)!!.use { check(it.moveToFirst()); it.getString(0) }
                val scanned = CountDownLatch(1)
                var success = false
                MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("audio/ogg")) { _, row ->
                    success = row != null
                    scanned.countDown()
                }
                assertTrue(scanned.await(30, TimeUnit.SECONDS))
                assertTrue(success)
                val row = sink.queryRecordings().single { it.uri == uri }
                assertTrue(row.displayName.endsWith(".ogg"))
                assertTrue(row.mimeType in setOf("audio/ogg", "audio/opus", "application/ogg", "audio/x-ogg"))
                context.contentResolver.openInputStream(uri)!!.use { input -> copy.outputStream().use { input.copyTo(it) } }
                assertTrue("Saved Opus must decode through MediaExtractor/MediaCodec",
                    OggPrefixQualificationTest().decode(copy).isNotEmpty())
                assertEquals(pcm.size.toLong(), ring.exportFloor())
            } finally {
                sink.queryRecordings().filter { it.displayName == name }.forEach {
                    context.contentResolver.delete(it.uri, null, null)
                }
                copy.delete()
            }
        }
    }
}
