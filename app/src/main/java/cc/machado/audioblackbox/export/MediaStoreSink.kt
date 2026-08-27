package cc.machado.audioblackbox.export

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One row read back from `MediaStore` for one of this app's own exported recordings (issue #7).
 * Carries only what [RecordingsRepository.queryRecordings] reads straight off the row -- duration
 * and size are populated by the platform itself (confirmed on-device, see issue #7's device pass),
 * so nothing here re-derives them by opening or parsing the file.
 */
data class RecordingRow(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMillis: Long,
    val dateAddedMillis: Long,
)

/**
 * Seam over the app's `MediaStore` recordings so the gallery UI layer (issue #7) never queries or
 * deletes `MediaStore` rows itself -- [MediaStoreSink] is the only class that knows the collection,
 * projection, and filter, the same way it is already the only class [ExportSink] work goes through
 * to write one.
 */
interface RecordingsRepository {
    /** Every row whose `DISPLAY_NAME` carries this app's own `blackbox_` prefix, across the whole
     * `MediaStore.Audio.Media` collection -- deliberately not scoped to a `RELATIVE_PATH`, so this
     * finds recordings in all three locations this app has ever written to
     * (`Recordings/Blackbox/`, `Music/Blackbox/`, and the legacy pre-#33 `Music/Recordings/`)
     * without needing to know about any of them individually, and without silently missing a
     * fourth if the destination ever moves again. Order is unspecified -- callers sort. */
    fun queryRecordings(): List<RecordingRow>

    /** Deletes the row at [uri]. Returns `true` if a row was actually removed, `false` if the
     * delete failed -- including a lack of ownership over that row (`SecurityException`/
     * `RecoverableSecurityException` on API 29+, e.g. after a reinstall reset `MediaStore`'s
     * `OWNER_PACKAGE_NAME` for that row -- see issue #59). Never throws. */
    fun delete(uri: Uri): Boolean
}

/**
 * [ExportSink] backed by `MediaStore.Audio`, writing into a per-app subfolder the way the
 * platform's own recorders do (issue #33): visible to other apps, survives uninstall, and needs
 * no legacy storage permission on scoped storage (`minSdk` 29 already implies scoped storage).
 *
 * ## Destination folder (issue #33)
 * The top-level `Recordings/` directory is only recognized by `MediaStore` from **API 31**
 * (Android 12) onward -- it does not exist as a valid `RELATIVE_PATH` root below that, confirmed
 * against the S25's own `Recordings/Voice Recorder/` and `Recordings/Call/` folders, which is
 * exactly the convention this class now follows: `Recordings/Blackbox/`. `minSdk` is 29
 * deliberately (see `AudioConfig`/issue #3, `foregroundServiceType="microphone"`), so API 29-30
 * need a fallback that predates `Recordings/`: **`Music/Blackbox/`**, chosen over inventing a new
 * top-level directory because `Music/` is a real, always-valid `MediaStore` root on every
 * supported API level and keeps the fallback's own per-app subfolder convention identical to the
 * API 31+ path -- only the parent changes. This is the same root the app already used
 * pre-issue-#33 (`Music/Recordings/`); nothing here migrates or deletes files already sitting
 * there -- those belong to the user and are left exactly where they are.
 *
 * The choice is made from [Build.VERSION.SDK_INT] -- the *running* OS -- never from `targetSdk`
 * or a build flag, so a single APK does the right thing on every device it actually runs on.
 *
 * Uses `IS_PENDING` for the duration of the write, cleared only in [ExportTarget.commit], so a
 * half-written file is never visible to another app browsing the destination folder. On any
 * failure [ExportTarget.abort] deletes the row via `ContentResolver.delete` rather than leaving an
 * orphaned pending entry behind -- this is unchanged by issue #32/#33's other changes.
 */
class MediaStoreSink(private val context: Context) : ExportSink, StreamingExportSink, RecordingsRepository {

    override fun openStreaming(displayName: String, mimeType: String): StreamingExportTarget {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath())
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore insert rejected for $displayName")

        val pfd = try {
            resolver.openFileDescriptor(uri, "rwt")
                ?: resolver.openFileDescriptor(uri, "rw")
                ?: throw IOException("openFileDescriptor returned null for $uri")
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        } catch (e: SecurityException) {
            resolver.delete(uri, null, null)
            throw IOException("openFileDescriptor denied for $uri", e)
        }

        // Early commit (issue #53): clear IS_PENDING immediately so the recording in progress is
        // visible in MediaStore and Gallery while still being written.
        try {
            val earlyCommit = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            resolver.update(uri, earlyCommit, null, null)
        } catch (e: Exception) {
            try { pfd.close() } catch (_: Exception) {}
            resolver.delete(uri, null, null)
            throw IOException("Failed to early-commit MediaStore row for $uri", e)
        }

        return object : StreamingExportTarget {
            private var isClosed = false
            override val uri: Uri = uri
            override val fileDescriptor: FileDescriptor = pfd.fileDescriptor
            override val outputStream: OutputStream by lazy {
                FileOutputStream(pfd.fileDescriptor)
            }

            override fun finish() {
                close()
            }

            // See StreamingExportTarget.refinalizeMetadata's doc (issue #140). SIZE and DURATION are
            // platform-computed columns for audio rows -- confirmed empirically (see the PR
            // description's reproduction) that writing them directly via ContentValues.update() is
            // silently dropped, matching the documented behavior of the other computed columns
            // (WIDTH/HEIGHT/etc). The platform's own mechanism to recompute them without disturbing
            // IS_PENDING (unlike a 1->0 toggle, which would also make an in-progress recording
            // transiently vanish from other apps' queries, undermining issue #53) is
            // MediaScannerConnection.scanFile against the row's real on-disk path, awaited with a
            // bounded latch rather than a sleep. Never throws -- a failed refresh here must not fail
            // an otherwise-successful recording.
            //
            // Every failure path logs at W. Best-effort must not mean invisible: the symptom of this
            // refresh silently not happening is *exactly* issue #140 -- a saved recording showing the
            // wrong size in the gallery -- which is indistinguishable from the bug this method exists
            // to fix. Without these logs a recurrence would be unreportable, since the user sees only
            // a wrong number and there is nothing on the device to correlate it with. Logcat is the
            // right channel and the only one: this app deliberately has no network permission and no
            // analytics (issue #119), so nothing here leaves the device.
            override fun refinalizeMetadata() {
                try {
                    val dataPath = resolver.query(
                        uri,
                        arrayOf(MediaStore.Audio.Media.DATA),
                        null, null, null,
                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

                    if (dataPath == null) {
                        // The row vanished, or the platform returned no DATA path for it. Either way
                        // there is nothing to hand the scanner, so SIZE/DURATION stay as they were.
                        Log.w(TAG, "refinalizeMetadata(): no DATA path for the row, size/duration left stale")
                        return
                    }

                    val latch = CountDownLatch(1)
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(dataPath),
                        arrayOf(StreamingAacWriter.MIME_TYPE_M4A),
                    ) { _, _ -> latch.countDown() }
                    if (!latch.await(REFINALIZE_SCAN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                        // The scan may still land after this returns -- the timeout bounds how long
                        // the drain thread waits, not the scanner. Logged because if it routinely
                        // times out, the row is routinely stale and the bound is the thing to revisit.
                        Log.w(TAG, "refinalizeMetadata(): scan did not report back within ${REFINALIZE_SCAN_TIMEOUT_MILLIS}ms")
                    }
                } catch (e: Exception) {
                    // Best-effort: leave the row as it was rather than fail the recording over a
                    // metadata refresh (see doc).
                    Log.w(TAG, "refinalizeMetadata(): refresh failed, size/duration left stale", e)
                }
            }

            override fun close() {
                if (isClosed) return
                isClosed = true
                try {
                    pfd.close()
                } catch (_: Exception) {}
            }
        }
    }

    override fun queryRecordings(): List<RecordingRow> {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        // See RecordingsRepository.queryRecordings's doc: filtered by this app's own filename
        // prefix across the *entire* collection, not by RELATIVE_PATH -- this is what finds
        // recordings in all three locations (current API 31+, current API 29-30 fallback, and the
        // legacy pre-#33 folder) with one query instead of three, and without this class needing a
        // fourth branch if the destination ever moves again (issue #7).
        //
        val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf(APP_FILE_PREFIX_LIKE_PATTERN)

        val rows = mutableListOf<RecordingRow>()
        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameCol) ?: continue
                // Defense in depth on top of the escaped LIKE above, and the part of this that is
                // actually unit-testable without Android (see MediaStoreSinkFilterTest): even if
                // the SQL selection above were ever weakened back to an unescaped pattern, a row
                // that doesn't carry the literal prefix is still never added to the result.
                if (!matchesRecordingPrefix(displayName)) continue
                val id = cursor.getLong(idCol)
                rows += RecordingRow(
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = displayName,
                    // MIME type is read straight from this row, per file -- never a single
                    // hardcoded constant (issue #7: a .wav shared as audio/mp4 fails to open).
                    mimeType = cursor.getString(mimeCol) ?: DEFAULT_MIME_TYPE,
                    sizeBytes = cursor.getLong(sizeCol),
                    durationMillis = cursor.getLong(durationCol),
                    dateAddedMillis = cursor.getLong(dateAddedCol) * 1000L,
                )
            }
        }
        return rows
    }

    /** Never throws (see [RecordingsRepository.delete]'s doc): a row this app does not own --
     * most likely one of this feature's own target legacy `Music/Recordings/` rows after a
     * reinstall reset `MediaStore` ownership (issue #59), not a hostile foreign file -- throws
     * `RecoverableSecurityException` (a `SecurityException` subtype) on API 29+ instead of simply
     * failing the delete. Caught here the same way [open] already catches `SecurityException` on
     * the write path, and reported as a plain `false` rather than crashing (PR #61 review,
     * `@sec`/`@rev` finding) -- [GalleryViewModel] surfaces that as a visible error, never a
     * silent no-op (issue #29's rule). */
    override fun delete(uri: Uri): Boolean =
        try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (e: SecurityException) {
            false
        }

    override fun open(displayName: String, mimeType: String): ExportTarget {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath())
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore insert rejected for $displayName")

        val stream = try {
            resolver.openOutputStream(uri) ?: throw IOException("openOutputStream returned null for $uri")
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        } catch (e: SecurityException) {
            resolver.delete(uri, null, null)
            throw IOException("openOutputStream denied for $uri", e)
        }

        return object : ExportTarget {
            override val outputStream: OutputStream = stream

            override fun commit() {
                val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }

            override fun abort() {
                resolver.delete(uri, null, null)
            }
        }
    }

    /** See class doc: `Recordings/` is only a valid `MediaStore` root from API 31; API 29-30 fall
     * back to `Music/Blackbox/`. Decided from the runtime OS, not `targetSdk`/a build flag. */
    private fun relativePath(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "${Environment.DIRECTORY_RECORDINGS}/$APP_SUBFOLDER"
        } else {
            "${Environment.DIRECTORY_MUSIC}/$APP_SUBFOLDER"
        }

    companion object {
        private const val APP_SUBFOLDER = "Blackbox"

        // Matches ExportEngine.filenameFor's own "blackbox_<timestamp>_<window>min.<ext>" pattern
        // (unchanged by issue #32/#33 -- only the sink/encoder/location moved), so this same
        // prefix already covers every file this app has ever written, .wav and .m4a alike.
        private const val APP_FILE_PREFIX = "blackbox_"
        private const val APP_FILE_PREFIX_LIKE_PATTERN = "blackbox_%"

        private const val DEFAULT_MIME_TYPE = "application/octet-stream"

        private const val TAG = "MediaStoreSink"

        /** Bound on how long [refinalizeMetadata] waits for `MediaScannerConnection`'s async
         * callback before giving up -- this runs on the forward-recording drain thread, never the
         * audio capture thread, so blocking it briefly does not risk dropped audio (issue #140). */
        private const val REFINALIZE_SCAN_TIMEOUT_MILLIS = 3_000L

        /** Pure oracle for "is this display name actually this app's own recording" -- the plain
         * Kotlin equivalent of the escaped `LIKE` selection [queryRecordings] uses, kept as its
         * own function so it is unit-testable without Android (see `MediaStoreSinkFilterTest`),
         * unlike the `ContentResolver` query itself. */
        fun matchesRecordingPrefix(displayName: String): Boolean = displayName.startsWith(APP_FILE_PREFIX)
    }
}
