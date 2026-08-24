package cc.machado.audioblackbox.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cc.machado.audioblackbox.R

/** The app's two screens (issue #73), switched by [FloatingBottomBar]. Deliberately a hoisted
 * `enum` + `selected`/`onSelect` state, not `navigation-compose` -- two destinations do not justify
 * that dependency and its API surface (see issue #73's explicit instruction not to add it). */
enum class Destination {
    DASHBOARD,
    SETTINGS,
}

/**
 * A floating bottom navigation bar (issue #73) built entirely from stock Material 3 primitives --
 * a [Surface] with a shape/elevation wrapping a stock [NavigationBar] -- rather than any
 * Material 3 Expressive component, which is explicitly out of scope for this project (see issue
 * #9). "Floating" here means visually: a shaped, elevated surface inset from the screen edges by
 * the caller's [modifier] (a fixed margin, e.g. `Modifier.padding(16.dp)`), not a distinct stock
 * component.
 *
 * [cc.machado.audioblackbox.ui.MainActivity] hosts this in [androidx.compose.material3.Scaffold]'s
 * own `bottomBar` slot, not a manually `align`ed/measured overlay -- PR #74 review found that a
 * hand-rolled `Box` + `onSizeChanged` approach left the bar drawn over Dashboard content on a real
 * device (a self-measurement race, not something the JVM-only preview/test tiers could catch).
 * `Scaffold`'s `bottomBar` slot measures this composable structurally, on every layout pass, and
 * derives its content `innerPadding` from that real measurement -- including this margin, since it
 * is part of what gets measured -- which is the framework-supported way to guarantee scrollable
 * content can never end up under a bottom bar, rather than a constant tuned to look right once.
 * [NavigationBar] itself also already applies [androidx.compose.material3.NavigationBarDefaults.windowInsets]
 * (bottom system-bar insets) internally, so the gesture/3-button navigation bar is handled the same
 * structural way, not via a second, hand-added insets consumption here.
 *
 * Selected-state accessibility ("selected state announced, not merely visually indicated", per
 * issue #73) comes for free from stock [NavigationBarItem]: its `selected` parameter drives the
 * same `Role.Tab` + selected semantics TalkBack already knows how to announce for tabs, so no
 * extra content description is added here -- doing so would risk exactly the "content description
 * that only repeats what is already conveyed" problem issue #66 was filed over.
 */
@Composable
fun FloatingBottomBar(
    selected: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
        ) {
            NavigationBarItem(
                selected = selected == Destination.DASHBOARD,
                onClick = { onSelect(Destination.DASHBOARD) },
                // `contentDescription = null`: the visible label text below already supplies the
                // accessible name for this item (NavigationBarItem merges icon + label semantics),
                // so a redundant description here would be exactly the "*_cd that only repeats its
                // label" issue #66 was filed over.
                icon = { Icon(imageVector = Icons.Filled.Home, contentDescription = null) },
                label = { Text(text = stringResource(R.string.nav_dashboard_label)) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer),
            )
            NavigationBarItem(
                selected = selected == Destination.SETTINGS,
                onClick = { onSelect(Destination.SETTINGS) },
                icon = { Icon(imageVector = Icons.Filled.Settings, contentDescription = null) },
                label = { Text(text = stringResource(R.string.nav_settings_label)) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer),
            )
        }
    }
}
