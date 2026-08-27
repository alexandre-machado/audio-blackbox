package cc.machado.audioblackbox.export

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
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
 * How far the MediaStore `DURATION` column may sit from the PCM actually fed to the encoder before
 * the recording counts as truncated.
 *
 * Sized off the app's own documented AAC priming delay -- 2048 samples, pinned in issue #37, whose
 * ceiling across the supported sample rates is 128ms -- plus one AAC frame of rounding, then rounded
 * up to 200ms. The number is deliberately close to that ceiling: a looser bound (the 400ms this
 * started at, ~3x the ceiling) would let a real truncation regression anywhere in the 128-400ms range
 * pass green, which is precisely the range a codec or container change would land in. If a legitimate
 * change pushes real durations past this, raise it against a recomputed ceiling and say why -- do not
 * widen it to make a red test go away.
 */
private const val DURATION_TOLERANCE_MILLIS = 200L

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

    /**
     * Regression test for issue #140: the row `MediaStoreSink.openStreaming` early-commits (issue
     * #53) is never re-finalized when the recording stops, so `SIZE`/`DURATION` are left at
     * whatever the platform derived from the almost-empty file at open time.
     *
     * ## Oracle
     * After [ForwardRecordingEngine.stop] returns [ForwardRecordingState.Success], the MediaStore
     * row's `SIZE` must match the real file's byte count on disk exactly (queried straight back
     * from `ContentResolver`, not re-derived by this test), and `DURATION` must be within
     * [DURATION_TOLERANCE_MILLIS] of the known 3000ms of PCM actually fed to the encoder. Before the
     * fix, `SIZE` sits at whatever a near-empty file was at early-commit time and `DURATION` sits at
     * 0 -- both fail this assertion. A test that only checked the row existed (as every other test
     * in this file does) would have passed throughout this bug's entire life; this test would not.
     *
     * The refresh must also not corrupt the row it refreshes, which is what the last two assertions
     * are for. `MediaScannerConnection.scanFile` is being pointed at a path that is *already* an
     * indexed row, and a scan that fails to match it to the existing row inserts a second one and/or
     * re-attributes ownership to the scanner rather than this app. Both would be invisible to a
     * SIZE/DURATION check while breaking the gallery: a duplicate shows the recording twice, and a
     * lost `OWNER_PACKAGE_NAME` is the mechanism behind issue #59, where the app loses sight of its
     * own files. Note the row lookup below is deliberately `single`, not `firstOrNull` -- the latter
     * would happily pick the first of two duplicates and pass.
     */
    @Test
    fun forwardRecording_mediaStoreRowMatchesFinishedFileAfterStop() {
        val sampleRateHz = 16_000
        val toneHz = 1000.0
        val config = AudioConfig(sampleRateHz = sampleRateHz, channelCount = 1)
        val buffer = RingBuffer(capacityBytes = 200_000, bytesPerSecond = config.bytesPerSecond)

        val name = "blackbox_${runId}_refinalize_test.m4a"
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

        // Feed a known 3 seconds of PCM so the finished file is unambiguously non-trivial.
        val totalMillis = 3_000L
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

        // `single`, not `firstOrNull`: this both asserts the row exists and asserts the re-finalizing
        // scan did not insert a duplicate alongside it. It throws if there are 0 or 2+ matches.
        val matches = sink.queryRecordings().filter { it.displayName == name }
        assertEquals(
            "exactly one MediaStore row must carry this display name, found ${matches.size} " +
                "-- more than one means the re-finalizing scan inserted a duplicate row",
            1,
            matches.size,
        )
        val row = matches.single()
        insertedUris += row.uri

        // Ground truth: the real file's byte count on disk, read directly via ParcelFileDescriptor
        // -- independent of whatever MediaStore's SIZE column claims.
        val realSizeBytes = resolver.openFileDescriptor(row.uri, "r")!!.use { it.statSize }
        assertTrue("finished file must be substantially larger than an early-commit stub (${realSizeBytes}B)", realSizeBytes > 4_000L)

        assertEquals(
            "MediaStore SIZE (${row.sizeBytes}) must match the real on-disk file size ($realSizeBytes)",
            realSizeBytes,
            row.sizeBytes,
        )
        assertTrue(
            "MediaStore DURATION (${row.durationMillis}ms) must be within ${DURATION_TOLERANCE_MILLIS}ms " +
                "of the known ${totalMillis}ms recorded",
            row.durationMillis in
                (totalMillis - DURATION_TOLERANCE_MILLIS)..(totalMillis + DURATION_TOLERANCE_MILLIS),
        )

        // Ownership must survive the scan. MediaScannerConnection runs as the platform's media
        // process, so a scan that re-inserts rather than updates leaves the row owned by someone
        // else -- at which point this app can no longer see or delete its own recording (issue #59)
        // even though the file is still on disk. Read straight off the row, not through
        // RecordingRow, which does not project this column.
        val owner = resolver.query(
            row.uri,
            arrayOf(MediaStore.Audio.Media.OWNER_PACKAGE_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        assertEquals(
            "the re-finalizing scan must leave OWNER_PACKAGE_NAME as this app; losing it is the " +
                "mechanism behind issue #59",
            context.packageName,
            owner,
        )
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
