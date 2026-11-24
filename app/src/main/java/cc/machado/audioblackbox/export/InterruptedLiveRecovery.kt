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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

private val Context.liveRecoveryStore by preferencesDataStore(
    name = "interrupted_live_rows",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
        androidx.datastore.preferences.core.emptyPreferences()
    },
)

internal enum class RecoveryResult { SCANNED, STALE, RETRY }

/** The receipt is emitted only after our scanner result and journal consumption agree. */
internal fun recoverLiveRows(
    rows: Set<String>,
    refresh: (String) -> RecoveryResult,
    forget: (String) -> Unit,
): Map<String, RecoveryResult> = rows.associateWith { raw ->
    val result = refresh(raw)
    if (result != RecoveryResult.RETRY) forget(raw)
    result
}

/** Durable journal, written on the start worker before publication and the first audio frame.
 * Startup repairs torn Ogg tails before rescanning; it never deletes audio or changes ADTS.
 * Only the startup snapshot is serialized with registration; scanner IPC never holds that lock.
 * Active rows are excluded so startup cannot mistake this process's file for an interrupted one.
 * All entry points run off the capture and main threads.
 */
internal object InterruptedLiveRecovery {
    private val lock = Any()
    private val recoveryLock = Any()
    private val activeRows = mutableSetOf<String>()
    private val mutableResults = kotlinx.coroutines.flow.MutableStateFlow<Map<String, RecoveryResult>>(emptyMap())
    val results = mutableResults.asStateFlow()
    private val rowsKey = stringSetPreferencesKey("unfinished_rows")
    private var recovered = false

    fun recover(context: Context) = synchronized(recoveryLock) {
        if (recovered) return
        val app = context.applicationContext
        try {
            val rows = synchronized(lock) { pendingRows(app) - activeRows }
            mutableResults.value = recoverLiveRows(rows, { refresh(app, Uri.parse(it)) }) {
                forget(app, Uri.parse(it))
            }
            recovered = true
        } catch (e: Exception) {
            Log.w("LiveRecovery", "Recovery failures=1", e)
            // A later startup attempt may retry; Live registration never awaits this scan.
            throw e
        }
    }

    /** Read-only journal inspection. Scan receipts, not absence alone, prove recovery ran. */
    fun pendingRows(context: Context): Set<String> = runBlocking {
        context.applicationContext.liveRecoveryStore.data.first()[rowsKey].orEmpty()
    }

    fun register(context: Context, uri: Uri) = synchronized(lock) {
        runBlocking {
            context.applicationContext.liveRecoveryStore.edit { prefs ->
                prefs[rowsKey] = prefs[rowsKey].orEmpty() + uri.toString()
            }
        }
        activeRows.add(uri.toString())
        Unit
    }

    fun forget(context: Context, uri: Uri) = synchronized(lock) {
        runBlocking {
            context.applicationContext.liveRecoveryStore.edit { prefs ->
                prefs[rowsKey] = prefs[rowsKey].orEmpty() - uri.toString()
            }
        }
        activeRows.remove(uri.toString())
        Unit
    }

    /** Distinguishes scanner confirmation from stale entries and retryable failures.
     * MIME is provider-owned; using the queried alias preserves Ogg/Opus and ADTS normalization.
     */
    private fun refresh(context: Context, uri: Uri): RecoveryResult {
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return RecoveryResult.STALE
        val resolver = context.contentResolver
        return try {
            val metadata = resolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.OWNER_PACKAGE_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null else Array(4) { cursor.getString(it) }
            } ?: return RecoveryResult.STALE // A user-deleted file is not recreated.
            val name = metadata[2].orEmpty()
            if (metadata[3] != context.packageName || !name.startsWith("blackbox_") ||
                !(StreamingAdtsWriter.accepts(metadata[1].orEmpty(), name) ||
                    StreamingOpusWriter.accepts(metadata[1].orEmpty(), name))) return RecoveryResult.STALE
            val path = metadata[0] ?: return RecoveryResult.RETRY
            if (StreamingOpusWriter.accepts(metadata[1].orEmpty(), name) &&
                !StreamingAdtsWriter.accepts(metadata[1].orEmpty(), name)) {
                // rw preserves existing bytes; rwt would destroy the file before validation.
                val descriptor = resolver.openFileDescriptor(uri, "rw") ?: return RecoveryResult.RETRY
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    android.os.ParcelFileDescriptor.AutoCloseOutputStream(
                        android.os.ParcelFileDescriptor.dup(descriptor.fileDescriptor)
                    ).use { output ->
                        val repair = OggTailRepair.repair(input.channel, output.channel)
                        Log.i("LiveRecovery", "Repaired=${if (repair == OggTailRepair.Result.TRUNCATED) 1 else 0} " +
                            "invalidHeaders=${if (repair == OggTailRepair.Result.INVALID_HEADERS) 1 else 0} " +
                            "interiorDamage=${if (repair == OggTailRepair.Result.INTERIOR_DAMAGE) 1 else 0}")
                    }
                }
            }
            val done = CountDownLatch(1)
            var scanned = false
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(metadata[1])) { _, result ->
                scanned = result != null
                done.countDown()
            }
            val success = done.await(15, TimeUnit.SECONDS) && scanned
            if (!success) Log.w("LiveRecovery", "Interrupted row scan incomplete; retained for next startup")
            if (success) RecoveryResult.SCANNED else RecoveryResult.RETRY
        } catch (e: Exception) {
            Log.w("LiveRecovery", "Row recovery failures=1", e)
            RecoveryResult.RETRY
        }
    }
}
