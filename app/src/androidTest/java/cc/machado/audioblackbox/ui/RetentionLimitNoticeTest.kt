package cc.machado.audioblackbox.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.settings.ClampNotice
import cc.machado.audioblackbox.ui.settings.RetentionStepperUiState
import cc.machado.audioblackbox.ui.settings.SettingsScreen
import cc.machado.audioblackbox.ui.settings.SettingsUiState
import cc.machado.audioblackbox.ui.theme.AudioBlackboxTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetentionLimitNoticeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Oracle: the same polite semantics node exists while empty and carries the first notice.
     * Moving the parent inside limit?.let fails the initial fetch, before any text appears. */
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
        val node = compose.onNodeWithTag("retention_limit_notice")
        val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        node.assert(polite).assertHeightIsEqualTo(0.dp)
        val originalId = node.fetchSemanticsNode().id
        compose.runOnIdle { limit.value = ClampNotice(30, 15) }
        node.assert(polite).assertTextEquals(compose.activity.getString(
            R.string.settings_retention_clamp_notice_body, 30, 15))
        assertEquals(originalId, node.fetchSemanticsNode().id)
        compose.runOnIdle { limit.value = null }
        node.assert(polite).assertHeightIsEqualTo(0.dp)
        assertEquals(originalId, node.fetchSemanticsNode().id)
    }
}
