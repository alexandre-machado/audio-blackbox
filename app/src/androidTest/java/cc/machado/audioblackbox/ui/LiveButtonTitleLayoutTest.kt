package cc.machado.audioblackbox.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.ui.dashboard.*
import cc.machado.audioblackbox.ui.theme.AudioBlackboxTheme
import cc.machado.audioblackbox.ui.theme.AvionicsPanelButton
import cc.machado.audioblackbox.ui.theme.PANEL_BUTTON_ICON_TEST_TAG
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class LiveButtonTitleLayoutTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Oracle controls use the real Surface > Row > Column button and the same absence
     * assertions as the dashboard test. Restoring a decorative icon must fail independently
     * of title wrapping. No reflection into Compose's private layout tree is needed.
     */
    @Test fun realButtonWithDecorativeIconFailsBothAbsenceChecks() {
        composeRule.setContent {
            AudioBlackboxTheme {
                AvionicsPanelButton(
                    label = "Live", onClick = {}, modifier = Modifier.testTag("icon-control"),
                    icon = { modifier ->
                        Icon(painterResource(R.drawable.ic_pause), contentDescription = null,
                            modifier = modifier)
                    },
                )
            }
        }
        assertThrows(AssertionError::class.java) { assertNoIconSlot("icon-control") }
        val painterFailure = assertThrows(AssertionError::class.java) { assertNoPainter("icon-control") }
        assertEquals("icon-control: must not draw an icon", painterFailure.message)
    }

    /** Oracle: the one-line assertion rejects a real wrapping Text with auto-size disabled.
     * This control deliberately gives the old title style only 60dp of available width.
     */
    @Test fun wrappingTitleFailsSingleLineAssertion() {
        composeRule.setContent {
            AudioBlackboxTheme {
                Box(Modifier.requiredSize(60.dp, 240.dp).testTag("wrap-control")) {
                    Text("SPEICHERN & BEENDEN", fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, letterSpacing = 0.06.sp)
                }
            }
        }
        val lines = titleLayout("wrap-control", "SPEICHERN & BEENDEN").lineCount
        assertTrue("Control must actually wrap", lines > 1)
        val failure = assertThrows(AssertionError::class.java) { assertSingleLine("wrap-control", lines) }
        assertTrue(failure.message.orEmpty().contains("wrap-control: title must occupy exactly one line"))
    }

    private data class Scenario(
        val locale: String = "en", val recording: Boolean = false,
        val width: Int = 280, val fontScale: Float = 1f,
    )

    /** Independent oracles: no icon slot/painter, and BOTH titles have exactly one line.
     * Widths are the row's available widths, not the outer window: card/gutter padding would
     * otherwise keep a 412dp window below the 400dp breakpoint and vacuously test stacking.
     * 280dp is the existing 360dp compact-window content width. 412/480dp exercise two-up.
     * These controls and the matrix execute in Tier 1 CI; compilation is not execution.
     */
    @Test fun liveTitleMatchesSaveInBothStatesAndAllLocales() {
        val scenario = mutableStateOf(Scenario())
        composeRule.setContent {
            val (tag, recording, width, scale) = scenario.value
            val config = Configuration(LocalConfiguration.current).apply {
                setLocale(Locale.forLanguageTag(tag))
                fontScale = scale
            }
            val context = LocalContext.current.createConfigurationContext(config)
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalContext provides context,
                LocalDensity provides Density(LocalDensity.current.density, scale),
            ) {
                AudioBlackboxTheme {
                    Box(Modifier.requiredSize(width.dp, COMPACT_WINDOW_HEIGHT)) {
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
        for (width in listOf(280, 412, 480)) {
            for (scale in listOf(1f, 1.3f)) {
                for (tag in listOf("en", "pt-BR", "de", "es", "fr", "it")) {
                    for (recording in listOf(false, true)) {
                        composeRule.runOnIdle { scenario.value = Scenario(tag, recording, width, scale) }
                        val case = "$tag recording=$recording width=$width scale=$scale"
                        val liveTag = if (recording) FORWARD_STOP_BUTTON_TEST_TAG else FORWARD_START_BUTTON_TEST_TAG
                        assertNoIconSlot(liveTag)
                        assertNoPainter(liveTag)
                        val saveBounds = composeRule.onNodeWithTag(SAVE_BUTTON_TEST_TAG).getUnclippedBoundsInRoot()
                        val liveBounds = composeRule.onNodeWithTag(liveTag).getUnclippedBoundsInRoot()
                        if (width >= 400) {
                            assertEquals("$case: side-by-side tops", saveBounds.top, liveBounds.top)
                            assertTrue("$case: side-by-side columns", liveBounds.left >= saveBounds.right)
                        } else {
                            assertTrue("$case: stacked rows", liveBounds.top >= saveBounds.bottom)
                        }
                        val config = Configuration(composeRule.activity.resources.configuration).apply {
                            setLocale(Locale.forLanguageTag(tag))
                        }
                        val resources = composeRule.activity.createConfigurationContext(config).resources
                        val saveTitle = resources.getString(R.string.dashboard_panel_save_label).uppercase()
                        val liveTitle = resources.getString(forwardActionResources(recording).label).uppercase()
                        for ((buttonTag, title) in listOf(SAVE_BUTTON_TEST_TAG to saveTitle, liveTag to liveTitle)) {
                            val layout = titleLayout(buttonTag, title)
                            assertSingleLine("$case: $buttonTag", layout.lineCount)
                            val size = layout.layoutInput.style.fontSize.value
                            assertTrue("$case: title size $size stays within 11–13sp", size in 11f..13f)
                            if (layout.isLineEllipsized(0)) {
                                assertEquals("$case: shrink to minimum before ellipsis", 11f, size, 0.01f)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun titleLayout(buttonTag: String, title: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNode(
            hasText(title) and hasAnyAncestor(hasTestTag(buttonTag)),
            useUnmergedTree = true,
        ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertEquals("Expected one title layout for $buttonTag", 1, results.size)
        return results.single()
    }

    private fun assertSingleLine(case: String, lines: Int) {
        assertEquals("$case: title must occupy exactly one line", 1, lines)
    }

    private fun assertNoIconSlot(buttonTag: String) {
        composeRule.onAllNodes(
            hasAnyAncestor(hasTestTag(buttonTag)) and hasTestTag(PANEL_BUTTON_ICON_TEST_TAG),
            useUnmergedTree = true,
        ).assertCountEquals(0)
        composeRule.onAllNodes(
            hasAnyAncestor(hasTestTag(buttonTag)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    // Secondary painter check uses public modifier inspection on semantics-bearing nodes.
    // The slot supplies a tag even for null-description icons. The primary slot check above
    // also catches callers that forget to apply that modifier. No private child traversal.
    private fun assertNoPainter(buttonTag: String) {
        val nodes = composeRule.onAllNodes(hasAnyAncestor(hasTestTag(buttonTag)),
            useUnmergedTree = true).fetchSemanticsNodes()
        composeRule.runOnIdle {
            assertFalse("$buttonTag: must not draw an icon", nodes.any { node ->
                node.layoutInfo.getModifierInfo().any { it.modifier.javaClass.simpleName.contains("Painter") }
            })
        }
    }
}
