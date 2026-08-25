package cc.machado.audioblackbox.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import cc.machado.audioblackbox.ui.dashboard.DASHBOARD_PADDING
import cc.machado.audioblackbox.ui.dashboard.ENGINE_SWITCH_TEST_TAG
import java.util.Locale
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.machado.audioblackbox.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.absoluteValue

/**
 * The screen-layout harness (issue #78), established after PR #74 shipped a floating bottom bar
 * that covered the dashboard's primary action on a real device while both merge gates approved and
 * CI stayed green -- because nothing in either test tier could see where anything was drawn.
 *
 * These are instrumented Compose UI tests rather than JVM/Robolectric ones on purpose: the defect
 * class is "a floating element covering content", which is a question about real measurement, and
 * this is the only tier that measures the same way the device does. See the PR for the full
 * argument.
 *
 * Every assertion here is the same shape -- some node's bottom edge against the floating bar's top
 * edge, taken from real measured bounds -- because that single relation is what PR #74 broke.
 * [CompactHarnessApp] explains why they run in a fixed small window instead of the emulator's
 * natural size.
 *
 * Synchronization: none of these tests sleeps, retries or polls. `performScrollTo`/`performClick`
 * and the bounds getters all go through the Compose test rule, which does not return until the
 * composition/layout has settled; the fixtures deliberately contain no indefinite animation.
 */
