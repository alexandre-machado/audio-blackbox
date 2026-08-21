package cc.machado.audioblackbox.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream

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
class MediaStoreSink(private val context: Context) : ExportSink {

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

    private companion object {
        const val APP_SUBFOLDER = "Blackbox"
    }
}
