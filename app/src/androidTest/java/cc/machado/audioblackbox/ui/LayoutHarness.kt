package cc.machado.audioblackbox.ui

import androidx.compose.foundation.layout.Box
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
 * exactly as [MainActivity] uses it: the destination is passed straight into [AppScaffold]'s
 * content slot, and the `innerPadding` handling that keeps content clear of the bar is
 * [AppScaffold]'s own. Nothing about the bar/content relation is re-implemented here -- an earlier
 * version of this file copied [MainActivity]'s padded `Column`, which left that half of PR #74's
 * fix untested (PR #87 review, `@rev`). If the structure regresses, these tests fail.
 *
 * What is not modelled: the battery-optimization banner, a sibling above the destination that does
 * not participate in the bar/content relation, and the onboarding branch.
 */
@Composable
internal fun HarnessApp(
    initialDestination: Destination,
    dashboardUiState: cc.machado.audioblackbox.ui.dashboard.DashboardUiState = dashboardFixture(),
    galleryUiState: cc.machado.audioblackbox.ui.gallery.GalleryUiState = galleryFixture(),
) {
    var selected by rememberSaveable { mutableStateOf(initialDestination) }
    AudioBlackboxTheme {
        AppScaffold(
            selectedDestination = selected,
            onSelectDestination = { selected = it },
            showBottomBar = true,
        ) {
            when (selected) {
                Destination.DASHBOARD -> DashboardScreen(
                    uiState = dashboardUiState,
                    onToggleEngine = {},
                    onSaveRecent = {},
                    onDismissSaveNotice = {},
                    onStartForwardRecording = {},
                    onStopForwardRecording = {},
                    onDismissForwardNotice = {},
                )
                Destination.GALLERY -> cc.machado.audioblackbox.ui.gallery.GalleryScreen(
                    uiState = galleryUiState,
                    onPlayPauseClicked = {},
                    onSeek = {},
                    onShareClicked = {},
                    onDeleteRequested = {},
                    onDeleteConfirmed = {},
                    onDeleteCancelled = {},
                    onDeleteErrorDismissed = {},
                )
                Destination.SETTINGS -> SettingsScreen(
                    uiState = settingsFixture(),
                    onSelectQualityPreset = {},
                    onDecrement = {},
                    onIncrement = {},
                    onApply = {},
                    onConfirmRetentionWindowChange = {},
                    onCancelRetentionWindowChange = {},
                    onAcknowledgeClampNotice = {},
                )
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
internal fun CompactHarnessApp(
    initialDestination: Destination,
    dashboardUiState: cc.machado.audioblackbox.ui.dashboard.DashboardUiState = dashboardFixture(),
    galleryUiState: cc.machado.audioblackbox.ui.gallery.GalleryUiState = galleryFixture(),
) {
    Box(modifier = Modifier.requiredSize(COMPACT_WINDOW_WIDTH, COMPACT_WINDOW_HEIGHT)) {
        HarnessApp(initialDestination, dashboardUiState, galleryUiState)
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

/**
 * Paused with 0ms in buffer so save window chips are disabled with insufficient-buffer helper text.
 */
internal fun emptyBufferDashboardFixture() = DashboardViewModel.mapUiState(
    captureState = CaptureState.Paused,
    bufferedMillis = 0L,
    capacityMinutes = 30,
    saveState = SaveUiState.Idle,
)

/** A pending (not yet applied) retention change: shows the dirty notice and an enabled Apply
 * button, which is the screen's last item. No dialog -- a dialog would sit in its own window and
 * cover the bar, which is not what these tests are about. */
internal fun settingsFixture() = SettingsViewModel.mapUiState(
    committedMinutes = 30,
    pendingMinutes = 45,
)

internal fun galleryFixture() = cc.machado.audioblackbox.ui.gallery.GalleryUiState(
    isLoading = false,
    items = emptyList(),
)

// --- Showcase fixtures (issue #151) ----------------------------------------------------------
//
// A SECOND, separate fixture set, used only by ScreenshotCaptureTest's store captures. The layout
// fixtures above must not be reused for that and must not be "improved" to look nicer: every one of
// them is deliberately an awkward state -- paused, buffer full, a notice open, a dirty pending
// change -- chosen so the content overflows COMPACT_WINDOW_HEIGHT. That overflow is the only
// condition under which issue #78's bug (a bottom bar drawn *over* content rather than reserving
// space for it) is observable at all, so softening them would leave ScreenLayoutTest passing
// against the broken layout. That is exactly the vacuous-test failure #78 was filed to avoid.
//
// These, by contrast, answer a different question: what should someone see in the Play Store
// listing before they have ever run the app. Every state below is one the app really reaches; none
// is an error, an interruption or an empty state. The store screenshots previously came from the
// layout fixtures, which is why the published listing led with "Recording is paused because the
// microphone is being used by a call."

/** Recording normally, roughly two thirds of the way through a 30-minute buffer, nothing to dismiss. */
internal fun showcaseDashboardFixture() = DashboardViewModel.mapUiState(
    captureState = CaptureState.Recording,
    bufferedMillis = 18L * 60_000L,
    capacityMinutes = 30,
    saveState = SaveUiState.Idle,
    // A mid-scale level, so the meter renders as it does when the app is actually hearing
    // something. Needed because the CI emulator has no host audio device behind its virtual
    // microphone: the real meter (#175) correctly reports silence there, so an unset level
    // publishes a store screenshot reading "NO SIGNAL" beside an empty bar, which makes a working
    // app look broken.
    //
    // This is a rendering fixture, not the app: every other value here is fabricated too (the
    // buffered 18 minutes, the 30-minute capacity, the gallery's three recordings). What #175
    // ended was the *shipped app* inventing a level for real users. Depicting a representative
    // state in a store image is a different thing, and the meter still shows exactly what this
    // level would produce.
    inputLevel = SHOWCASE_INPUT_LEVEL,
)

/** ~0.62 lands around -23 dBFS on AudioLevel's scale: clearly active, nowhere near clipping. */
private const val SHOWCASE_INPUT_LEVEL = 0.62f

/** Settled on a 30-minute retention window: committed == pending, so no dirty notice and no dialog. */
internal fun showcaseSettingsFixture() = SettingsViewModel.mapUiState(
    committedMinutes = 30,
    pendingMinutes = 30,
)

/**
 * Three saved recordings rather than the empty state.
 *
 * Names follow the real `blackbox_<yyyy-MM-dd_HH-mm-ss>_<window>min.m4a` pattern, because
 * `GalleryViewModel` parses the capture time back out of the filename -- a name that did not match
 * would silently fall back to `DATE_ADDED` and render a date unrelated to the one in the name, which
 * is a detail a reader of the screenshot would notice even if a test would not. Sizes are consistent
 * with the durations at the app's default bitrate rather than round numbers.
 *
 * All three are [ItemPlaybackState.Stopped]: a screenshot of a mid-playback progress bar would
 * advertise a specific position that means nothing out of context.
 */
internal fun showcaseGalleryFixture(): cc.machado.audioblackbox.ui.gallery.GalleryUiState {
    fun item(name: String, minutes: Long, sizeBytes: Long, capturedAtMillis: Long) =
        cc.machado.audioblackbox.ui.gallery.RecordingListItem(
            recording = cc.machado.audioblackbox.ui.gallery.RecordingItem(
                uri = android.net.Uri.parse("content://media/external/audio/media/$capturedAtMillis"),
                displayName = name,
                mimeType = "audio/mp4",
                sizeBytes = sizeBytes,
                durationMillis = minutes * 60_000L,
                capturedAtMillis = capturedAtMillis,
            ),
            playback = cc.machado.audioblackbox.ui.gallery.ItemPlaybackState.Stopped,
        )
    return cc.machado.audioblackbox.ui.gallery.GalleryUiState(
        isLoading = false,
        items = listOf(
            // capturedAtMillis must agree with the timestamp in the name beside it. The screen
            // renders capturedAtMillis, so a mismatch shows a date the filename contradicts -- and
            // this fixture bypasses GalleryViewModel.mapRowsToItems, which is what would normally
            // derive one from the other. Values are those timestamps at UTC-3.
            item("blackbox_2026-08-27_14-32-10_30min.m4a", 30, 28_918_272L, 1_787_851_930_000L),
            item("blackbox_2026-08-26_09-05-44_15min.m4a", 15, 14_459_136L, 1_787_745_944_000L),
            item("blackbox_2026-08-24_19-48-02_30min.m4a", 30, 28_918_272L, 1_787_611_682_000L),
        ),
    )
}

/**
 * [HarnessApp] with the showcase fixtures and its destination driven from outside.
 *
 * Externally driven on purpose: [HarnessApp] keeps its own `rememberSaveable` selection and is
 * navigated by clicking the bottom bar, which means resolving each tab's label string. Under a
 * pinned locale those labels are not the ones the instrumentation's own context would return, so a
 * capture run would have to look them up through the localized context to find the node. Passing the
 * destination in removes that coupling entirely -- the capture never needs to know what any tab is
 * called in the language being captured.
 */
@Composable
internal fun ShowcaseApp(destination: Destination) {
    AudioBlackboxTheme {
        AppScaffold(
            selectedDestination = destination,
            onSelectDestination = {},
            showBottomBar = true,
        ) {
            when (destination) {
                Destination.DASHBOARD -> DashboardScreen(
                    uiState = showcaseDashboardFixture(),
                    onToggleEngine = {},
                    onSaveRecent = {},
                    onDismissSaveNotice = {},
                    onStartForwardRecording = {},
                    onStopForwardRecording = {},
                    onDismissForwardNotice = {},
                )
                Destination.GALLERY -> cc.machado.audioblackbox.ui.gallery.GalleryScreen(
                    uiState = showcaseGalleryFixture(),
                    onPlayPauseClicked = {},
                    onSeek = {},
                    onShareClicked = {},
                    onDeleteRequested = {},
                    onDeleteConfirmed = {},
                    onDeleteCancelled = {},
                    onDeleteErrorDismissed = {},
                )
                Destination.SETTINGS -> SettingsScreen(
                    uiState = showcaseSettingsFixture(),
                    onSelectQualityPreset = {},
                    onDecrement = {},
                    onIncrement = {},
                    onApply = {},
                    onConfirmRetentionWindowChange = {},
                    onCancelRetentionWindowChange = {},
                    onAcknowledgeClampNotice = {},
                )
            }
        }
    }
}
