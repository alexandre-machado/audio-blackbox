package cc.machado.audioblackbox

import android.app.Application
import cc.machado.audioblackbox.audio.QualityPreset
import cc.machado.audioblackbox.settings.DataStoreRetentionWindowPreferences
import cc.machado.audioblackbox.settings.RetentionWindowPreferences
import cc.machado.audioblackbox.widget.RecordingWidgetStateObserver
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
 * and reads a `DataStore` file that at most holds a handful of bytes (one Int and one String), so the blocking
 * window is a local disk read, not network or contended I/O. This is the ONE place in this
 * codebase allowed to block on `DataStore`; every other consumer (the dashboard's retention
 * selector) reads it reactively via [RetentionWindowPreferences.bufferDurationMinutesFlow].
 */
class AudioBlackboxApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val preferences: RetentionWindowPreferences = DataStoreRetentionWindowPreferences(this)
        PreloadedRetentionWindow.minutes = runBlocking { preferences.currentBufferDurationMinutes() }
        PreloadedRetentionWindow.preset = runBlocking { preferences.currentQualityPreset() }

        // Issue #275: reconciles any placed home-screen widget with real capture state on this
        // process's first collection -- closing the staleness gap that broke the removed Quick
        // Settings tile, but only once a new process actually starts. That window is bounded by
        // `updatePeriodMillis` (currently 30 min, longer under Doze) or the next process start,
        // whichever comes first -- not immediate on its own; see RecordingWidgetUpdater's doc for
        // the full mechanism (`@rev` review on PR #278, finding 2).
        RecordingWidgetStateObserver.start(this)

        // Issue #346: the durable error log's path needs a `Context.filesDir`, which
        // `RecorderService`'s companion object -- built the first time anything touches it,
        // possibly before any Service instance exists -- does not have (see e.g.
        // `AudioCaptureEngine`'s own companion-owned `_engine`). Setting it here, exactly once at
        // process start, mirrors `PreloadedRetentionWindow` above: a plain, `Application.onCreate`
        // -written holder is the only context-bearing thing guaranteed to run first.
        ErrorLogFileHolder.file = java.io.File(applicationContext.filesDir, "export_errors.log")
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

    @Volatile
    var preset: QualityPreset = QualityPreset.DEFAULT
}

/**
 * Holds the durable error log's file path (issue #346), set once by
 * [AudioBlackboxApplication.onCreate] -- see that call site's doc for why a plain top-level holder
 * is needed here, the same shape as [PreloadedRetentionWindow]. `null` (the default) only in a
 * plain JVM unit test that never runs [AudioBlackboxApplication]; every real production code path
 * that reads this (`RecorderService`'s companion) already treats a `null` error-log file as a safe
 * no-op -- see [cc.machado.audioblackbox.export.logExportError]'s and
 * [cc.machado.audioblackbox.export.readErrorLog]'s own docs.
 */
object ErrorLogFileHolder {
    @Volatile
    var file: java.io.File? = null
}
