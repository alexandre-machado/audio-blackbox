package cc.machado.audioblackbox.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.ui.theme.CockpitBorderStrong
import cc.machado.audioblackbox.ui.theme.CockpitPanelRaised
import cc.machado.audioblackbox.ui.theme.FlightOrange
import cc.machado.audioblackbox.ui.theme.FlightOrangeContainer
import cc.machado.audioblackbox.ui.theme.TextDim

/** The app's screens, switched by [FloatingBottomBar]. Deliberately a hoisted
 * `enum` + `selected`/`onSelect` state, not `navigation-compose` -- three destinations do not justify
 * that dependency and its API surface. */
enum class Destination {
    DASHBOARD,
    GALLERY,
    SETTINGS,
}

/**
 * Identifies the bar's own [Surface] -- i.e. its visible rectangle, excluding the caller's margin
 * -- to the instrumented layout harness (issue #78, `ScreenLayoutTest`), which asserts that screen
 * content stays clear of exactly that rectangle. Deliberately a tag on the bar rather than on every
 * element the tests check: the bar is the one thing whose measured bounds every one of those
 * assertions needs, and deriving them from the nav items' bounds instead would silently exclude the
 * bar's own padding -- the strip PR #74's defect actually covered content with.
 */
const val FLOATING_BOTTOM_BAR_TEST_TAG = "floating_bottom_bar"

/**
 * A floating bottom navigation bar built entirely from stock Material 3 primitives --
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
    val navItemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = FlightOrange,
        selectedTextColor = FlightOrange,
        indicatorColor = FlightOrangeContainer,
        unselectedIconColor = TextDim,
        unselectedTextColor = TextDim,
    )

    Surface(
        modifier = modifier.testTag(FLOATING_BOTTOM_BAR_TEST_TAG),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        color = CockpitPanelRaised,
        border = BorderStroke(1.dp, CockpitBorderStrong),
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            windowInsets = WindowInsets(0, 0, 0, 0),
        ) {
            NavigationBarItem(
                selected = selected == Destination.DASHBOARD,
                onClick = { onSelect(Destination.DASHBOARD) },
                icon = { Icon(imageVector = Icons.Filled.Home, contentDescription = null) },
                label = {
                    Text(
                        text = stringResource(R.string.nav_dashboard_label).uppercase(),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                    )
                },
                colors = navItemColors,
            )
            NavigationBarItem(
                selected = selected == Destination.GALLERY,
                onClick = { onSelect(Destination.GALLERY) },
                icon = { Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null) },
                label = {
                    Text(
                        text = stringResource(R.string.nav_gallery_label).uppercase(),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                    )
                },
                colors = navItemColors,
            )
            NavigationBarItem(
                selected = selected == Destination.SETTINGS,
                onClick = { onSelect(Destination.SETTINGS) },
                icon = { Icon(imageVector = Icons.Filled.Settings, contentDescription = null) },
                label = {
                    Text(
                        text = stringResource(R.string.nav_settings_label).uppercase(),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                    )
                },
                colors = navItemColors,
            )
        }
    }
}
