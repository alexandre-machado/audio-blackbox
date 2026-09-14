package cc.machado.audioblackbox.ui.dashboard

import cc.machado.audioblackbox.export.ErrorLogEntry
import cc.machado.audioblackbox.export.ErrorLogSeverity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #387: regression coverage for [ErrorLogUiState.hasCrashOrErrorEntries], the oracle the
 * dashboard now uses to decide whether [DurableErrorLogCard][DashboardScreen] (and therefore the
 * only entry point into [ErrorLogModal][DashboardScreen]) is shown at all.
 *
 * These tests exercise the pure [ErrorLogUiState] data class directly rather than routing through
 * [DashboardViewModel] -- the oracle is a pure function of [ErrorLogUiState.entries], so no
 * ViewModel/coroutine machinery is load-bearing here (see [DashboardViewModelErrorLogTest] for the
 * pre-existing [ErrorLogUiState.hasVisibleErrors] coverage this file deliberately does not
 * duplicate).
 */
class ErrorLogUiStateTest {

    private fun entry(reason: String, severity: ErrorLogSeverity) = ErrorLogEntry(
        timestampMillis = 1_000L,
        component = "component",
        reason = reason,
        message = "message for $reason",
        stackTrace = null,
        severity = severity,
    )

    @Test
    fun `empty log has no crash-or-error entries`() {
        val state = ErrorLogUiState(entries = emptyList())

        assertFalse(state.hasCrashOrErrorEntries)
        assertFalse(state.hasVisibleErrors)
    }

    @Test
    fun `AUDIT-only log has no crash-or-error entries`() {
        val state = ErrorLogUiState(entries = listOf(entry("MUXER_STOP_RECOVERED", ErrorLogSeverity.AUDIT)))

        assertFalse(
            "an AUDIT-only log must not raise the card (#347's rule, unchanged by #387)",
            state.hasCrashOrErrorEntries,
        )
        assertFalse(state.hasVisibleErrors)
    }

    @Test
    fun `CRASH-only log raises the card even though hasVisibleErrors stays false`() {
        val state = ErrorLogUiState(entries = listOf(entry("uncaught", ErrorLogSeverity.CRASH)))

        // This is the exact defect #387 fixes: a CRASH-only log must still surface the card, even
        // though it must NOT be reported as a recording/export failure.
        assertTrue(
            "a CRASH-only log must raise the card so the modal stays reachable (#387)",
            state.hasCrashOrErrorEntries,
        )
        assertFalse(
            "a CRASH-only log must not be reported as a recording failure (#371's premise)",
            state.hasVisibleErrors,
        )
        assertEqualsCounts(state, errorCount = 0, crashCount = 1)
    }

    @Test
    fun `ERROR plus CRASH raises the card and keeps the error counter from summing crashes`() {
        val state = ErrorLogUiState(
            entries = listOf(
                entry("SINK_OPEN_FAILED", ErrorLogSeverity.ERROR),
                entry("uncaught", ErrorLogSeverity.CRASH),
            ),
        )

        assertTrue(state.hasCrashOrErrorEntries)
        assertTrue(state.hasVisibleErrors)
        assertEqualsCounts(state, errorCount = 1, crashCount = 1)
    }

    private fun assertEqualsCounts(state: ErrorLogUiState, errorCount: Int, crashCount: Int) {
        org.junit.Assert.assertEquals(
            "errorCount must never include CRASH entries",
            errorCount,
            state.errorCount,
        )
        org.junit.Assert.assertEquals(
            "crashCount must never include ERROR entries",
            crashCount,
            state.crashCount,
        )
    }
}
