package cc.machado.audioblackbox.export

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.PauseGap
import cc.machado.audioblackbox.audio.RingBuffer
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [ForwardRecordingEngine] (issue #54).
 *
 * Verifies live AAC encoding via MediaStore / file streaming, Goertzel frequency detection,
 * live interruption gap timeline alignment, concurrent snapshot capability, and resource cleanup.
 */
@RunWith(AndroidJUnit4::class)
class ForwardRecordingEngineTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val sink by lazy { MediaStoreSink(context) }
    private val resolver get() = context.contentResolver
    private val runId = UUID.randomUUID().toString().take(8)
    private val insertedUris = mutableListOf<Uri>()

    @After
    fun tearDown() {
        for (uri in insertedUris) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {}
        }
        insertedUris.clear()
    }

    @Test
    fun forwardRecording_encodesValidDecodableAudioWithGoertzelDetection() {
        val sampleRateHz = 16_000
        val toneHz = 1000.0
        val config = AudioConfig(sampleRateHz = sampleRateHz, channelCount = 1)
        val buffer = RingBuffer(capacityBytes = 80_000, bytesPerSecond = config.bytesPerSecond)

        val name = "blackbox_${runId}_goertzel_test.m4a"
        val engine = ForwardRecordingEngine(
            config = config,
            readSinceProvider = { cursor, maxBytes -> buffer.readSince(cursor, maxBytes) },
            writeCursorProvider = { buffer.writeCursor() },
            oldestCursorProvider = { buffer.oldestCursor() },
            gapsProvider = { emptyList() },
            sink = sink,
        )

        val startResult = engine.start(customDisplayName = name)
        assertTrue("start should succeed: $startResult", startResult is ForwardRecordingState.Recording)

        // Write 2 seconds of 1000 Hz tone in 50ms chunks
        val totalMillis = 2000L
        val chunkMillis = 50L
        val totalChunks = (totalMillis / chunkMillis).toInt()
        val toneChunk = ToneGenerator.tone(
            frequencyHz = toneHz,
            sampleRateHz = sampleRateHz,
            durationMillis = chunkMillis,
            channelCount = 1,
        )

        repeat(totalChunks) {
            buffer.write(toneChunk)
            Thread.sleep(10)
        }

        val stopResult = engine.stop()
        assertTrue("stop should succeed: $stopResult", stopResult is ForwardRecordingState.Success)

        // Locate file in MediaStore and decode
        val recordings = sink.queryRecordings()
        val row = recordings.firstOrNull { it.displayName == name }
        assertNotNull("recording row must exist in MediaStore", row)
        insertedUris += row!!.uri

        val tempFile = File.createTempFile("forward_test_read_", ".m4a", context.cacheDir)
        try {
            resolver.openInputStream(row.uri)!!.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            val decoded = AacDecodeSupport.decode(tempFile)
            assertEquals(sampleRateHz, decoded.sampleRateHz)
            assertEquals(1, decoded.channelCount)

            val energyTarget = GoertzelDetector.energyAt(decoded.pcm, toneHz, sampleRateHz, 1)
            val energyHalf = GoertzelDetector.energyAt(decoded.pcm, toneHz / 2.0, sampleRateHz, 1)
            val energyDouble = GoertzelDetector.energyAt(decoded.pcm, toneHz * 2.0, sampleRateHz, 1)

            assertTrue("target frequency energy ($energyTarget) must be high (> 200)", energyTarget > 200.0)
            assertTrue("sub-harmonic energy ($energyHalf) must be suppressed relative to target", energyHalf < energyTarget / 4.0)
            assertTrue("harmonic energy ($energyDouble) must be suppressed relative to target", energyDouble < energyTarget / 4.0)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun forwardRecording_timelineAlignmentWithInterruptionGaps() {
        val sampleRateHz = 16_000
        val toneHz = 1000.0
        val config = AudioConfig(sampleRateHz = sampleRateHz, channelCount = 1)
        val buffer = RingBuffer(capacityBytes = 160_000, bytesPerSecond = config.bytesPerSecond)

        val gaps = mutableListOf<PauseGap>()
        val name = "blackbox_${runId}_gap_test.m4a"

        val engine = ForwardRecordingEngine(
            config = config,
            readSinceProvider = { cursor, maxBytes -> buffer.readSince(cursor, maxBytes) },
            writeCursorProvider = { buffer.writeCursor() },
            oldestCursorProvider = { buffer.oldestCursor() },
            gapsProvider = { synchronized(gaps) { gaps.toList() } },
            sink = sink,
        )

        engine.start(customDisplayName = name)

        val toneChunk = ToneGenerator.tone(
            frequencyHz = toneHz,
            sampleRateHz = sampleRateHz,
            durationMillis = 50L,
            channelCount = 1,
        )

        // 1. Write 1 second of tone
        repeat(20) { buffer.write(toneChunk) }
        Thread.sleep(100)

        // 2. Inject a 1-second pause gap
        val gapStart = System.currentTimeMillis()
        val gapEnd = gapStart + 1000L
        synchronized(gaps) {
            gaps.add(PauseGap(gapStart, gapEnd))
        }
        Thread.sleep(100)

        // 3. Write another 1 second of tone
        repeat(20) { buffer.write(toneChunk) }
        Thread.sleep(100)

        val stopResult = engine.stop()
        assertTrue(stopResult is ForwardRecordingState.Success)

        val recordings = sink.queryRecordings()
        val row = recordings.firstOrNull { it.displayName == name }
        assertNotNull("recording row must exist", row)
        insertedUris += row!!.uri

        val tempFile = File.createTempFile("forward_gap_read_", ".m4a", context.cacheDir)
        try {
            resolver.openInputStream(row.uri)!!.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            val decoded = AacDecodeSupport.decode(tempFile)
            assertEquals(sampleRateHz, decoded.sampleRateHz)

            // Total duration should be ~3 seconds (1s tone + 1s silence + 1s tone)
            val totalDecodedDurationMillis = (decoded.pcm.size.toLong() * 1000L) / (sampleRateHz * 2)
            assertTrue(
                "decoded duration ($totalDecodedDurationMillis ms) must span ~3000ms",
                totalDecodedDurationMillis in 2700L..3500L,
            )
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun forwardRecording_concurrentSnapshotCapability() {
        val sampleRateHz = 16_000
        val config = AudioConfig(sampleRateHz = sampleRateHz, channelCount = 1)
        val buffer = RingBuffer(capacityBytes = 80_000, bytesPerSecond = config.bytesPerSecond)

        val name = "blackbox_${runId}_concurrent_snap.m4a"
        val engine = ForwardRecordingEngine(
            config = config,
            readSinceProvider = { cursor, maxBytes -> buffer.readSince(cursor, maxBytes) },
            writeCursorProvider = { buffer.writeCursor() },
            oldestCursorProvider = { buffer.oldestCursor() },
            gapsProvider = { emptyList() },
            sink = sink,
        )

        engine.start(customDisplayName = name)

        val chunk = ToneGenerator.tone(1000.0, sampleRateHz, 50L, 1)
        var snapshotsTaken = 0

        repeat(30) { index ->
            buffer.write(chunk)
            if (index % 5 == 0) {
                val snap = buffer.snapshot(500L)
                if (snap.data.isNotEmpty()) {
                    snapshotsTaken++
                }
            }
        }

        val stopResult = engine.stop()
        assertTrue(stopResult is ForwardRecordingState.Success)
        assertTrue("at least one concurrent snapshot should have succeeded", snapshotsTaken > 0)

        val recordings = sink.queryRecordings()
        val row = recordings.firstOrNull { it.displayName == name }
        assertNotNull("recording row must exist", row)
        insertedUris += row!!.uri
    }

    @Test
    fun forwardRecording_resourceCleanupAcrossSequentialSessions() {
        val sampleRateHz = 16_000
        val config = AudioConfig(sampleRateHz = sampleRateHz, channelCount = 1)
        val buffer = RingBuffer(capacityBytes = 80_000, bytesPerSecond = config.bytesPerSecond)

        repeat(3) { sessionIndex ->
            val name = "blackbox_${runId}_seq_${sessionIndex}.m4a"
            val engine = ForwardRecordingEngine(
                config = config,
                readSinceProvider = { cursor, maxBytes -> buffer.readSince(cursor, maxBytes) },
                writeCursorProvider = { buffer.writeCursor() },
                oldestCursorProvider = { buffer.oldestCursor() },
                gapsProvider = { emptyList() },
                sink = sink,
            )

            val startResult = engine.start(customDisplayName = name)
            assertTrue(startResult is ForwardRecordingState.Recording)

            val chunk = ToneGenerator.tone(1000.0, sampleRateHz, 50L, 1)
            repeat(5) { buffer.write(chunk) }

            val stopResult = engine.stop()
            assertTrue("session $sessionIndex should finish successfully", stopResult is ForwardRecordingState.Success)

            val row = sink.queryRecordings().firstOrNull { it.displayName == name }
            if (row != null) insertedUris += row.uri
        }
    }
}
