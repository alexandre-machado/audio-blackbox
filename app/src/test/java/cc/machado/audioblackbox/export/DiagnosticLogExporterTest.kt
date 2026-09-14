package cc.machado.audioblackbox.export

import cc.machado.audioblackbox.audio.QualityPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves issue #388's "montagem do relatório" acceptance criterion: [buildFullDiagnosticReport]
 * joins all four log generations in the fixed order the exported file promises, redaction (via
 * [redactSensitivePaths], exercised through the real function, not a stand-in -- same discipline
 * PR #372's `@sec` finding required for [CrashLogHandlerTest]) survives into the assembled report,
 * the triage header carries every field the issue requires, and both "generation missing" and "log
 * entirely empty" are distinguishable, non-vacuous states.
 *
 * The oracle for every test below is explicit: each assertion names the exact production behavior
 * ([buildFullDiagnosticReport]'s section order/content, [isDiagnosticReportEmpty]'s boolean) that
 * would have to regress for the test to fail.
 */
class DiagnosticLogExporterTest {

    @Test
    fun `buildFullDiagnosticReport joins all four generations in order`() {
        val report = buildFullDiagnosticReport(
            versionName = "1.2.3",
            versionCode = 42L,
            deviceModel = "Samsung SM-S931B",
            androidVersion = "16 (API 36)",
            preset = QualityPreset.HIGH_FIDELITY,
            retentionMinutes = 30,
            exportLogText = "EXPORT_CURRENT_LINE",
            exportLogOldText = "EXPORT_OLD_LINE",
            crashLogText = "CRASH_CURRENT_LINE",
            crashLogOldText = "CRASH_OLD_LINE",
            timestampMillis = 1788000000000L,
        )

        // Oracle: the four section markers, in this exact relative order -- a caller that swapped
        // two generations, or merged them out of the export_errors.log -> .old -> crash_log.log ->
        // .old sequence, would fail this ordering check even though every section is still present.
        val exportIdx = report.indexOf("--- export_errors.log ---")
        val exportOldIdx = report.indexOf("--- export_errors.log.old ---")
        val crashIdx = report.indexOf("--- crash_log.log ---")
        val crashOldIdx = report.indexOf("--- crash_log.log.old ---")
        assertTrue("all four sections must be present", exportIdx >= 0 && exportOldIdx > exportIdx)
        assertTrue(exportOldIdx < crashIdx)
        assertTrue(crashIdx < crashOldIdx)

        assertTrue(report.contains("EXPORT_CURRENT_LINE"))
        assertTrue(report.contains("EXPORT_OLD_LINE"))
        assertTrue(report.contains("CRASH_CURRENT_LINE"))
        assertTrue(report.contains("CRASH_OLD_LINE"))
    }

    @Test
    fun `buildFullDiagnosticReport header carries version, device, android, preset and retention`() {
        val report = buildFullDiagnosticReport(
            versionName = "2.0.0",
            versionCode = 99L,
            deviceModel = "Pixel 8",
            androidVersion = "15 (API 35)",
            preset = QualityPreset.VOICE,
            retentionMinutes = 45,
            exportLogText = null,
            exportLogOldText = null,
            crashLogText = null,
            crashLogOldText = null,
            timestampMillis = 1788000000000L,
        )

        assertTrue(report.contains("App Version: 2.0.0 (99)"))
        assertTrue(report.contains("Device: Pixel 8"))
        assertTrue(report.contains("Android OS: 15 (API 35)"))
        assertTrue(report.contains("Active Preset: VOICE (16000 Hz, Mono)"))
        assertTrue(report.contains("Retention Window: 45 min"))
    }

    @Test
    fun `buildFullDiagnosticReport marks a missing generation explicitly, not silently omitted`() {
        val report = buildFullDiagnosticReport(
            versionName = "1.0.0",
            versionCode = 1L,
            deviceModel = "Device",
            androidVersion = "14 (API 34)",
            preset = QualityPreset.BALANCED,
            retentionMinutes = 15,
            exportLogText = "only this exists",
            exportLogOldText = null,
            crashLogText = null,
            crashLogOldText = null,
            timestampMillis = 1788000000000L,
        )

        // Oracle: a caller of this report cannot tell "file absent" apart from "section dropped"
        // unless a missing generation still renders its own header followed by an explicit marker.
        assertTrue(report.contains("--- export_errors.log.old ---\n(not present)"))
        assertTrue(report.contains("--- crash_log.log ---\n(not present)"))
        assertTrue(report.contains("--- crash_log.log.old ---\n(not present)"))
        assertTrue(report.contains("only this exists"))
    }

    @Test
    fun `buildFullDiagnosticReport carries redacted content through, not the raw sensitive text`() {
        val rawLine = "failed reading /data/user/0/cc.machado.audioblackbox/files/export_errors.log"
        val redacted = redactSensitivePaths(
            rawLine,
            sensitiveRoots = emptyList(),
            packageName = "cc.machado.audioblackbox",
        )

        val report = buildFullDiagnosticReport(
            versionName = "1.0.0",
            versionCode = 1L,
            deviceModel = "Device",
            androidVersion = "14 (API 34)",
            preset = QualityPreset.BALANCED,
            retentionMinutes = 15,
            exportLogText = redacted,
            exportLogOldText = null,
            crashLogText = null,
            crashLogOldText = null,
            timestampMillis = 1788000000000L,
        )

        // Oracle: the real absolute path must never reach the assembled report -- only the
        // already-redacted marker does. If redaction regressed (e.g. redactSensitivePaths stopped
        // matching /data/user/<n>/<package>), rawLine's literal path would show up here instead.
        assertFalse(report.contains("/data/user/0/cc.machado.audioblackbox"))
        assertTrue(report.contains("<redacted-path>"))
    }

    @Test
    fun `isDiagnosticReportEmpty is true only when every generation is null or blank`() {
        assertTrue(isDiagnosticReportEmpty(null, null, null, null))
        assertTrue(isDiagnosticReportEmpty("", "   ", null, null))
        assertFalse(isDiagnosticReportEmpty("has content", null, null, null))
        assertFalse(isDiagnosticReportEmpty(null, null, "crash entry", null))
    }

    @Test
    fun `buildFullDiagnosticReport of an entirely empty log still produces a valid header`() {
        val report = buildFullDiagnosticReport(
            versionName = "1.0.0",
            versionCode = 1L,
            deviceModel = "Device",
            androidVersion = "14 (API 34)",
            preset = QualityPreset.DEFAULT,
            retentionMinutes = 15,
            exportLogText = null,
            exportLogOldText = null,
            crashLogText = null,
            crashLogOldText = null,
            timestampMillis = 1788000000000L,
        )

        assertTrue(isDiagnosticReportEmpty(null, null, null, null))
        assertTrue(report.contains("=== AUDIO BLACKBOX FULL DIAGNOSTIC LOG ==="))
        // All four sections still render as explicitly absent rather than the report being blank.
        assertEquals(4, Regex("\\(not present\\)").findAll(report).count())
    }
}
