package cc.machado.audioblackbox

import android.app.Application
import cc.machado.audioblackbox.settings.DataStoreRetentionWindowPreferences
import cc.machado.audioblackbox.settings.RetentionWindowPreferences
import kotlinx.coroutines.runBlocking

/**
 * Preloads the persisted retention window (issue #45) synchronously, once, before anything else
 * in the process runs -- so [cc.machado.audioblackbox.service.RecorderService]'s companion
 * object, which is a plain `val`/`var`-backed singleton built the first time anything touches it
 * (not something with its own suspend-friendly construction hook), can build its very first
 * `AudioConfig` from the real persisted value instead of always starting at
 * [cc.machado.audioblackbox.audio.AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES] and only picking up
 * the real value on a later, asynchronous read.
 *
 * `runBlocking` here is deliberate and narrowly scoped: this runs once per process, in
 * `Application.onCreate` -- before any Activity/Service exists to block a user-visible frame --
 * and reads a `DataStore` file that at most holds a handful of bytes (one Int), so the blocking
 * window is a local disk read, not network or contended I/O. This is the ONE place in this
 * codebase allowed to block on `DataStore`; every other consumer (the dashboard's retention
 * selector) reads it reactively via [RetentionWindowPreferences.bufferDurationMinutesFlow].
 */
class AudioBlackboxApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val preferences: RetentionWindowPreferences = DataStoreRetentionWindowPreferences(this)
        PreloadedRetentionWindow.minutes = runBlocking { preferences.currentBufferDurationMinutes() }
    }
}

/**
 * Holds the retention window value [AudioBlackboxApplication] preloaded, for
 * [cc.machado.audioblackbox.service.RecorderService]'s companion object to read at its own,
 * later, first-touch initialization. A plain top-level `var` (not a `StateFlow`/DataStore
 * reference itself) because this is read exactly once, synchronously, at that companion object's
 * class-initialization time -- see [cc.machado.audioblackbox.service.RecorderService]'s companion
 * doc. Any *subsequent* change to the retention window goes through
 * [cc.machado.audioblackbox.service.RecorderService.rebuildEngineIfIdle] instead, never back
 * through this holder.
 */
object PreloadedRetentionWindow {
    @Volatile
    var minutes: Int = cc.machado.audioblackbox.audio.AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES
}
