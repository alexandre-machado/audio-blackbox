package cc.machado.audioblackbox.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.ui.dashboard.*
import cc.machado.audioblackbox.ui.theme.AudioBlackboxTheme
import cc.machado.audioblackbox.ui.theme.AvionicsCard
import cc.machado.audioblackbox.ui.theme.SCREEN_GUTTER
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LiveButtonTitleLayoutTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Control for the absence oracle: a null-description icon must still be detected. */
    @Test fun decorativeIconIsDetectedWithoutImageSemantics() {
        composeRule.setContent {
            Box(Modifier.testTag("icon-control")) {
                Icon(painterResource(R.drawable.ic_pause), contentDescription = null)
            }
        }
        val layout = composeRule.onNodeWithTag("icon-control", useUnmergedTree = true)
            .fetchSemanticsNode().layoutInfo
        composeRule.runOnIdle {
            assertTrue("The painter oracle must detect decorative icons", hasPainter(layout))
        }
    }

    /** Oracle: the actual dashboard action has no image (including decorative painters), and
     * its title occupies the same single line as Save at 360dp / fontScale 1.3 in every locale.
     * Mutation: restore the old iconRes parameter, Icon branch and Live call-site argument.
     * The painter assertion must fail even though Icon(contentDescription = null) has no Role.
     * Run that production mutation in CI; no local emulator is required by this source change.
     */
    @Test fun liveTitleMatchesSaveInBothStatesAndAllLocales() {
        val scenario = mutableStateOf("en" to false)
        composeRule.setContent {
            val (tag, recording) = scenario.value
            val config = Configuration(LocalConfiguration.current).apply {
                setLocale(Locale.forLanguageTag(tag))
                fontScale = 1.3f
            }
            val context = LocalContext.current.createConfigurationContext(config)
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalContext provides context,
                LocalDensity provides Density(LocalDensity.current.density, 1.3f),
            ) {
                AudioBlackboxTheme {
                    Box(Modifier.requiredSize(COMPACT_WINDOW_WIDTH, COMPACT_WINDOW_HEIGHT)
                        .padding(SCREEN_GUTTER)) {
                        AvionicsCard {
                            ActionPanelRow(
                                uiState = dashboardFixture().copy(
                                    saveState = SaveUiState.Idle,
                                    forwardRecordingState = if (recording) {
                                        ForwardRecordingUiState.Recording("live.m4a", 42_000L)
                                    } else ForwardRecordingUiState.Idle,
                                ),
                                onSaveRecent = {},
                                onStartForwardRecording = {},
                                onStopForwardRecording = {},
                            )
                        }
                    }
                }
            }
        }
        for (tag in listOf("en", "pt-BR", "de", "es", "fr", "it")) {
            for (recording in listOf(false, true)) {
                composeRule.runOnIdle { scenario.value = tag to recording }
                val case = "$tag recording=$recording"
                val liveTag = if (recording) FORWARD_STOP_BUTTON_TEST_TAG else FORWARD_START_BUTTON_TEST_TAG
                composeRule.onAllNodes(
                    hasAnyAncestor(hasTestTag(liveTag)) and
                        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image),
                    useUnmergedTree = true,
                ).assertCountEquals(0)
                val live = composeRule.onNodeWithTag(liveTag, useUnmergedTree = true)
                val liveLayout = live.fetchSemanticsNode().layoutInfo
                composeRule.runOnIdle {
                    assertFalse("$case: Live must not draw an icon", hasPainter(liveLayout))
                }
                val config = Configuration(composeRule.activity.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(tag))
                }
                val resources = composeRule.activity.createConfigurationContext(config).resources
                val saveTitle = resources.getString(R.string.dashboard_panel_save_label).uppercase()
                val liveTitle = resources.getString(forwardActionResources(recording).label).uppercase()
                val saveLines = titleLines(SAVE_BUTTON_TEST_TAG, saveTitle)
                val liveLines = titleLines(liveTag, liveTitle)
                assertEquals("$case: Save positive control", 1, saveLines)
                assertEquals("$case: Live title must match Save", saveLines, liveLines)
            }
        }
    }

    private fun titleLines(buttonTag: String, title: String): Int {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNode(
            hasText(title) and hasAnyAncestor(hasTestTag(buttonTag)),
            useUnmergedTree = true,
        ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals("Expected one title layout for $buttonTag", 1, results.size)
        return results.single().lineCount
    }

    // Decorative Material Icon deliberately omits image semantics. Inspect its paint modifier
    // too, otherwise restoring the original null-description icon would pass the absence test.
    private fun hasPainter(layout: LayoutInfo): Boolean {
        if (layout.getModifierInfo().any { it.modifier.javaClass.simpleName.contains("Painter") }) {
            return true
        }
        // LayoutInfo exposes modifiers but not children. Compose's internal SemanticsInfo
        // exposes ALL layout children, including nodes with no semantics. Fail loudly if that
        // inspection API changes; never silently skip the decorative-icon assertion.
        val children = layout.javaClass.getMethod("getChildrenInfo").invoke(layout) as List<*>
        return children.any { hasPainter(it as LayoutInfo) }
    }
}
