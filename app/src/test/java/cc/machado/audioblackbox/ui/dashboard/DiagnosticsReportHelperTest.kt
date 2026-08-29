package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.audio.QualityPreset
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying [DiagnosticsReportHelper]'s report generation (issue #206).
 */
class DiagnosticsReportHelperTest {

    @Test
    fun `buildSaveErrorReport formats all incident parameters accurately`() {
        val report = DiagnosticsReportHelper.buildSaveErrorReport(
            reason = "CURSOR_LAPPED",
            message = "lost 5120 bytes",
            preset = QualityPreset.HIGH_FIDELITY,
            capacityMinutes = 15,
            bufferedMillis = 30_000L,
            timestampMillis = 1788000000000L,
        )

        assertTrue(report.contains("=== AUDIO BLACKBOX INCIDENT REPORT ==="))
        assertTrue(report.contains("Type: Audio Save / Export Failure"))
        assertTrue(report.contains("Reason Code: CURSOR_LAPPED"))
        assertTrue(report.contains("Message: lost 5120 bytes"))
        assertTrue(report.contains("Active Preset: HIGH_FIDELITY (44100 Hz, Stereo)"))
        assertTrue(report.contains("Configured Capacity: 15 min"))
        assertTrue(report.contains("Buffered Audio at Failure: 30s (30000 ms)"))
    }

    @Test
    fun `buildForwardErrorReport formats forward failure context accurately`() {
        val report = DiagnosticsReportHelper.buildForwardErrorReport(
            reason = "STREAM_RESET",
            message = "capture was reset",
            preset = QualityPreset.VOICE,
            capacityMinutes = 45,
            timestampMillis = 1788000000000L,
        )

        assertTrue(report.contains("=== AUDIO BLACKBOX INCIDENT REPORT ==="))
        assertTrue(report.contains("Type: Continuous Live Recording Failure"))
        assertTrue(report.contains("Reason Code: STREAM_RESET"))
        assertTrue(report.contains("Message: capture was reset"))
        assertTrue(report.contains("Active Preset: VOICE (16000 Hz, Mono)"))
        assertTrue(report.contains("Configured Capacity: 45 min"))
    }
}
