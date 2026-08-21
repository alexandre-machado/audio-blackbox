package cc.machado.audioblackbox.ui.gallery

import android.net.Uri
import cc.machado.audioblackbox.export.RecordingRow
import cc.machado.audioblackbox.export.RecordingsRepository

/** In-memory [RecordingsRepository] for [GalleryViewModel] instance tests -- [rows] is mutable so
 * a test can simulate a row disappearing outside the app (a file manager, the OS itself) between
 * two [queryRecordings] calls, which is exactly the "no phantom entries on refresh" guarantee
 * issue #7 requires. */
class FakeRecordingsRepository(initial: List<RecordingRow>) : RecordingsRepository {
    var rows: List<RecordingRow> = initial
    val deleted = mutableListOf<Uri>()

    /** When set, [delete] returns `false` without touching [rows] -- simulates
     * `MediaStoreSink.delete` failing on a row this app does not own (issue #59) via the
     * documented `false` contract. */
    var deleteSucceeds: Boolean = true

    /** When set, [delete] throws instead of returning -- simulates the interface contract being
     * violated by an unexpected exception, which [GalleryViewModel.onDeleteConfirmed] must still
     * not crash on (PR #61 review finding). */
    var deleteThrows: Boolean = false

    override fun queryRecordings(): List<RecordingRow> = rows

    override fun delete(uri: Uri): Boolean {
        deleted += uri
        if (deleteThrows) throw IllegalStateException("simulated delete failure")
        if (!deleteSucceeds) return false
        val before = rows.size
        rows = rows.filterNot { it.uri == uri }
        return rows.size != before
    }
}
