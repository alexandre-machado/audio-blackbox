package cc.machado.audioblackbox.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The app's window shell: the single [Scaffold] that hosts [FloatingBottomBar] in its `bottomBar`
 * slot and hands its content slot the `innerPadding` derived from that bar's real measured height
 * (issue #73 / PR #74 round 2).
 *
 * Extracted out of [MainActivity]'s `setContent` block by issue #78 so the layout contract this
 * shell exists to hold -- "content is never drawn under the bar" -- can be asserted by an
 * instrumented Compose UI test (`ScreenLayoutTest`) without also standing up permissions,
 * onboarding preferences, DataStore and a bound [cc.machado.audioblackbox.service.RecorderService].
 * The shell is exactly where PR #74's defect lived, so it is the thing worth testing directly;
 * [MainActivity] keeps everything else it had.
 *
 * Why the bar goes in the `bottomBar` slot rather than an overlay: PR #74's first commit
 * (`b84c307`) hand-rolled it as a `Box` + `Alignment.BottomCenter` + `onSizeChanged`
 * self-measurement that fed a `contentPadding` back down into each screen. That draws the bar
 * *over* the content area -- the padding only lets the very end of a scroll clear the bar, while
 * everything else scrolls underneath it -- and on the repo owner's S25 it covered the dashboard's
 * primary action outright. `Scaffold` instead measures this composable structurally on every layout
 * pass and shrinks the content slot by that measurement, so no scroll position can put content
 * under the bar. See [FloatingBottomBar]'s doc for the rest of that history.
 *
 * @param showBottomBar false while onboarding is still in progress -- the same gate the caller's
 *   content slot uses, so the bar and the content it insets never disagree about whether the bar
 *   exists.
 */
@Composable
fun AppScaffold(
    selectedDestination: Destination,
    onSelectDestination: (Destination) -> Unit,
    showBottomBar: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showBottomBar) {
                FloatingBottomBar(
                    selected = selectedDestination,
                    onSelect = onSelectDestination,
                    // A fixed visual margin from the already-safe edge. It is part of what
                    // Scaffold measures, so it is reserved in `innerPadding` too.
                    modifier = Modifier.padding(BOTTOM_BAR_MARGIN),
                )
            }
        },
        content = content,
    )
}

/** The floating bar's margin from the screen edges -- see [AppScaffold]. */
val BOTTOM_BAR_MARGIN = 16.dp