@RunWith(AndroidJUnit4::class)
class ScreenLayoutTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int): String = composeRule.activity.getString(id)

    /**
     * Oracle: fails if the dashboard's primary action can come to rest inside the floating bar's
     * rectangle -- i.e. if the bar is drawn over the content area instead of the content area being
     * shrunk to exclude it. Under PR #74's pre-fix layout the scroll viewport ran the full height of
     * the window, so scrolling the Save button into view parked it under the bar; under the current
     * layout the viewport ends above the bar and it cannot.
     */
    @Test
    fun dashboardPrimaryActionIsNotObscuredByTheFloatingBottomBar() {
        composeRule.setContent { CompactHarnessApp(Destination.DASHBOARD) }

        // `hasClickAction` disambiguates the Save button from the save card's title, which is the
        // same string.
        val primaryAction = composeRule.onNode(
            hasText(string(R.string.dashboard_save_button)) and hasClickAction(),
        )
        primaryAction.performScrollTo()
        primaryAction.assertIsDisplayed()
        primaryAction.assertClearOfBottomBar("the dashboard's primary action (Save recent audio)")
    }

    /**
     * Expressed through the last item of each screen, because that is the element a user most often
     * has to reach, but note what is actually pinned: `performScrollTo` scrolls the *minimum* needed
     * to bring a node into the scroll viewport, so a node that lands under the bar proves the
     * viewport itself extends under the bar. It does not prove the node was unreachable -- under PR
     * #74's pre-fix layout the extra bottom `contentPadding` meant scrolling further would have
     * brought it clear, which is why this is not named after reachability (PR #87 review, `@rev`).
     *
     * Oracle: fails if either screen's scroll viewport ends below the floating bar's top edge, i.e.
     * if the content area a screen scrolls within runs to the window's bottom edge with the bar
     * floating on top of it.
     */
    @Test
    fun scrollViewportOfEachScreenEndsAboveTheFloatingBottomBar() {
        composeRule.setContent { CompactHarnessApp(Destination.DASHBOARD) }

        val dashboardLastItem = composeRule.onNodeWithText(string(R.string.dashboard_save_notice_dismiss))
        dashboardLastItem.performScrollTo()
        dashboardLastItem.assertIsDisplayed()
        dashboardLastItem.assertClearOfBottomBar(
            "the dashboard's last item (the save notice's OK button)",
            note = CAME_TO_REST,
        )

        settingsTab().performClick()

        val settingsLastItem = composeRule.onNodeWithText(string(R.string.settings_retention_apply_button))
        settingsLastItem.performScrollTo()
        settingsLastItem.assertIsDisplayed()
        settingsLastItem.assertClearOfBottomBar("the settings screen's last item (Apply)", note = CAME_TO_REST)
    }

    /**
     * Oracle: fails if tapping a bar item does not swap the rendered destination (or does not
     * announce the new selection), or if the destination that appears is laid out in a content area
     * that extends underneath the bar. The content area is read as the bounds of the destination's
     * scrollable container -- the region the screen believes it owns -- which under the pre-fix
     * layout ran to the bottom of the window with the bar floating on top of it, and now stops above
     * the bar for both destinations.
     */
    @Test
    fun bothDestinationsRenderAndSwitchWithTheirContentAreaClearOfTheBottomBar() {
        composeRule.setContent { CompactHarnessApp(Destination.DASHBOARD) }

        // `assertExists`, not `assertIsDisplayed`: in a 320dp-tall window most of a screen is below
        // the fold by design, and "the destination rendered" is a question about what is composed,
        // not about what happens to be on screen without scrolling.
        dashboardMarker().assertExists()
        dashboardTab().assertIsSelected()
        contentArea().assertClearOfBottomBar("the dashboard's content area")

        settingsTab().performClick()

        settingsMarker().assertExists()
        dashboardMarker().assertDoesNotExist()
        settingsTab().assertIsSelected()
        contentArea().assertClearOfBottomBar("the settings screen's content area")

        dashboardTab().performClick()

        dashboardMarker().assertExists()
        settingsMarker().assertDoesNotExist()
        dashboardTab().assertIsSelected()
        contentArea().assertClearOfBottomBar("the dashboard's content area after switching back")
    }

    /**
     * The one assertion that runs at the emulator's natural size in an **edge-to-edge** window, the
     * way [MainActivity] draws (`enableEdgeToEdge()`, `MainActivity.kt`). The three tests above run
     * in a fixed box inside a normally-inset window, where the decor has already consumed the
     * system-bar insets, so the inset arithmetic never executes there -- and inset double-counting
     * is a defect this tier was chosen to catch (PR #87 review, `@rev`; issue #80's mechanism).
     *
     * `enableEdgeToEdge` is deliberately *not* added to the compact tests: their box's bottom edge
     * is nowhere near the window's, so mixing real insets into that geometry would be incoherent.
     *
     * Oracle: fails if, with real system-bar insets in play, the content area either runs under the
     * bar (the space was not reserved) or is separated from it by more than the bar's own margin
     * (the space was reserved twice -- the double-count). The gap between the two is exactly
     * [BOTTOM_BAR_MARGIN] when each is counted once: `Scaffold` reserves the whole bar slot,
     * margin included, and the bar's visible surface sits inside that slot inset by that margin.
     */
    @Test
    fun contentAreaClearsTheBottomBarByExactlyItsMarginInAnEdgeToEdgeWindow() {
        composeRule.runOnUiThread { composeRule.activity.enableEdgeToEdge() }
        composeRule.setContent { HarnessApp(Destination.DASHBOARD) }

        contentArea().assertClearOfBottomBar("the dashboard's content area in an edge-to-edge window")

        val gap = bottomBarTop() - contentArea().getUnclippedBoundsInRoot().bottom
        assertTrue(
            "the gap between the content area and the floating bar is $gap, expected " +
                "$BOTTOM_BAR_MARGIN: anything larger means the bar's height was reserved more than " +
                "once (an inset/padding double-count), anything smaller that part of it was not " +
                "reserved at all.",
            (gap - BOTTOM_BAR_MARGIN).value.absoluteValue <= GAP_TOLERANCE_DP,
        )
    }

    /**
     * Oracle: fails if long state text in the engine switch row (e.g. the paused-state explanation,
     * which runs up to ~100+ characters in English and Portuguese) pushes the [androidx.compose.material3.Switch]
     * off the right edge of the screen. Under the pre-fix layout the label column had no weight
     * modifier in the [androidx.compose.foundation.layout.Row], allowing its unbounded measured
     * text width to push the switch beyond the window's right edge; under the fix the text column
     * has `Modifier.weight(1f)` with 16dp spacing so the text wraps naturally and the switch
     * remains fully within root bounds and clear of the screen edge.
     */
    @Test
    fun engineSwitchRemainsWithinRootBoundsAcrossLongStateStringsInCompactWidth() {
        composeRule.setContent { CompactHarnessApp(Destination.DASHBOARD) }

        val engineSwitch = composeRule.onNodeWithTag(ENGINE_SWITCH_TEST_TAG, useUnmergedTree = true)
        engineSwitch.assertIsDisplayed()

        val rootBounds = composeRule.onRoot().getUnclippedBoundsInRoot()
        val switchBounds = engineSwitch.getUnclippedBoundsInRoot()

        assertTrue(
            "the continuous recording switch is clipped off the right edge of the screen: " +
                "its right edge is at ${switchBounds.right}, but the root window right edge is at ${rootBounds.right}.",
            switchBounds.right <= rootBounds.right,
        )
        assertTrue(
            "the continuous recording switch is positioned before the left edge of the screen: " +
                "its left edge is at ${switchBounds.left}, but the root window left edge is at ${rootBounds.left}.",
            switchBounds.left >= rootBounds.left,
        )
        val expectedMaxRight = rootBounds.right - DASHBOARD_PADDING
        assertTrue(
            "the continuous recording switch right edge is at ${switchBounds.right}, expected to be at or within " +
                "$expectedMaxRight (accounting for $DASHBOARD_PADDING dashboard padding).",
            (switchBounds.right - expectedMaxRight).value <= GAP_TOLERANCE_DP,
        )
    }

    /**
     * Oracle: same defect as above, verified under the Portuguese (pt-BR) locale where the paused
     * state string ("Pausado — uma ligação está usando o microfone; a gravação será retomada
     * automaticamente quando ela terminar") is even longer than English.
     */
    @Test
    fun engineSwitchRemainsWithinRootBoundsInCompactWidthWithPortugueseLocale() {
        composeRule.setContent {
            val ptConfig = Configuration(LocalConfiguration.current).apply {
                setLocale(Locale.forLanguageTag("pt-BR"))
            }
            val ptContext = LocalContext.current.createConfigurationContext(ptConfig)
            CompositionLocalProvider(
                LocalConfiguration provides ptConfig,
                LocalContext provides ptContext,
            ) {
                CompactHarnessApp(Destination.DASHBOARD)
            }
        }

        val engineSwitch = composeRule.onNodeWithTag(ENGINE_SWITCH_TEST_TAG, useUnmergedTree = true)
        engineSwitch.assertIsDisplayed()

        val rootBounds = composeRule.onRoot().getUnclippedBoundsInRoot()
        val switchBounds = engineSwitch.getUnclippedBoundsInRoot()

        assertTrue(
            "the continuous recording switch is clipped off the right edge with pt-BR paused text: " +
                "its right edge is at ${switchBounds.right}, but the root window right edge is at ${rootBounds.right}.",
            switchBounds.right <= rootBounds.right,
        )
        val expectedMaxRight = rootBounds.right - DASHBOARD_PADDING
        assertTrue(
            "the continuous recording switch right edge is at ${switchBounds.right}, expected to be at or within " +
                "$expectedMaxRight (accounting for $DASHBOARD_PADDING dashboard padding).",
            (switchBounds.right - expectedMaxRight).value <= GAP_TOLERANCE_DP,
        )
    }

    // ---- helpers ----

    /** The bar's own [androidx.compose.material3.Surface], i.e. its visible rectangle. */
    private fun bottomBarTop(): Dp =
        composeRule.onNodeWithTag(FLOATING_BOTTOM_BAR_TEST_TAG).getUnclippedBoundsInRoot().top

    /**
     * The region [AppScaffold] leaves for screen content. Matched by tag rather than by "the one
     * node with a scroll action": that shortcut degrades into an "expected exactly one node" matcher
     * error the day a third destination brings a second scrollable (PR #87 review, `@rev`), and a
     * matcher error is the least useful thing to read out of a layout suite.
     */
    private fun contentArea(): SemanticsNodeInteraction = composeRule.onNodeWithTag(APP_CONTENT_TEST_TAG)

    /** `hasClickAction` separates the Settings *tab* from the Settings screen's own title, which is
     * the same word. */
    private fun tab(label: String): SemanticsNodeInteraction =
        composeRule.onNode(hasText(label) and hasClickAction())

    private fun dashboardTab() = tab(string(R.string.nav_dashboard_label))

    private fun settingsTab() = tab(string(R.string.nav_settings_label))

    /** Text that only the dashboard renders. */
    private fun dashboardMarker() =
        composeRule.onNodeWithText(string(R.string.dashboard_save_window_label))

    /** Text that only the settings screen renders. */
    private fun settingsMarker() =
        composeRule.onNodeWithText(string(R.string.settings_retention_title))

    private fun SemanticsNodeInteraction.assertClearOfBottomBar(what: String, note: String = "") {
        val barTop = bottomBarTop()
        val bottom = getUnclippedBoundsInRoot().bottom
        assertTrue(
            "$what is not clear of the floating bottom bar: it extends to $bottom, " +
                "but the bar's top edge is at $barTop.$note",
            bottom <= barTop,
        )
    }

    private companion object {
        /** Guards against reading a minimum-scroll landing spot as a reachability failure. */
        const val CAME_TO_REST =
            " It came to rest there after the smallest scroll that brings it into the viewport, so" +
                " this is a statement about where the viewport ends, not about whether the item" +
                " could be reached by scrolling further."

        /** Dp arithmetic on real measured bounds lands on fractional pixels; 1dp is far below the
         * bar height a double-count would add, so this loosens nothing that matters. */
        const val GAP_TOLERANCE_DP = 1f
    }
}
