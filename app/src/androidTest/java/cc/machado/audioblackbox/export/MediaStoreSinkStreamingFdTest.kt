package cc.machado.audioblackbox.export

import android.provider.MediaStore
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #378: the descriptor [MediaStoreSink.openStreaming] hands to `MediaMuxer` must support
 * `fstat()` and `ftruncate()` after the early commit, not just `write()`.
 *
 * ## Oracle
 * Clearing `IS_PENDING` renames the file on disk from `.pending-<expiry>-<name>` to `<name>`. On
 * the S25 (Android 16), a descriptor opened *before* that rename keeps accepting writes, but
 * `fstat`/`ftruncate` on it fail with EIO, and `MediaMuxer.stop()` then fails with ERROR_IO
 * (-1004) because it ftruncates its pre-allocated tail. If `openStreaming` goes back to handing
 * out the descriptor it opened before the early commit, the `fstat`/`ftruncate` calls below throw
 * `ErrnoException` on that device. Mutation-verified on PR #415: this test failed with
 * `fstat failed: ENOENT`, and `ForwardRecordingEngineTest` failed 5/5 with the production
 * `ftruncate err: EIO` / `stop() err: -1004` signature. (The errno differs between a bare fstat
 * and MPEG4Writer's ftruncate; both are the stale pre-rename descriptor.)
 *
 * Tier note: the API 30 CI emulator passed `ForwardRecordingEngineTest` with the defect present,
 * so its FUSE does not reproduce this. It is a Tier 2 (S25) regression guard first.
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreSinkStreamingFdTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun openStreaming_descriptorSupportsFstatAndFtruncateAfterEarlyCommit() {
        val sink = MediaStoreSink(context)
        val target = sink.openStreaming("blackbox_fdtest_${System.nanoTime()}.m4a", StreamingAacWriter.MIME_TYPE_M4A)
        try {
            // Precondition: the early commit happened, i.e. the row is published, not pending.
            val pending = context.contentResolver.query(
                target.uri,
                arrayOf(MediaStore.Audio.Media.IS_PENDING, MediaStore.Audio.Media.DATA),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getInt(0) to c.getString(1) else null }
            assertNotNull("the streaming row must exist", pending)
            assertEquals("the row must be early-committed (issue #53)", 0, pending!!.first)
            assertFalse("the published file must not carry the .pending- prefix: ${pending.second}", pending.second.contains("/.pending-"))

            val fd = target.fileDescriptor
            FileOutputStream(fd).write(ByteArray(8192) { 1 })
            assertEquals("fstat after write", 8192L, Os.fstat(fd).st_size)
            Os.ftruncate(fd, 4096L) // what MediaMuxer.stop() does to its pre-allocated tail
            assertEquals("fstat after ftruncate", 4096L, Os.fstat(fd).st_size)
        } finally {
            target.close()
            sink.delete(target.uri)
        }
    }
}
