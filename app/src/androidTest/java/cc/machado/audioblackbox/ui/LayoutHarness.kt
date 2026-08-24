package cc.machado.audioblackbox.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.ui.dashboard.DashboardScreen
import cc.machado.audioblackbox.ui.dashboard.DashboardViewModel
import cc.machado.audioblackbox.ui.dashboard.SaveUiState
import cc.machado.audioblackbox.ui.settings.SettingsScreen
import cc.machado.audioblackbox.ui.settings.SettingsViewModel
import cc.machado.audioblackbox.ui.theme.AudioBlackboxTheme

/**
 * Shared fixture for the instrumented screen-layout harness (issue #78): the app's real window
 * shell ([AppScaffold] -- the same composable [MainActivity] uses) wrapped around the app's real,
 * stateless screens, driven by hand-built UI states instead of live ViewModels.
 *
 * Why not `createAndroidComposeRule<MainActivity>()`: [MainActivity]'s content slot is reachable
 * only past onboarding (RECORD_AUDIO + POST_NOTIFICATIONS granted, three persisted preference
 * flags), and once there it builds ViewModels that bind to
 * [cc.machado.audioblackbox.service.RecorderService] and DataStore. None of that participates in
 * the layout contract under test, and all of it is a source of flakiness and of test failures that
 * have nothing to do with layout. What does participate -- [AppScaffold]'s `Scaffold` +
 * `bottomBar`-slot structure and each screen's scrollable `Column` -- is production code, used here
 * exactly as [MainActivity] uses it (same `Column(Modifier.padding(innerPadding).fillMaxSize())`
 * around the destination). If that structure regresses, these tests fail.
 */
@Composable
internal fun HarnessApp(initialDestination: Destination) {
    var selected by rememberSaveable { mutableStateOf(initialDestination) }
    AudioBlackboxTheme {
        AppScaffold(
            selectedDestination = selected,
            onSelectDestination = { selected = it },
            showBottomBar = true,
        ) { innerPadding ->
            // Mirrors MainActivity's content slot exactly (minus the battery-optimization banner,
            // which is a sibling above the destination and does not affect the bar/content
            // relationship being asserted).
            Column(modifier = Modifier.fillMaxSize()) {
                when (selected) {
                    Destination.DASHBOARD -> DashboardScreen(
                        uiState = dashboardFixture(),
                        onToggleEngine = {},
                        onSelectWindow = {},
                        onDismissSaveNotice = {},
                        contentPadding = innerPadding,
                    )
                    Destination.SETTINGS -> SettingsScreen(
                        uiState = settingsFixture(),
                        onDecrement = {},
                        onIncrement = {},
                        onApply = {},
                        onConfirmRetentionWindowChange = {},
                        onCancelRetentionWindowChange = {},
                        onAcknowledgeClampNotice = {},
                        contentPadding = innerPadding,
                    )
                }
            }
        }
    }
}

/**
 * [HarnessApp] in a layout box of a fixed, deliberately small size.
 *
 * The bug this harness exists to catch is only observable when a screen's content is taller than
 * the space left for it: a bar drawn *over* the content (PR #74's first commit) and a bar the
 * framework *reserves space for* (the fix) are indistinguishable while everything happens to fit on
 * screen. The CI emulator is a 411x731dp Pixel profile, on which both screens fit -- so asserting
 * at the emulator's natural size would produce three tests that pass against the broken layout,
 * which is exactly the vacuous-test failure mode this issue was filed to avoid.
 *
 * [COMPACT_WINDOW_HEIGHT] is therefore fixed here rather than inherited from the device, which also
 * means these assertions do not silently change meaning if the CI AVD profile is ever changed. The
 * size is not artificial: a 360x320dp content area is roughly what a phone gives in a split-screen
 * window, or in landscape at a large font scale.
 */
@Composable
internal fun CompactHarnessApp(initialDestination: Destination) {
    Box(modifier = Modifier.requiredSize(COMPACT_WINDOW_WIDTH, COMPACT_WINDOW_HEIGHT)) {
        HarnessApp(initialDestination)
    }
}

internal val COMPACT_WINDOW_WIDTH = 360.dp
internal val COMPACT_WINDOW_HEIGHT = 320.dp

/**
 * Paused, buffer full, with a successful save notice showing -- picked so the dashboard has both a
 * primary action (the Save button, mid-content) and a distinct last item (the notice's dismiss
 * button), and so the screen is comfortably taller than [COMPACT_WINDOW_HEIGHT].
 *
 * Deliberately not [CaptureState.Recording]: that state renders an infinite pulse animation, and
 * keeping a never-settling animation out of a layout test removes any question about what
 * `waitForIdle` is waiting for.
 */
internal fun dashboardFixture() = DashboardViewModel.mapUiState(
    captureState = CaptureState.Paused,
    bufferedMillis = 30L * 60_000L,
    capacityMinutes = 30,
    saveState = SaveUiState.Success("blackbox_2026-08-24_10-15-00_30min.m4a"),
)

/** A pending (not yet applied) retention change: shows the dirty notice and an enabled Apply
 * button, which is the screen's last item. No dialog -- a dialog would sit in its own window and
 * cover the bar, which is not what these tests are about. */
internal fun settingsFixture() = SettingsViewModel.mapUiState(
    committedMinutes = 30,
    pendingMinutes = 45,
    pendingConfirmationMinutes = null,
)
