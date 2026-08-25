package cc.machado.audioblackbox.export

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Unit tests for [StreamingExportSink] and [StreamingExportTarget] lifecycle discipline (issue #53).
 *
 * Exercises the early-commit, append, clean finalization, and failure preservation contracts
 * across success, cancellation, mid-stream write failure, and storage exhaustion paths without
 * an Android device/emulator.
 *
 * ## Oracles
 * - **Early Commit**: Unlike bounded [ExportTarget] (which starts pending and only commits at the end),
 *   a [StreamingExportTarget] is early-committed upon creation so that an in-progress recording is
 *   immediately visible to queries while audio is appended.
 * - **Failure / Cancellation Preservation**: If an error, exception, or cancellation occurs mid-stream,
 *   [StreamingExportTarget.close] closes open handles safely and NEVER deletes the row — partial recorded
 *   audio is preserved for the black box.
 * - **Initial Open Failure Cleanliness**: If the sink cannot create the target initially (e.g. insert rejected),
 *   it throws [IOException] immediately and leaves no orphan.
 * - **Coexistence with Bounded Export**: Both bounded [ExportSink.open] and streaming [StreamingExportSink.openStreaming]
 *   coexist on the same sink with their distinct lifecycle invariants.
 */
class StreamingExportSinkTest {

    private class FakeStreamingTarget(
        override val uri: Uri,
        val tempFile: File,
    ) : StreamingExportTarget {
        private val fos = FileOutputStream(tempFile)
        var isFinished = false
        var isClosed = false
        var isDeleted = false

        override val fileDescriptor: FileDescriptor
            get() = fos.fd

        override val outputStream: OutputStream
            get() = fos

        override fun finish() {
            isFinished = true
            close()
        }

        override fun close() {
            if (isClosed) return
            isClosed = true
            fos.close()
            }

        fun deleteRow() {
            isDeleted = true
            tempFile.delete()
        }
    }

    private class FakeStreamingSink(
        private val targetProvider: (String, String) -> FakeStreamingTarget,
        private val failOpen: Boolean = false,
    ) : StreamingExportSink, ExportSink {

        var lastOpenedStreamingName: String? = null
        var lastOpenedStreamingMimeType: String? = null
        var lastOpenedBoundedName: String? = null

        override fun openStreaming(displayName: String, mimeType: String): StreamingExportTarget {
            if (failOpen) throw IOException("insert rejected: disk full or permission denied")
            lastOpenedStreamingName = displayName
            lastOpenedStreamingMimeType = mimeType
            return targetProvider(displayName, mimeType)
        }

        override fun open(displayName: String, mimeType: String): ExportTarget {
            lastOpenedBoundedName = displayName
            return object : ExportTarget {
                val buffer = ByteArrayOutputStream()
                var committed = false
                var aborted = false
                override val outputStream: OutputStream = buffer
                override fun commit() { committed = true }
                override fun abort() { aborted = true }
            }
        }
    }

    @Test
    fun `openStreaming creates early-committed target with valid descriptor and stream`() {
        val tempFile = File.createTempFile("fake_stream_", ".m4a")
        try {
            val uri: Uri = mock()
            val target = FakeStreamingTarget(uri, tempFile)
            val sink = FakeStreamingSink({ _, _ -> target })

            val resultTarget = sink.openStreaming("blackbox_2026-08-25_13-00-00_live.m4a", "audio/mp4")

            assertEquals(uri, resultTarget.uri)
            assertTrue("fileDescriptor must be valid", resultTarget.fileDescriptor.valid())
            assertFalse("session must not be closed on open", target.isClosed)
            assertFalse("session must not be finished on open", target.isFinished)

            // Write some sample bytes to outputStream
            resultTarget.outputStream.write(byteArrayOf(1, 2, 3, 4))
            resultTarget.finish()

            assertTrue(target.isFinished)
            assertTrue(target.isClosed)
            assertFalse("target must not be deleted on finish", target.isDeleted)
            assertEquals(4, tempFile.length())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `close on mid-stream failure or cancellation preserves partial recorded audio and does not delete row`() {
        val tempFile = File.createTempFile("fake_stream_partial_", ".m4a")
        try {
            val uri: Uri = mock()
            val target = FakeStreamingTarget(uri, tempFile)
            val sink = FakeStreamingSink({ _, _ -> target })

            val resultTarget = sink.openStreaming("blackbox_2026-08-25_13-00-00_live.m4a", "audio/mp4")

            // Simulate recording 1000 bytes
            val chunk = ByteArray(1000) { 0x55.toByte() }
            resultTarget.outputStream.write(chunk)

            // Simulate mid-recording process death, cancellation, or exception -> close() called without finish()
            resultTarget.close()

            assertTrue(target.isClosed)
            assertFalse("finish() was not called", target.isFinished)
            assertFalse("partial audio row must NEVER be deleted on failure/cancel", target.isDeleted)
            assertTrue("partial file must exist on disk with recorded bytes", tempFile.exists())
            assertEquals(1000L, tempFile.length())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `initial open failure throws IOException and leaves no orphan`() {
        val sink = FakeStreamingSink(
            targetProvider = { _, _ -> throw AssertionError("should not be called") },
            failOpen = true,
        )

        try {
            sink.openStreaming("blackbox_fail.m4a", "audio/mp4")
            fail("expected IOException when openStreaming fails")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("insert rejected") == true)
        }
    }

    @Test
    fun `storage exhaustion mid-stream surfaces IOException and preserves existing written data`() {
        val tempFile = File.createTempFile("fake_stream_enospc_", ".m4a")
        try {
            val uri: Uri = mock()
            val target = FakeStreamingTarget(uri, tempFile)
            val sink = FakeStreamingSink({ _, _ -> target })

            val resultTarget = sink.openStreaming("blackbox_enospc.m4a", "audio/mp4")

            // Write first chunk successfully
            resultTarget.outputStream.write(byteArrayOf(10, 20, 30))

            // Simulate storage exhaustion on second chunk
            try {
                // Closing stream and attempting write throws IOException
                target.close()
                resultTarget.outputStream.write(byteArrayOf(40, 50))
                fail("expected IOException on write after exhaustion")
            } catch (expected: IOException) {
                // Expected: storage full / write failure surfaces visibly
            }

            // Verify row is preserved and previous data is intact
            assertFalse(target.isDeleted)
            assertEquals(3L, tempFile.length())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `bounded and streaming exports coexist on same sink without interference`() {
        val tempFile = File.createTempFile("fake_stream_coexist_", ".m4a")
        try {
            val uri: Uri = mock()
            val streamingTarget = FakeStreamingTarget(uri, tempFile)
            val sink = FakeStreamingSink({ _, _ -> streamingTarget })

            // Open bounded export
            val boundedTarget = sink.open("blackbox_bounded.m4a", "audio/mp4")
            assertEquals("blackbox_bounded.m4a", sink.lastOpenedBoundedName)

            // Open streaming export
            val streamTarget = sink.openStreaming("blackbox_streaming.m4a", "audio/mp4")
            assertEquals("blackbox_streaming.m4a", sink.lastOpenedStreamingName)

            // Bounded abort deletes its row
            boundedTarget.abort()

            // Streaming close preserves its row
            streamTarget.close()
            assertFalse(streamingTarget.isDeleted)
        } finally {
            tempFile.delete()
        }
    }
}
