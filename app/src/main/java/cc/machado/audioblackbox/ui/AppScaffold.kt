package cc.machado.audioblackbox.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The app's window shell: the single [Scaffold] that hosts [FloatingBottomBar] in its `bottomBar`
 * slot, *and* the single place the `innerPadding` that comes back from it is applied.
 *
 * Both halves live here on purpose. The layout contract PR #74 broke has two parts -- the bar must
 * be measured by the framework rather than by itself, and the space the framework reserves must
 * actually be consumed by the content -- and while the second half was a line in [MainActivity],
 * the instrumented harness had to write that line out by hand to test anything, which meant
 * deleting it from [MainActivity] broke nothing (PR #87 review, `@rev`). One owner, so the harness
 * drives the same code a user gets. This is why [content] is a [ColumnScope] slot and not a
 * `(PaddingValues) -> Unit`: there is no padding left for a caller to forget to apply.
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
 * Extracted out of [MainActivity]'s `setContent` block by issue #78 so that contract can be
 * asserted by an instrumented Compose UI test (`ScreenLayoutTest`) without also standing up
 * permissions, onboarding preferences, DataStore and a bound
 * [cc.machado.audioblackbox.service.RecorderService].
 *
 * @param showBottomBar false while onboarding is still in progress -- the same gate the caller's
 *   content uses, so the bar and the content it insets never disagree about whether the bar exists.
 * @param content laid out as a [Column] inside the space left over by the bar and the system bars.
 */
@Composable
fun AppScaffold(
    selectedDestination: Destination,
    onSelectDestination: (Destination) -> Unit,
    showBottomBar: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showBottomBar) {
                FloatingBottomBar(
                    selected = selectedDestination,
                    onSelect = onSelectDestination,
                    // A fixed visual margin above the system navigation bar (3-button or gesture).
                    // Scaffold measures the bar including this inset and reserves it in innerPadding.
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(BOTTOM_BAR_MARGIN),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .testTag(APP_CONTENT_TEST_TAG),
            content = content,
        )
    }
}

/** The floating bar's margin from the screen edges -- see [AppScaffold]. */
val BOTTOM_BAR_MARGIN = 16.dp

/**
 * Identifies the region [AppScaffold] leaves for screen content, i.e. what is left of the window
 * once the system bars and the floating bar have been reserved. The layout harness (issue #78)
 * measures this rectangle rather than looking for "the one scrollable node in the tree": that
 * shortcut turns into an unhelpful matcher error the day a third destination adds a second
 * scrollable (PR #87 review), and this is the region the assertions are actually about.
 */
const val APP_CONTENT_TEST_TAG = "app_content"
