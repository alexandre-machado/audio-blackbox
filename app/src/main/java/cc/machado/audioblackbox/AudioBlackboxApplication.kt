package cc.machado.audioblackbox

import android.app.Application
import cc.machado.audioblackbox.audio.QualityPreset
import cc.machado.audioblackbox.export.redactSensitivePaths
import cc.machado.audioblackbox.export.writeCrashLogEntrySync
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

        installCrashLogHandler()
    }

    /**
     * Issue #371: the last line of defense for a JVM crash. Installs a global
     * [Thread.setDefaultUncaughtExceptionHandler] that appends a durable, local entry to a sibling
     * of `export_errors.log` (see [CrashLogFileHolder] and
     * [cc.machado.audioblackbox.export.writeCrashLogEntrySync]'s doc for the file-placement and
     * durability rationale) and then **always** invokes whatever handler was previously installed.
     *
     * ## Chaining, not replacing
     * [previousHandler] is captured before this handler is installed and invoked unconditionally
     * from a `finally` block -- including when the write itself throws. Android's own default
     * handler (or another one already installed earlier in the process) is what kills the process
     * and reports the crash to Play Console / Android vitals, which today is this app's *only*
     * external crash backstop (no crash-reporting SDK is used, by product decision -- see #119).
     * A handler that swallowed the exception instead of chaining would silently destroy that
     * backstop while looking like an improvement.
     *
     * ## Coverage this does NOT provide (issue #371's explicit non-goals)
     * - A process killed by a package replace/update (as in #370) never reaches this handler at
     *   all -- there is no uncaught exception, the process is simply torn down. That is a
     *   lifecycle-trail gap, not a crash gap, and is out of scope here.
     * - A native crash (e.g. a `MediaCodec`/NDK `SIGSEGV`) is invisible to this handler entirely --
     *   `Thread.setDefaultUncaughtExceptionHandler` is a JVM-only mechanism.
     * - Nothing here is transmitted off-device; the entry is appended to local storage only.
     */
    private fun installCrashLogHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val crashFile = java.io.File(applicationContext.filesDir, "crash_log.log")
        CrashLogFileHolder.file = crashFile

        val (versionName, versionCode) = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            (info.versionName ?: "unknown") to info.longVersionCode
        } catch (e: Exception) {
            "unknown" to -1L
        }

        // Roots that must never appear verbatim in a crash entry (issue #371's "no paths under the
        // user's media directories" requirement): this app's own private dirs and *every* external
        // storage volume this Context can resolve -- `getExternalFilesDirs` (plural), not
        // `getExternalFilesDir` (singular), so a secondary volume (e.g. a removable SD card) is
        // covered too, not just the primary one (`@sec` review finding on PR #372). Recordings
        // themselves are exported via `MediaStore` (`content://` URIs, not filesystem paths -- see
        // `MediaStoreSink`), so this list exists as defense in depth against an incidental path
        // showing up inside an exception message, not because a normal code path is expected to
        // hand one to a crash. [redactSensitivePaths] itself additionally covers path *shapes*
        // (aliases, other volumes, other user profiles) this Context never resolves -- see its own
        // doc for what is and is not covered.
        val sensitiveRoots = buildList {
            applicationContext.filesDir?.absolutePath?.let(::add)
            applicationContext.cacheDir?.absolutePath?.let(::add)
            applicationContext.externalCacheDir?.absolutePath?.let(::add)
            applicationContext.getExternalFilesDirs(null)?.forEach { dir ->
                dir?.absolutePath?.let(::add)
            }
        }
        val packageNameForRedaction = packageName
        val redact: (String) -> String = { raw ->
            redactSensitivePaths(raw, sensitiveRoots, packageNameForRedaction)
        }

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Bounded wait, not an unbounded one (`@rev`/`@sec` review finding on PR #372):
                // this write is synchronous I/O on whatever thread runs it, and a stalled/unhealthy
                // filesystem could otherwise delay -- indefinitely -- the `finally` below that hands
                // off to [previousHandler], which is what gets this crash into Play Console/Android
                // vitals. Vitals reporting is this app's only *external* crash backstop and must not
                // be held hostage by the on-device log succeeding. The write itself still runs on a
                // fresh thread (not the crashing one) precisely so it can be abandoned via a timed
                // `join` rather than needing its own internal cancellation plumbing; on the
                // overwhelmingly common case (a healthy filesystem) this adds only the cost of one
                // thread start and finishes well inside the timeout, so the crash entry is still
                // written essentially every time -- the bound only matters in the rare stall case,
                // where losing the entry is the accepted cost of not losing vitals reporting too.
                val writerThread = Thread({
                    writeCrashLogEntrySync(
                        file = crashFile,
                        timestampMillis = System.currentTimeMillis(),
                        threadName = thread.name,
                        throwable = throwable,
                        versionName = versionName,
                        versionCode = versionCode,
                        sanitize = redact,
                    )
                }, "crash-log-writer")
                writerThread.isDaemon = true
                // Issue #373: `writeCrashLogEntrySync` catches `Throwable`, but something that
                // still escapes it (an `Error` such as `OutOfMemoryError`/`StackOverflowError` --
                // exactly what a crash-time write on an already-dying process can hit) would
                // otherwise propagate to the *shared* default handler, i.e. back into this very
                // handler, recursively. That would misattribute a second report to
                // `previousHandler` (noise on the app's only external crash backstop, Play
                // Console/Android vitals) describing the writer's failure instead of the original
                // crash, and stack a second `CRASH_WRITE_TIMEOUT_MILLIS`-shaped delay before the
                // original crash finally reports. This handler is deliberately a no-op -- do not
                // "fix" it by logging or writing anything here, since doing either on this thread,
                // for this failure, is the exact recursion this exists to stop.
                writerThread.setUncaughtExceptionHandler { _, _ -> }
                writerThread.start()
                writerThread.join(CRASH_WRITE_TIMEOUT_MILLIS)
            } catch (t: Throwable) {
                // Never let a failure in the crash-logger itself block the platform's own handler.
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private companion object {
        /** See the `Thread.setDefaultUncaughtExceptionHandler` block's own comment for why this
         * bound exists at all: it trades "the write might not finish before the process is killed
         * anyway" for "the previous handler -- and Play Console/Android vitals reporting -- is
         * never held up by a stalled filesystem for longer than this." */
        private const val CRASH_WRITE_TIMEOUT_MILLIS = 2_000L
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

/**
 * Holds the crash log's file path (issue #371) -- a sibling of [ErrorLogFileHolder]'s file, same
 * "plain top-level holder set once in `Application.onCreate`" shape and the same reason: the
 * crash handler installed in [AudioBlackboxApplication.installCrashLogHandler] needs a
 * `Context.filesDir`-derived path before any Activity/Service exists. `null` (the default) only in
 * a plain JVM unit test that never runs [AudioBlackboxApplication]; [DashboardViewModel]'s reader
 * already treats a `null` crash-log file as a safe no-op the same way it does for
 * [ErrorLogFileHolder] -- see [cc.machado.audioblackbox.export.readErrorLog]'s doc.
 */
object CrashLogFileHolder {
    @Volatile
    var file: java.io.File? = null
}
