package cc.machado.audioblackbox.export

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.liveRecoveryStore by preferencesDataStore(name = "interrupted_live_rows")

/** Durable journal, written before the first codec byte. Every entry is a published live row.
 * Startup only rescans: it never opens a writable descriptor, rewrites a tail, or deletes audio.
 * Serialized with registration so app/service startup cannot mistake this process's live file
 * for an interrupted recording. All entry points run off the capture and main threads.
 */
internal object InterruptedLiveRecovery {
    private val lock = Any()
    private val rowsKey = stringSetPreferencesKey("unfinished_rows")
    private var recovered = false

    fun recover(context: Context) = synchronized(lock) {
        if (recovered) return
        val app = context.applicationContext
        try {
            val rows = pendingRows(app)
            for (raw in rows) {
                val uri = Uri.parse(raw)
                if (refresh(app, uri)) forget(app, uri)
            }
            recovered = true
        } catch (e: Exception) {
            Log.w("LiveRecovery", "Interrupted recording metadata recovery failed; files preserved", e)
            // Do not mark complete: registration must retry before opening another session.
            throw e
        }
    }

    /** Read-only journal inspection also lets the kill test distinguish our recovery from an
     * automatic MediaProvider scan on descriptor death. Never exposes audio contents. */
    fun pendingRows(context: Context): Set<String> = runBlocking {
        context.applicationContext.liveRecoveryStore.data.first()[rowsKey].orEmpty()
    }

    fun register(context: Context, uri: Uri) = synchronized(lock) {
        recover(context)
        runBlocking {
            context.applicationContext.liveRecoveryStore.edit { prefs ->
                prefs[rowsKey] = prefs[rowsKey].orEmpty() + uri.toString()
            }
        }
        Unit
    }

    fun forget(context: Context, uri: Uri) = synchronized(lock) {
        runBlocking {
            context.applicationContext.liveRecoveryStore.edit { prefs ->
                prefs[rowsKey] = prefs[rowsKey].orEmpty() - uri.toString()
            }
        }
        Unit
    }

    /** Returns true only after the scanner confirms a row, or if the journal entry is stale.
     * MIME is provider-owned; using the queried alias preserves Ogg/Opus and ADTS normalization.
     */
    private fun refresh(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return true
        val resolver = context.contentResolver
        return try {
            val metadata = resolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.OWNER_PACKAGE_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null else Array(4) { cursor.getString(it) }
            } ?: return true // A user-deleted file is not recreated.
            val name = metadata[2].orEmpty()
            if (metadata[3] != context.packageName || !name.startsWith("blackbox_") ||
                !(StreamingAdtsWriter.accepts(metadata[1].orEmpty(), name) ||
                    StreamingOpusWriter.accepts(metadata[1].orEmpty(), name))) return true
            val path = metadata[0] ?: return false
            val done = CountDownLatch(1)
            var scanned = false
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(metadata[1])) { _, result ->
                scanned = result != null
                done.countDown()
            }
            val success = done.await(15, TimeUnit.SECONDS) && scanned
            if (!success) Log.w("LiveRecovery", "Interrupted row scan incomplete; retained for next startup")
            success
        } catch (e: Exception) {
            Log.w("LiveRecovery", "Interrupted row could not be scanned; file preserved", e)
            false
        }
    }
}
