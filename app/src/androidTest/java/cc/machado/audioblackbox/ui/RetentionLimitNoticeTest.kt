package cc.machado.audioblackbox.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.settings.ClampNotice
import cc.machado.audioblackbox.ui.settings.RetentionStepperUiState
import cc.machado.audioblackbox.ui.settings.SettingsScreen
import cc.machado.audioblackbox.ui.settings.SettingsUiState
import cc.machado.audioblackbox.ui.theme.AudioBlackboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetentionLimitNoticeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Oracle: the merged live region has real layout bounds before, during and after a notice.
     * An empty Box fails the bounds assertion even when its semantics id is stable. */
    @Test fun emptyLiveRegionSurvivesFirstNoticeAndClear() {
        val limit = mutableStateOf<ClampNotice?>(null)
        compose.setContent {
            AudioBlackboxTheme {
                SettingsScreen(
                    uiState = SettingsUiState(
                        retentionStepper = RetentionStepperUiState(15, 15, 29, true, true, false),
                        retentionLimit = limit.value,
                    ),
                    onSelectQualityPreset = {}, onDecrement = {}, onIncrement = {},
                    onAcknowledgeClampNotice = {},
                )
            }
        }
        val node = compose.onNodeWithTag("retention_limit_notice", useUnmergedTree = false)
        node.performScrollTo()
        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        fun assertVisibleRegion() {
            node.assert(polite)
            val size = node.fetchSemanticsNode().layoutInfo
            assertTrue("live region must have non-zero width", size.width > 0)
            assertTrue("live region must have non-zero height", size.height > 0)
        }
        assertVisibleRegion()
        val originalId = node.fetchSemanticsNode().id
        compose.runOnIdle { limit.value = ClampNotice(30, 15) }
        assertVisibleRegion()
        node.assertTextContains(compose.activity.getString(
            R.string.settings_retention_clamp_notice_body, 30, 15))
        assertEquals(originalId, node.fetchSemanticsNode().id)
        // REV-19 oracle: the live region holds only the ceiling and the notice, never the card's
        // stepper controls, so a +/- tap does not re-announce the whole card.
        val announced = node.fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }
            .joinToString(" ") { it.text }
        val decrementCd = compose.activity.getString(R.string.settings_retention_decrement_cd)
        assertTrue("live region must not contain the stepper: $announced", !announced.contains(decrementCd))
        assertTrue("live region must not merge stepper actions",
            node.fetchSemanticsNode().children.none { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) } &&
                !node.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick))
        compose.runOnIdle { limit.value = null }
        assertVisibleRegion()
        assertEquals(originalId, node.fetchSemanticsNode().id)
    }
}
