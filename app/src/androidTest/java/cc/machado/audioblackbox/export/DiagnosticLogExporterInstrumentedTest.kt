package cc.machado.audioblackbox.export

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Looper
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.anyIntent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.machado.audioblackbox.CrashLogFileHolder
import cc.machado.audioblackbox.ErrorLogFileHolder
import cc.machado.audioblackbox.audio.QualityPreset
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #388's "the action fires the share intent with the expected content/URI" acceptance
 * criterion, proven end-to-end against the real [exportFullDiagnosticLog] function (not a stand-in):
 * real files are written under this instrumentation's real `filesDir`, [ErrorLogFileHolder]/
 * [CrashLogFileHolder] are pointed at them exactly as [cc.machado.audioblackbox.AudioBlackboxApplication.onCreate]
 * does in production, and [Intents] (Espresso-Intents) intercepts the real [Intent] this app's
 * process hands to [android.content.Context.startActivity] -- so this test would fail if
 * [exportFullDiagnosticLog] stopped building a `text/plain` `ACTION_SEND` with a `content://`
 * `EXTRA_STREAM`, or if the shared file's actual on-disk bytes stopped matching the report this
 * test independently expects.
 *
 * Non-vacuity: a run with [ErrorLogFileHolder.file]/[CrashLogFileHolder.file] left `null` (the
 * "log entirely absent" case) is asserted separately to show no share [Intent] fires and the
 * empty-log toast path is taken instead -- proving this test's "intent fires" assertions are not
 * trivially true for every call.
 *
 * PR #390 `@rev` review (SHA `804ea81`) added three more cases: an `AUDIT`-only log (finding 2), a
 * proof that the heavy read/redact/write work actually lands off the main thread (finding 1), and a
 * regression test for the write-failure path now caught by [exportFullDiagnosticLog] (finding 4).
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticLogExporterInstrumentedTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var errorLogFile: File
    private lateinit var crashLogFile: File

    @Before
    fun setUp() {
        Intents.init()
        // Espresso-Intents only intercepts an activity-launch Intent it can "resolve" to some
        // result -- respond to every one with CANCELED so exportFullDiagnosticLog's
        // startActivity(chooser) call returns normally instead of actually opening a chooser UI.
        Intents.intending(anyIntent())
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))

        errorLogFile = File(context.filesDir, "export_errors_test.log")
        crashLogFile = File(context.filesDir, "crash_log_test.log")
        errorLogFile.delete()
        File(errorLogFile.parent, errorLogFile.name + ".old").delete()
        crashLogFile.delete()
        File(crashLogFile.parent, crashLogFile.name + ".old").delete()

        val diagnosticsCacheDir = File(context.cacheDir, "diagnostics")
        // Cleanup from a previous run of the write-failure test, which deliberately leaves a
        // *file* (not a directory) at this path -- see that test's own comment.
        if (diagnosticsCacheDir.exists() && diagnosticsCacheDir.isFile) {
            diagnosticsCacheDir.delete()
        }
    }

    @After
    fun tearDown() {
        Intents.release()
        ErrorLogFileHolder.file = null
        CrashLogFileHolder.file = null
        errorLogFile.delete()
        File(errorLogFile.parent, errorLogFile.name + ".old").delete()
        crashLogFile.delete()
        File(crashLogFile.parent, crashLogFile.name + ".old").delete()

        val diagnosticsCacheDir = File(context.cacheDir, "diagnostics")
        if (diagnosticsCacheDir.exists() && diagnosticsCacheDir.isFile) {
            diagnosticsCacheDir.delete()
        }
    }

    /** Every real UI call site launches [exportFullDiagnosticLog] from a `rememberCoroutineScope()`
     * coroutine, which runs on [Dispatchers.Main] by default -- so tests call it the same way,
     * from a background instrumentation thread, blocking on [Dispatchers.Main] via [runBlocking]. */
    private fun runExportBlocking(
        preset: QualityPreset = QualityPreset.BALANCED,
        retentionMinutes: Int = 30,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = runBlocking(Dispatchers.Main) {
        exportFullDiagnosticLog(
            context = context,
            preset = preset,
            retentionMinutes = retentionMinutes,
            ioDispatcher = ioDispatcher,
        )
    }

    @Test
    fun exportFullDiagnosticLog_withRealEntries_firesShareIntentWithExpectedContent() {
        errorLogFile.writeText("{\"schemaVersion\":1,\"timestampMillis\":1,\"component\":\"Test\",\"reason\":\"CURSOR_LAPPED\",\"severity\":\"ERROR\",\"message\":\"lost bytes\"}\n")
        ErrorLogFileHolder.file = errorLogFile
        CrashLogFileHolder.file = crashLogFile

        runExportBlocking()

        Intents.intended(
            allOf(
                hasAction(Intent.ACTION_CHOOSER),
            ),
        )

        val chooserIntent = Intents.getIntents().last()
        // minSdk 29 (see .agents/team.toml): the typed getParcelableExtra(String, Class<T>)
        // overload is API 33+ only, so this uses the older, deprecated-but-still-min-sdk-compatible
        // form deliberately, the same way production code elsewhere in this repo targets minSdk 29.
        @Suppress("DEPRECATION")
        val sentIntent = chooserIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertTrue("wrapped intent must be ACTION_SEND", sentIntent?.action == Intent.ACTION_SEND)
        assertTrue("wrapped intent must be text/plain", sentIntent?.type == "text/plain")

        @Suppress("DEPRECATION")
        val uri = sentIntent?.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertTrue("EXTRA_STREAM must carry a content:// uri", uri?.scheme == "content")

        val sharedText = context.contentResolver.openInputStream(uri!!)!!.bufferedReader().readText()
        assertTrue(sharedText.contains("--- export_errors.log ---"))
        assertTrue(sharedText.contains("CURSOR_LAPPED"))
        assertTrue(sharedText.contains("Retention Window: 30 min"))
        assertTrue(sharedText.contains("Active Preset: ${QualityPreset.BALANCED.name}"))
    }

    @Test
    fun exportFullDiagnosticLog_withEmptyLog_doesNotFireShareIntent() {
        ErrorLogFileHolder.file = errorLogFile
        CrashLogFileHolder.file = crashLogFile
        // Neither file exists on disk at all -- the empty-log path.

        runExportBlocking()

        assertFalse(
            "no ACTION_SEND/ACTION_CHOOSER should fire for an entirely empty diagnostic log",
            Intents.getIntents().any {
                it.action == Intent.ACTION_CHOOSER || it.action == Intent.ACTION_SEND
            },
        )
    }

    @Test
    fun exportFullDiagnosticLog_withAuditOnlyLog_stillFiresShareIntent() {
        // Issue #388 acceptance criterion 2 / PR #390 `@rev` finding 2: a log with only AUDIT
        // entries (no ERROR, no CRASH) must remain exportable -- isDiagnosticReportEmpty must not
        // key off severity. TAIL_TRUNCATED resolves to AUDIT via severityForReason.
        errorLogFile.writeText(
            "{\"schemaVersion\":1,\"timestampMillis\":1,\"component\":\"ForwardRecordingEngine\"," +
                "\"reason\":\"TAIL_TRUNCATED\",\"severity\":\"AUDIT\",\"message\":\"trimmed tail\"}\n",
        )
        ErrorLogFileHolder.file = errorLogFile
        CrashLogFileHolder.file = crashLogFile

        runExportBlocking()

        Intents.intended(hasAction(Intent.ACTION_CHOOSER))
    }

    @Test
    fun exportFullDiagnosticLog_runsHeavyWorkOffTheMainThread() {
        // PR #390 `@rev` finding 1, proven by mutation-verified non-vacuity: a probe dispatcher
        // wraps the real Dispatchers.IO and records which thread actually executed the
        // read/redact/assemble/write work. If exportFullDiagnosticLog stopped calling
        // withContext(ioDispatcher) around that work (the exact mutation manually verified while
        // writing this test -- removing withContext makes probedLooper capture the main Looper and
        // this test fail), this test would fail.
        errorLogFile.writeText("{\"schemaVersion\":1,\"timestampMillis\":1,\"component\":\"Test\",\"reason\":\"CURSOR_LAPPED\",\"severity\":\"ERROR\",\"message\":\"lost bytes\"}\n")
        ErrorLogFileHolder.file = errorLogFile
        CrashLogFileHolder.file = crashLogFile

        val probedLooper = AtomicReference<Looper?>()
        val probeRan = AtomicBoolean(false)
        val probeDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                Dispatchers.IO.dispatch(context) {
                    probeRan.set(true)
                    probedLooper.set(Looper.myLooper())
                    block.run()
                }
            }
        }

        runExportBlocking(ioDispatcher = probeDispatcher)

        assertTrue("the probe dispatcher must actually have run the work", probeRan.get())
        // A background (non-Looper) thread has no prepared Looper at all -- Looper.myLooper()
        // returns null there, which is already proof enough that this is not the main thread's
        // Looper. The explicit inequality below is the actual oracle either way.
        assertTrue(
            "heavy work must not run on the main Looper",
            probedLooper.get() !== Looper.getMainLooper(),
        )
    }

    @Test
    fun exportFullDiagnosticLog_whenWriteFails_doesNotCrashAndDoesNotFireShareIntent() {
        // PR #390 `@rev` finding 4 regression test: pre-create the diagnostics cache subdirectory
        // path as a plain *file* instead of a directory, so `File(cacheDir, "diagnostics").mkdirs()`
        // silently fails and the subsequent `reportFile.writeText(...)` throws a real IOException
        // (no parent directory to write into) -- the exact shape of failure finding 4 flagged as
        // uncaught. Before the fix, this IOException propagated out of exportFullDiagnosticLog and
        // this test itself would fail with that exception; the oracle here is that the suspend call
        // returns normally.
        val diagnosticsCacheDir = File(context.cacheDir, "diagnostics")
        if (diagnosticsCacheDir.exists()) {
            diagnosticsCacheDir.deleteRecursively()
        }
        assertTrue(diagnosticsCacheDir.createNewFile())

        errorLogFile.writeText("{\"schemaVersion\":1,\"timestampMillis\":1,\"component\":\"Test\",\"reason\":\"CURSOR_LAPPED\",\"severity\":\"ERROR\",\"message\":\"lost bytes\"}\n")
        ErrorLogFileHolder.file = errorLogFile
        CrashLogFileHolder.file = crashLogFile

        // No exception must escape -- this line itself is the primary assertion (JUnit fails the
        // test automatically if it throws).
        runExportBlocking()

        assertFalse(
            "a write failure must not still fire a share intent for a broken/partial file",
            Intents.getIntents().any {
                it.action == Intent.ACTION_CHOOSER || it.action == Intent.ACTION_SEND
            },
        )

        diagnosticsCacheDir.delete()
    }
}
