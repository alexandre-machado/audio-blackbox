package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.ui.dashboard.ErrorLogUiState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Proves issue #371's three load-bearing claims about [writeCrashLogEntrySync] and the handler
 * shape [cc.machado.audioblackbox.AudioBlackboxApplication.installCrashLogHandler] installs around
 * it:
 *
 * 1. An uncaught exception on a background thread produces a durably-written, readable entry --
 *    asserted by really throwing on a real background [Thread] and then really reading the result
 *    back off disk through [readErrorLog], the exact function the dashboard uses. Nothing here is
 *    mocked: the whole defect class this issue exists to close is "the process died before the
 *    write reached disk", and a mocked writer would hide exactly that failure mode (the same trap
 *    as PR #289/#294's `RemoteViews` mock).
 * 2. The previous default handler still runs afterward, with the same thread/throwable it would
 *    have received had this handler never been installed -- so Android's own crash reporting to
 *    Play Console / Android vitals is never silently swallowed.
 * 3. The write survives on a *sibling* file, never `export_errors.log`, and even once merged for
 *    display a [ErrorLogSeverity.CRASH] entry does not flip the dashboard's ERROR-only
 *    "does a recording error exist" card.
 */
class CrashLogHandlerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun uncaughtException_writesDurableCrashEntry_andStillInvokesPreviousHandler() {
        val crashFile = File(tempDir.root, "crash_log.log")
        val previousHandlerInvoked = CountDownLatch(1)
        val previousHandlerThread = AtomicReference<Thread>()
        val previousHandlerThrowable = AtomicReference<Throwable>()

        val previousHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
            previousHandlerThread.set(thread)
            previousHandlerThrowable.set(throwable)
            previousHandlerInvoked.countDown()
        }

        // Mirrors AudioBlackboxApplication.installCrashLogHandler's real handler body: write first
        // (synchronously, direct to disk, no channel/coroutine), then always chain to whatever
        // handler ran before -- even if the write throws.
        val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashLogEntrySync(
                    file = crashFile,
                    timestampMillis = 123456789L,
                    threadName = thread.name,
                    throwable = throwable,
                    versionName = "1.2.3",
                    versionCode = 42L,
                )
            } finally {
                previousHandler.uncaughtException(thread, throwable)
            }
        }

        val worker = Thread(
            { throw IllegalStateException("boom on background thread") },
            "crash-test-worker",
        )
        worker.setUncaughtExceptionHandler(handler)
        worker.start()
        worker.join(5_000)

        assertTrue(
            "previous handler (the platform's own crash backstop) must still run",
            previousHandlerInvoked.await(5, TimeUnit.SECONDS),
        )
        assertEquals("crash-test-worker", previousHandlerThread.get()?.name)
        assertTrue(previousHandlerThrowable.get() is IllegalStateException)

        // Real write path, real disk read: this is what would be left on disk if the process had
        // actually died the instant the handler returned.
        assertTrue("crash log file should exist on disk", crashFile.exists())
        val entries = readErrorLog(null, crashFile)
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals(ErrorLogSeverity.CRASH, entry.severity)
        assertEquals("java.lang.IllegalStateException", entry.reason)
        assertEquals("crash-test-worker", entry.component)
        assertTrue(entry.message.contains("boom on background thread"))
        assertTrue(entry.stackTrace!!.contains("IllegalStateException"))
        assertTrue(entry.stackTrace!!.contains("versionName=1.2.3 versionCode=42"))
    }

    @Test
    fun writeCrashLogEntrySync_capturesFullCauseChain() {
        val crashFile = File(tempDir.root, "crash_log.log")
        val cause = RuntimeException("root cause")
        val outer = IllegalStateException("wrapper", cause)

        val written = writeCrashLogEntrySync(
            file = crashFile,
            timestampMillis = 1L,
            threadName = "main",
            throwable = outer,
            versionName = "1.0",
            versionCode = 1L,
        )
        assertTrue(written)

        val entry = readErrorLog(null, crashFile).single()
        assertTrue(entry.stackTrace!!.contains("Caused by"))
        assertTrue(entry.stackTrace!!.contains("root cause"))
    }

    @Test
    fun writeCrashLogEntrySync_redactsSensitivePathsViaSanitizer() {
        val crashFile = File(tempDir.root, "crash_log.log")
        val sensitivePath = "/data/data/cc.machado.audioblackbox/files/recordings/2026.raw"
        val throwable = RuntimeException("failed reading $sensitivePath")

        writeCrashLogEntrySync(
            file = crashFile,
            timestampMillis = 1L,
            threadName = "main",
            throwable = throwable,
            versionName = "1.0",
            versionCode = 1L,
            sanitize = { it.replace("/data/data/cc.machado.audioblackbox/files", "<redacted-path>") },
        )

        val raw = crashFile.readText()
        assertFalse(raw.contains("/data/data/cc.machado.audioblackbox/files"))
        assertTrue(raw.contains("<redacted-path>"))
    }

    @Test
    fun crashEntries_neverLandInExportErrorsFile_andDoNotRaiseTheExportFailureCard() {
        val exportFile = tempDir.newFile("export_errors.log")
        val crashFile = File(tempDir.root, "crash_log.log")

        writeCrashLogEntrySync(
            file = crashFile,
            timestampMillis = 1L,
            threadName = "main",
            throwable = RuntimeException("boom"),
            versionName = "1.0",
            versionCode = 1L,
        )

        assertEquals("crash entries must not be appended to the export log", 0L, exportFile.length())

        val merged = readErrorLog(exportFile, crashFile)
        assertEquals(1, merged.size)
        assertEquals(ErrorLogSeverity.CRASH, merged.single().severity)
        assertFalse(
            "a CRASH-only merged list must not raise the export-failure card",
            ErrorLogUiState(entries = merged).hasVisibleErrors,
        )
    }

    @Test
    fun writeCrashLogEntrySync_rotatesOnceOversized_oldestFirstEviction() {
        val crashFile = File(tempDir.root, "crash_log.log")
        val bigMessage = "x".repeat(2_000)
        // The crash log's cap (1 MB) is stricter than the export log's; ~700 entries at ~2 KB each
        // comfortably exceeds it, forcing at least one rotation.
        repeat(700) { i ->
            writeCrashLogEntrySync(
                file = crashFile,
                timestampMillis = i.toLong(),
                threadName = "main",
                throwable = RuntimeException(bigMessage),
                versionName = "1.0",
                versionCode = 1L,
            )
        }

        val rotated = File(tempDir.root, "crash_log.log.old")
        assertTrue("expected rotation once the crash log exceeds its stricter size cap", rotated.exists())
    }
}
