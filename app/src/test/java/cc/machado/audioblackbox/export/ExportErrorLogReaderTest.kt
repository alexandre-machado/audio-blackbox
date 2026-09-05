package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.ui.dashboard.ErrorLogUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Proves [readErrorLog]'s three load-bearing contracts for issue #346's durable error list:
 * newest-first ordering, inclusion of the rotated `.old` generation, and best-effort migration of
 * pre-#346 plain-text lines instead of silently dropping them.
 *
 * ## Testing Oracle
 * Each test asserts on the exact parsed [ErrorLogEntry] list `readErrorLog` returns for a file
 * written a specific way -- a broken ordering, a dropped `.old` file, or a mis-parsed legacy line
 * would fail one of these assertions directly, not just "the file has some bytes in it".
 */
class ExportErrorLogReaderTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun readErrorLog_returnsNewestFirst() {
        val file = tempDir.newFile("export_errors.log")
        logExportError(file, { 1000L }, "ExportEngine", "REASON_A", "first", null)
        flushErrorLogsForTest()
        logExportError(file, { 2000L }, "ExportEngine", "REASON_B", "second", null)
        flushErrorLogsForTest()

        val entries = readErrorLog(file)
        assertEquals(listOf("REASON_B", "REASON_A"), entries.map { it.reason })
    }

    @Test
    fun readErrorLog_includesRotatedOldGeneration_afterCurrent() {
        val file = tempDir.newFile("export_errors.log")
        val oldFile = tempDir.newFile("export_errors.log.old")
        oldFile.writeText(
            """{"schemaVersion":1,"timestampMillis":100,"component":"ExportEngine","reason":"OLD_ONE","severity":"ERROR","message":"m1"}
{"schemaVersion":1,"timestampMillis":200,"component":"ExportEngine","reason":"OLD_TWO","severity":"ERROR","message":"m2"}
""".trimIndent(),
        )
        logExportError(file, { 9999L }, "ExportEngine", "CURRENT", "still buffered", null)
        flushErrorLogsForTest()

        val entries = readErrorLog(file)
        // Current file's (newest-first) entries first, then .old's own newest-first entries --
        // the rotation generation is included, not excluded (issue #346's explicit owner call).
        assertEquals(listOf("CURRENT", "OLD_TWO", "OLD_ONE"), entries.map { it.reason })
    }

    @Test
    fun readErrorLog_parsesLegacyPlainTextLineBestEffort_insteadOfDroppingIt() {
        val file = tempDir.newFile("export_errors.log")
        file.writeText(
            "[2026-01-01T00:00:00.000+00:00] [ExportEngine] [SINK_OPEN_FAILED] simulated disk full\n" +
                "java.io.IOException: simulated disk full\n" +
                "\tat cc.machado.audioblackbox.export.ExportEngine.runExport(ExportEngine.kt:329)\n",
        )

        val entries = readErrorLog(file)
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("ExportEngine", entry.component)
        assertEquals("SINK_OPEN_FAILED", entry.reason)
        assertEquals("simulated disk full", entry.message)
        assertTrue(entry.legacy)
        assertTrue(entry.stackTrace!!.contains("java.io.IOException"))
        // The legacy ISO timestamp format parses to a real millis value, not the "couldn't parse"
        // fallback of 0L.
        assertTrue(entry.timestampMillis > 0L)
    }

    @Test
    fun readErrorLog_mixedLegacyAndJsonLines_bothSurvive() {
        val file = tempDir.newFile("export_errors.log")
        file.writeText(
            "[2026-01-01T00:00:00.000+00:00] [ExportEngine] [LEGACY_REASON] legacy message\n",
        )
        logExportError(file, { 5000L }, "ExportEngine", "NEW_REASON", "new message", null)
        flushErrorLogsForTest()

        val entries = readErrorLog(file)
        assertEquals(listOf("NEW_REASON", "LEGACY_REASON"), entries.map { it.reason })
    }

    @Test
    fun readErrorLog_auditSeverity_roundTrips() {
        val file = tempDir.newFile("export_errors.log")
        logExportError(
            file, { 1L }, "ForwardRecordingEngine", "MUXER_STOP_RECOVERED", "recovered", null,
            ErrorLogSeverity.AUDIT,
        )
        flushErrorLogsForTest()

        val entry = readErrorLog(file).single()
        assertEquals(ErrorLogSeverity.AUDIT, entry.severity)
    }

    @Test
    fun readErrorLog_legacyTailTruncatedLine_isAuditNotError_andDoesNotRaiseTheCard() {
        // The exact shape ForwardRecordingEngine wrote before issue #346 migrated the format to
        // JSON (issue #322/#323's TAIL_TRUNCATED call site): a device that recorded before this
        // migration has this line on disk today, with no severity field at all. PR #349 review
        // finding: LegacyBuilder.build() used to hardcode ERROR for every migrated line regardless
        // of `reason`, which would flip this pre-existing, already-successful session's dashboard
        // card on after the migration -- exactly what #346 said must not happen.
        val file = tempDir.newFile("export_errors.log")
        file.writeText(
            "[2026-01-01T00:00:00.000+00:00] [ForwardRecordingEngine] [TAIL_TRUNCATED] " +
                "Clean stop dropped 512 bytes at cursor 4096: no retained segment describes their " +
                "recorded format\n",
        )

        val entries = readErrorLog(file)
        val entry = entries.single()
        assertEquals("TAIL_TRUNCATED", entry.reason)
        assertTrue(entry.legacy)
        assertEquals(ErrorLogSeverity.AUDIT, entry.severity)
        assertTrue(
            "an AUDIT-only legacy line must not raise the dashboard's error card",
            !ErrorLogUiState(entries = entries).hasVisibleErrors,
        )
    }

    @Test
    fun readErrorLog_legacyMuxerStopRecoveredLine_isAudit() {
        val file = tempDir.newFile("export_errors.log")
        file.writeText(
            "[2026-01-01T00:00:00.000+00:00] [ForwardRecordingEngine] [MUXER_STOP_RECOVERED] " +
                "MediaMuxer had already stopped itself before finish() could call stop() explicitly\n",
        )

        val entry = readErrorLog(file).single()
        assertEquals(ErrorLogSeverity.AUDIT, entry.severity)
    }

    @Test
    fun readErrorLog_legacyUnrecognizedReason_defaultsToError() {
        // An unrecognized legacy reason must default to ERROR (the fail-safe direction), not
        // silently become AUDIT and hide a genuine failure.
        val file = tempDir.newFile("export_errors.log")
        file.writeText(
            "[2026-01-01T00:00:00.000+00:00] [ExportEngine] [SOME_FUTURE_REASON] unrecognized\n",
        )

        val entry = readErrorLog(file).single()
        assertEquals(ErrorLogSeverity.ERROR, entry.severity)
    }

    @Test
    fun readErrorLog_missingFile_returnsEmptyList() {
        val missing = tempDir.root.resolve("does_not_exist.log")
        assertEquals(emptyList<ErrorLogEntry>(), readErrorLog(missing))
    }

    @Test
    fun readErrorLog_nullFile_returnsEmptyList() {
        assertEquals(emptyList<ErrorLogEntry>(), readErrorLog(null))
    }

    @Test
    fun clearErrorLog_deletesBothCurrentAndOldGeneration() {
        val file = tempDir.newFile("export_errors.log")
        val oldFile = tempDir.newFile("export_errors.log.old")
        logExportError(file, { 1L }, "ExportEngine", "REASON", "message", null)
        flushErrorLogsForTest()
        assertTrue(file.exists())
        assertTrue(oldFile.exists())

        clearErrorLog(file)

        assertTrue(!file.exists())
        assertTrue(!oldFile.exists())
        assertEquals(emptyList<ErrorLogEntry>(), readErrorLog(file))
    }
}
