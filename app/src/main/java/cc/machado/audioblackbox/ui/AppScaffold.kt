package cc.machado.audioblackbox.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * THROWAWAY EVIDENCE BRANCH -- DO NOT MERGE.
 *
 * PR #74's pre-fix layout (commit b84c307) transplanted into the extracted shell: the bar is an
 * overlay in a `Box` + `Alignment.BottomCenter`, measuring itself with `onSizeChanged` and feeding
 * its height back down as a `contentPadding` for each screen to apply inside its own scrollable
 * `Column`. Everything else -- the three tests and their assertions -- is unchanged from
 * test/78-layout-harness. This exists only to show the assertions go red against the layout they
 * were written to catch.
 */
@Composable
fun AppScaffold(
    selectedDestination: Destination,
    onSelectDestination: (Destination) -> Unit,
    showBottomBar: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    var barHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val contentPadding = PaddingValues(bottom = barHeight + BOTTOM_BAR_MARGIN)

    Scaffold(modifier = modifier) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            content(contentPadding)
            if (showBottomBar) {
                FloatingBottomBar(
                    selected = selectedDestination,
                    onSelect = onSelectDestination,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(BOTTOM_BAR_MARGIN)
                        .onSizeChanged { size -> barHeight = with(density) { size.height.toDp() } },
                )
            }
        }
    }
}

/** The floating bar's margin from the screen edges -- see [AppScaffold]. */
val BOTTOM_BAR_MARGIN = 16.dp
