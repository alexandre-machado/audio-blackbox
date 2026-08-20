package cc.machado.audioblackbox.export

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream

/**
 * [ExportSink] backed by `MediaStore.Audio`, writing into `Music/Recordings` (issue #5 /
 * README Module 3 decision): visible to other apps, survives uninstall, and needs no legacy
 * storage permission on scoped storage (`minSdk` 29 already implies scoped storage).
 *
 * Uses `IS_PENDING` for the duration of the write, cleared only in [ExportTarget.commit], so a
 * half-written file is never visible to another app browsing `Music/Recordings`. On any failure
 * [ExportTarget.abort] deletes the row via `ContentResolver.delete` rather than leaving an
 * orphaned pending entry behind.
 */
class MediaStoreSink(private val context: Context) : ExportSink {

    override fun open(displayName: String): ExportTarget {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, MIME_TYPE_WAV)
            put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_PATH)
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

    private companion object {
        const val MIME_TYPE_WAV = "audio/wav"
        val RELATIVE_PATH = "${Environment.DIRECTORY_MUSIC}/Recordings"
    }
}
