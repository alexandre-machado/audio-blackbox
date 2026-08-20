package cc.machado.audioblackbox.export

import java.io.IOException
import java.io.OutputStream

/**
 * Seam over the concrete export destination (MediaStore in production, see [MediaStoreSink]) so
 * [ExportEngine] and its orchestration logic are unit-testable without Android (issue #5).
 */
interface ExportSink {

    /**
     * Opens a new pending destination named [displayName]. The returned [ExportTarget] must be
     * created in a "pending"/not-yet-visible state if the underlying store supports one, so a
     * reader never observes a half-written file.
     *
     * @throws IOException if the destination cannot be created (store rejected the insert, no
     *   space, permission denied).
     */
    fun open(displayName: String): ExportTarget
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
