package cc.machado.audioblackbox.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Round-trips the hand-rolled JSONL writer ([logExportError] -> file) through the hand-rolled
 * reader ([readErrorLog]) against hostile content (issue #349, `@sec`'s non-blocking finding):
 * quoting/escaping metacharacters, control characters, non-ASCII/emoji, a lone UTF-16 surrogate,
 * and two "the file on disk is not what the writer intended" shapes -- a torn final line from a
 * process killed mid-write, and a garbage line landing in the middle of an otherwise-good file.
 *
 * ## Testing Oracle
 * Each test writes content through the real writer (or, for the torn-file shapes, assembles a
 * file byte-for-byte the way a crash would leave it) and asserts the exact string
 * [readErrorLog] hands back, or that a torn/garbage line is skipped without losing or corrupting
 * any other record in the file.
 */
class ExportErrorLogRoundTripHostileContentTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun roundTrip_quotesBackslashesNewlinesTabsAndControlChars() {
        val file = tempDir.newFile("export_errors.log")
        val hostileMessage = "quote\" backslash\\ newline\nhere\ttabcontrolchar"
        val hostileReason = "REASON_WITH_\"QUOTE\"_AND_\\BACKSLASH\\"
        val exception = RuntimeException("stack\ntrace\twith\"quotes\"and\\backslashes\\")

        logExportError(file, { 1L }, "Comp\"onent", hostileReason, hostileMessage, exception)
        flushErrorLogsForTest()

        val entry = readErrorLog(file).single()
        assertEquals("Comp\"onent", entry.component)
        assertEquals(hostileReason, entry.reason)
        assertEquals(hostileMessage, entry.message)
        assertTrue(entry.stackTrace!!.contains("stack\ntrace\twith\"quotes\"and\\backslashes\\"))
    }

    @Test
    fun roundTrip_nonAsciiAndEmoji() {
        val file = tempDir.newFile("export_errors.log")
        val message = "gravou áudio com éxito 🎤🔴 中文测试"

        logExportError(file, { 1L }, "ExportEngine", "REASON", message, null)
        flushErrorLogsForTest()

        val entry = readErrorLog(file).single()
        assertEquals(message, entry.message)
    }

    @Test
    fun roundTrip_loneSurrogate_doesNotThrowAndPreservesSurroundingText() {
        val file = tempDir.newFile("export_errors.log")
        // A lone high surrogate with no matching low surrogate -- not valid UTF-16 text, but
        // nothing stops a Throwable#getMessage() or a filename from containing one.
        val message = "before\uD800after"

        logExportError(file, { 1L }, "ExportEngine", "REASON", message, null)
        flushErrorLogsForTest()

        // Must not throw, must not be silently dropped: some entry comes back, with the
        // surrounding, well-formed text intact around wherever the unpairable surrogate landed.
        val entries = readErrorLog(file)
        assertEquals(1, entries.size)
        val recovered = entries.single().message
        assertTrue(
            "expected surrounding text preserved, got: $recovered",
            recovered.startsWith("before") && recovered.endsWith("after"),
        )
    }

    @Test
    fun readErrorLog_truncatedFinalLine_isSkipped_priorRecordsSurvive() {
        // Models a process killed mid-write, or a rotation landing mid-record: the last line in
        // the file is a torn-off prefix of a JSON object, never closed.
        val fileA = tempDir.newFile("only_a.log")
        logExportError(fileA, { 1L }, "ExportEngine", "REASON_A", "first, intact", null)
        flushErrorLogsForTest()
        val intactLine = fileA.readText().trim()

        val file = tempDir.newFile("export_errors.log")
        val tornLine = "{\"schemaVersion\":1,\"timestampMillis\":2,\"component\":\"ExportEngine\"," +
            "\"reason\":\"REASON_B\",\"severity\":\"ERROR\",\"message\":\"cut off mid-str"
        file.writeText(intactLine + "\n" + tornLine)

        val entries = readErrorLog(file)
        assertEquals("only the intact record should survive", listOf("REASON_A"), entries.map { it.reason })
    }

    @Test
    fun readErrorLog_garbageLineInMiddle_isSkipped_recordsBeforeAndAfterSurvive() {
        val fileA = tempDir.newFile("a.log")
        logExportError(fileA, { 1L }, "ExportEngine", "REASON_A", "first", null)
        flushErrorLogsForTest()
        val lineA = fileA.readText().trim()

        val fileC = tempDir.newFile("c.log")
        logExportError(fileC, { 3L }, "ExportEngine", "REASON_C", "third", null)
        flushErrorLogsForTest()
        val lineC = fileC.readText().trim()

        val file = tempDir.newFile("export_errors.log")
        file.writeText(lineA + "\n" + "!!! not json, not a legacy header, just noise !!!" + "\n" + lineC)

        val entries = readErrorLog(file)
        // Newest-first: REASON_C then REASON_A, with the garbage line contributing nothing.
        assertEquals(listOf("REASON_C", "REASON_A"), entries.map { it.reason })
    }
}
