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

    override fun queryRecordings(): List<RecordingRow> = rows

    override fun delete(uri: Uri): Boolean {
        deleted += uri
        val before = rows.size
        rows = rows.filterNot { it.uri == uri }
        return rows.size != before
    }
}
