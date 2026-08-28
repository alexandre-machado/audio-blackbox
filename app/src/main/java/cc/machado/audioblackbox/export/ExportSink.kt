package cc.machado.audioblackbox.export

import android.net.Uri
import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException
import java.io.OutputStream

/**
 * Seam over the concrete export destination (MediaStore in production, see [MediaStoreSink]) so
 * [ExportEngine] and its orchestration logic are unit-testable without Android (issue #5).
 */
interface ExportSink {

    /**
     * Opens a new pending destination named [displayName], declared as [mimeType] (issue #32:
     * this varies by [PayloadEncoder], so it is no longer a sink-level constant). The returned
     * [ExportTarget] must be created in a "pending"/not-yet-visible state if the underlying store
     * supports one, so a reader never observes a half-written file.
     *
     * @throws IOException if the destination cannot be created (store rejected the insert, no
     *   space, permission denied).
     */
    fun open(displayName: String, mimeType: String): ExportTarget
}

/**
 * One in-flight export destination. Exactly one of [commit]/[abort] must be called after
 * [outputStream] is done being written to (typically after closing it) -- never both, never
 * neither.
 */
interface ExportTarget {
    val outputStream: OutputStream

    /** Marks the destination visible/complete (e.g. clears `IS_PENDING`). Call only after every
     * byte has been written successfully. */
    fun commit()

    /** Deletes the (possibly partially written) destination. Call on any failure or
     * cancellation so a half-written file is never left orphaned. */
    fun abort()
}

/**
 * Seam over live streaming export destinations for long-running forward recordings (issue #53).
 */
interface StreamingExportSink {

    /**
     * Opens a new live streaming destination named [displayName], declared as [mimeType],
     * with early commit (`IS_PENDING = 0`) so that in-progress recordings are immediately
     * visible in MediaStore and Gallery while still being written.
     *
     * @throws IOException if the destination cannot be created (insert rejected, disk full, permission denied).
     */
    fun openStreaming(displayName: String, mimeType: String): StreamingExportTarget
}

/**
 * An active destination for long-running streaming recordings (issue #53).
 *
 * Unlike [ExportTarget] (which stays `IS_PENDING = 1` until committed at the very end of a bounded
 * snapshot export), a [StreamingExportTarget] commits the row early to MediaStore upon creation so
 * the file is immediately visible in MediaStore and the Gallery while audio continues to be appended.
 *
 * ## Lifecycle & Failure Discipline (issue #53)
 * - On normal completion, [finish] finalizes the container, flushes underlying file descriptors/streams,
 *   and releases all resources while preserving the committed row.
 * - On cancellation, error, or unexpected termination (e.g. process death, mid-stream exception,
 *   storage exhaustion), [close] safely releases open file handles without deleting the row:
 *   partial recorded audio is preserved on disk for the black box recording.
 */
interface StreamingExportTarget : Closeable, AutoCloseable {
    /** The content URI of the MediaStore row. */
    val uri: Uri

    /** The seekable file descriptor backing the early-committed destination. */
    val fileDescriptor: FileDescriptor

    /** An output stream to write directly to the target destination if needed. */
    val outputStream: OutputStream

    /**
     * Finalizes the streaming export target.
     */
    fun finish()

    /**
     * Re-finalizes this row's `SIZE`/`DURATION` metadata to reflect what has actually been written
     * so far (issue #140). Issue #53's early commit clears `IS_PENDING` as soon as the row is
     * created, before any audio is written, so the platform derives `SIZE`/`DURATION` from an
     * almost-empty file at that point and never revisits them on its own -- nothing else in this
     * codebase called `scanFile`/`MediaScannerConnection`/`ContentResolver.notifyChange` for this
     * row before this method existed. `SIZE`/`DURATION` are platform-computed columns for audio
     * rows: writing them directly via `ContentValues` is silently dropped (confirmed empirically),
     * so implementations must trigger the platform's own recompute rather than set the values
     * themselves. Safe to call repeatedly while still writing (mid-recording progress -- `DURATION`
     * will read back as stale or 0 until [finish] writes the container's duration atom, but `SIZE`
     * still reflects real bytes on disk) and once more after [finish] (the authoritative, final
     * value for both). Must never throw -- a metadata refresh failing is not a reason to fail or
     * abort an otherwise-successful recording; implementations swallow their own errors and leave
     * the row as it was on failure.
     */
    fun refinalizeMetadata()

    /**
     * Closes underlying descriptors and streams safely without deleting the committed destination.
     */
    override fun close()
}
