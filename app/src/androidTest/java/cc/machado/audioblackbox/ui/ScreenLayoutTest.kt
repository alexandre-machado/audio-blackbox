package cc.machado.audioblackbox.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.machado.audioblackbox.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
     * Oracle: fails if the last item of either screen's scrollable content cannot be brought fully
     * clear of the floating bar -- the "you can scroll to it, but the bar sits on top of it when you
     * get there" failure. `performScrollTo` scrolls the minimum needed to bring the node into the
     * scroll viewport, so where the node ends up is exactly a statement about where that viewport
     * ends: at the window's bottom edge (pre-fix, under the bar) or above the bar (now).
     */
    @Test
    fun lastItemOfEachScreenCanBeScrolledClearOfTheFloatingBottomBar() {
        composeRule.setContent { CompactHarnessApp(Destination.DASHBOARD) }

        val dashboardLastItem = composeRule.onNodeWithText(string(R.string.dashboard_save_notice_dismiss))
        dashboardLastItem.performScrollTo()
        dashboardLastItem.assertIsDisplayed()
        dashboardLastItem.assertClearOfBottomBar("the dashboard's last item (the save notice's OK button)")

        settingsTab().performClick()

        val settingsLastItem = composeRule.onNodeWithText(string(R.string.settings_retention_apply_button))
        settingsLastItem.performScrollTo()
        settingsLastItem.assertIsDisplayed()
        settingsLastItem.assertClearOfBottomBar("the settings screen's last item (Apply)")
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

    // ---- helpers ----

    /** The bar's own [androidx.compose.material3.Surface], i.e. its visible rectangle. */
    private fun bottomBarTop(): Dp =
        composeRule.onNodeWithTag(FLOATING_BOTTOM_BAR_TEST_TAG).getUnclippedBoundsInRoot().top

    /** The visible destination's scrollable container -- each screen has exactly one. */
    private fun contentArea(): SemanticsNodeInteraction = composeRule.onNode(hasScrollAction())

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

    private fun SemanticsNodeInteraction.assertClearOfBottomBar(what: String) {
        val barTop = bottomBarTop()
        val bottom = getUnclippedBoundsInRoot().bottom
        assertTrue(
            "$what is not clear of the floating bottom bar: it extends to $bottom, " +
                "but the bar's top edge is at $barTop.",
            bottom <= barTop,
        )
    }
}
