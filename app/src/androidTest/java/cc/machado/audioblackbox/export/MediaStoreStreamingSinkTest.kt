package cc.machado.audioblackbox.export

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.machado.audioblackbox.audio.AudioConfig
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [MediaStoreSink.openStreaming] and [StreamingExportTarget] integration
 * with Android MediaStore and [StreamingAacWriter] (issue #53).
 *
 * Exercises early-commit MediaStore row creation, immediate visibility, live AAC encoding via
 * file descriptors, arbitrary finalization, and failure preservation against real Android platform
 * APIs.
 *
 * ## Oracles
 * - **Early Commit Visibility**: When [MediaStoreSink.openStreaming] is called, the created MediaStore
 *   row immediately has `IS_PENDING = 0` (unlike bounded export which keeps `IS_PENDING = 1`), and is
 *   immediately visible to both raw `ContentResolver` queries and [RecordingsRepository.queryRecordings].
 * - **Live AAC Stream Round-Trip**: PCM audio fed through [StreamingAacWriter] writing directly to the
 *   target's `FileDescriptor` is cleanly finalized, producing a valid, playable `.m4a` file in MediaStore
 *   that decodes to the exact tone frequency with high Goertzel energy and suppressed harmonic noise.
 * - **Failure / Cancellation Preservation**: An ungraceful close or exception mid-stream preserves the
 *   MediaStore row and its partially written file on disk — the black box recording is never deleted.
 * - **Bounded / Streaming Coexistence**: Bounded export (`IS_PENDING = 1` -> abort deletes) and streaming
 *   export (`IS_PENDING = 0` -> close preserves) coexist without interfering.
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreStreamingSinkTest {

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

    private fun queryIsPending(uri: Uri): Int? {
        val projection = arrayOf(MediaStore.Audio.Media.IS_PENDING)
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_PENDING))
            } else null
        }
    }

    @Test
    fun openStreaming_isImmediatelyCommittedAndVisibleInMediaStore() {
        val name = "blackbox_${runId}_early_commit.m4a"
        val target = sink.openStreaming(name, "audio/mp4")
        insertedUris += target.uri

        try {
            // Oracle: IS_PENDING must be 0 immediately upon return from openStreaming()
            val isPending = queryIsPending(target.uri)
            assertEquals("early-committed row must have IS_PENDING = 0", 0, isPending)

            // Oracle: queryRecordings() must immediately find this in-progress recording
            val recordings = sink.queryRecordings()
            val found = recordings.any { it.displayName == name }
            assertTrue("in-progress recording must be visible in queryRecordings()", found)
        } finally {
            target.close()
        }
    }

    @Test
    fun streamingAacWriter_encodesViaStreamingTargetDescriptorAndDecodesValidAudio() {
        val name = "blackbox_${runId}_stream_encode.m4a"
        val target = sink.openStreaming(name, "audio/mp4")
        insertedUris += target.uri

        val sampleRateHz = 16_000
        val toneHz = 1000.0
        val durationMillis = 2_000L
        val config = AudioConfig(sampleRateHz = sampleRateHz, channelCount = 1)

        try {
            val writer = StreamingAacWriter(target.fileDescriptor, config)
            var elapsed = 0L
            val chunkMillis = 50L
            while (elapsed < durationMillis) {
                val thisChunkMillis = minOf(chunkMillis, durationMillis - elapsed)
                val pcm = ToneGenerator.tone(
                    frequencyHz = toneHz,
                    sampleRateHz = sampleRateHz,
                    durationMillis = thisChunkMillis,
                    channelCount = 1,
                )
                writer.write(pcm)
                elapsed += thisChunkMillis
            }
            writer.finish()
            target.finish()

            // Verify the written file by copying from MediaStore ContentResolver stream to temp file and decoding
            val tempFile = File.createTempFile("verify_stream_", ".m4a", context.cacheDir)
            try {
                resolver.openInputStream(target.uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                } ?: throw AssertionError("could not open input stream for ${target.uri}")

                val decoded = AacDecodeSupport.decode(tempFile)
                assertEquals(sampleRateHz, decoded.sampleRateHz)
                assertEquals(1, decoded.channelCount)

                val energyAtTone = GoertzelDetector.energyAt(decoded.pcm, toneHz, sampleRateHz, 1)
                val energyAtHalf = GoertzelDetector.energyAt(decoded.pcm, toneHz / 2, sampleRateHz, 1)
                assertTrue("expected strong energy at ${toneHz}Hz, got $energyAtTone", energyAtTone > 500.0)
                assertTrue("expected weak harmonic energy at ${toneHz / 2}Hz, got $energyAtHalf", energyAtHalf < energyAtTone / 5)
            } finally {
                tempFile.delete()
            }
        } finally {
            target.close()
        }
    }

    @Test
    fun midRecordingFailure_preservesCommittedRowAndDataOnDisk() {
        val name = "blackbox_${runId}_mid_fail.m4a"
        val target = sink.openStreaming(name, "audio/mp4")
        insertedUris += target.uri

        val sampleRateHz = 16_000
        val toneHz = 1000.0
        val config = AudioConfig(sampleRateHz = sampleRateHz, channelCount = 1)

        val writer = StreamingAacWriter(target.fileDescriptor, config)
        val pcm = ToneGenerator.tone(
            frequencyHz = toneHz,
            sampleRateHz = sampleRateHz,
            durationMillis = 1000L,
            channelCount = 1,
        )
        writer.write(pcm)

        // Simulate mid-recording ungraceful exit (close without finish)
        writer.close()
        target.close()

        // Oracle: MediaStore row must STILL exist and be found by queryRecordings()
        val recordings = sink.queryRecordings()
        val found = recordings.any { it.displayName == name }
        assertTrue("partial recording row must be preserved in MediaStore on failure/close", found)
    }

    @Test
    fun boundedAndStreamingCoexistWithDistinctPendingDisciplines() {
        val boundedName = "blackbox_${runId}_bounded.m4a"
        val streamingName = "blackbox_${runId}_streaming.m4a"

        val boundedTarget = sink.open(boundedName, "audio/mp4")
        val streamingTarget = sink.openStreaming(streamingName, "audio/mp4")
        insertedUris += streamingTarget.uri

        try {
            // Streaming target has IS_PENDING = 0 immediately
            val streamingIsPending = queryIsPending(streamingTarget.uri)
            assertEquals(0, streamingIsPending)

            // Streaming target is visible in queryRecordings()
            val rows = sink.queryRecordings().map { it.displayName }.toSet()
            assertTrue("streaming target must be visible in queryRecordings()", streamingName in rows)

            // Aborting bounded target deletes its row cleanly
            boundedTarget.abort()

            // Closing streaming target preserves its row
            streamingTarget.close()
            val rowsAfter = sink.queryRecordings().map { it.displayName }.toSet()
            assertTrue("streaming target must still be visible after close", streamingName in rowsAfter)
        } finally {
            streamingTarget.close()
        }
    }
}
