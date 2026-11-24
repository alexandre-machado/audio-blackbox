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
import androidx.compose.ui.semantics.SemanticsActions
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
import cc.machado.audioblackbox.ui.theme.PANEL_ROW_STACKED_TAG
import cc.machado.audioblackbox.ui.theme.PANEL_ROW_SIDE_BY_SIDE_TAG
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import kotlin.math.abs
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class LiveButtonTitleLayoutTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Oracle: the original untagged, null-description Icon above the title changes both
     * height and title offset. Each assertion must reject it independently (Tier 1 CI).
     */
    @Test fun originalIconStructureFailsGeometryChecks() {
        composeRule.setContent {
            AudioBlackboxTheme {
                Column(Modifier.width(360.dp)) {
                    AvionicsPanelButton(label = "LIVE", caption = "00:42", onClick = {},
                        modifier = Modifier.testTag("baseline"))
                    LegacyControl("original-icon")
                }
            }
        }
        val heightFailure = assertThrows(AssertionError::class.java) {
            assertEqualHeights("baseline", "original-icon")
        }
        assertTrue(heightFailure.message.orEmpty().contains("button heights"))
        val offsetFailure = assertThrows(AssertionError::class.java) {
            assertEqualTitleOffsets("baseline", "LIVE", "original-icon", "LIVE")
        }
        assertTrue(offsetFailure.message.orEmpty().contains("title top offsets"))
    }

    @Composable
    private fun LegacyControl(tag: String) {
        Surface(onClick = {}, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(9.dp))
                Column(Modifier.weight(1f)) {
                    Icon(painterResource(R.drawable.ic_pause), contentDescription = null)
                    Text("LIVE", fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, letterSpacing = 0.06.sp)
                    Text("00:42", fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                        modifier = Modifier.padding(top = 1.dp))
                }
            }
        }
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
        val width: Int = 360, val fontScale: Float = 1f,
    )

    /** Oracle: both complete titles remain one line in the normal matrix. Geometry rejects
     * extra content above either title, independently of decorative-icon semantics.
     * Widths are row content widths so 412/480 exercise the actual two-column branch.
     * Tier 1 CI executes these assertions; host compilation is not execution.
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
        for (width in listOf(360, 412, 480)) {
            for (scale in (if (width == 360) listOf(1f, 1.3f, 2f) else listOf(1f, 1.3f))) {
                for (tag in listOf("en", "pt-BR", "de", "es", "fr", "it")) {
                    for (recording in listOf(false, true)) {
                        composeRule.runOnIdle { scenario.value = Scenario(tag, recording, width, scale) }
                        val case = "$tag recording=$recording width=$width scale=$scale"
                        val liveTag = if (recording) FORWARD_STOP_BUTTON_TEST_TAG else FORWARD_START_BUTTON_TEST_TAG
                        val saveBounds = composeRule.onNodeWithTag(SAVE_BUTTON_TEST_TAG).getUnclippedBoundsInRoot()
                        val liveBounds = composeRule.onNodeWithTag(liveTag).getUnclippedBoundsInRoot()
                        val config = Configuration(composeRule.activity.resources.configuration).apply {
                            setLocale(Locale.forLanguageTag(tag))
                        }
                        val resources = composeRule.activity.createConfigurationContext(config).resources
                        val saveTitle = resources.getString(R.string.dashboard_panel_save_label).uppercase()
                        val liveTitle = resources.getString(forwardActionResources(recording).label).uppercase()
                        // Re-measure each actual rendered title at 11sp using its resolved
                        // font and density, independently of the row's layout tag.
                        val layouts = listOf(SAVE_BUTTON_TEST_TAG to saveTitle, liveTag to liveTitle)
                            .map { (buttonTag, title) -> buttonTag to titleLayout(buttonTag, title) }
                        val density = layouts.first().second.layoutInput.density
                        val halfContent = with(density) { ((width.dp - 8.dp) / 2 - 47.dp).roundToPx() }
                        val fits = layouts.all { (_, layout) ->
                            val input = layout.layoutInput
                            val paragraph = androidx.compose.ui.text.ParagraphIntrinsics(
                                text = input.text.text,
                                style = input.style.copy(fontSize = 11.sp),
                                density = input.density,
                                fontFamilyResolver = input.fontFamilyResolver,
                            )
                            kotlin.math.ceil(paragraph.maxIntrinsicWidth).toInt() <= halfContent
                        }
                        val stacked = width < 400 || !fits
                        composeRule.onNodeWithTag(if (stacked) PANEL_ROW_STACKED_TAG else PANEL_ROW_SIDE_BY_SIDE_TAG)
                            .assertExists()
                        composeRule.onNodeWithTag(if (stacked) PANEL_ROW_SIDE_BY_SIDE_TAG else PANEL_ROW_STACKED_TAG)
                            .assertDoesNotExist()
                        if (stacked) assertTrue("$case: stacked rows", liveBounds.top >= saveBounds.bottom)
                        else {
                            assertEquals("$case: side-by-side tops", saveBounds.top, liveBounds.top)
                            assertTrue("$case: side-by-side columns", liveBounds.left >= saveBounds.right)
                        }
                        android.util.Log.i("LiveTitleMatrix", "$case stacked=$stacked")
                        for ((buttonTag, layout) in layouts) {
                            assertFalse("$case $buttonTag: no visual overflow", layout.hasVisualOverflow)
                            assertEquals("$case $buttonTag: complete title", layout.layoutInput.text.length,
                                layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
                            if (scale < 2f) assertSingleLine("$case: $buttonTag", layout.lineCount)
                            val size = layout.layoutInput.style.fontSize.value
                            assertTrue("$case: title size $size stays within 11–13sp", size in 11f..13f)
                        }
                        // Only a shared row promises equal geometry. Stacked buttons size
                        // independently, including when their localized captions wrap.
                        if (!stacked && layouts.all { it.second.lineCount == 1 }) {
                            assertEqualHeights(SAVE_BUTTON_TEST_TAG, liveTag, case)
                            assertEqualTitleOffsets(SAVE_BUTTON_TEST_TAG, saveTitle, liveTag, liveTitle, case)
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

    private fun assertEqualHeights(saveTag: String, liveTag: String, case: String = "control") {
        val save = composeRule.onNodeWithTag(saveTag).getUnclippedBoundsInRoot()
        val live = composeRule.onNodeWithTag(liveTag).getUnclippedBoundsInRoot()
        assertTrue("$case $liveTag: button heights must match within 1dp",
            abs((save.bottom - save.top).value - (live.bottom - live.top).value) <= 1f)
    }

    private fun assertEqualTitleOffsets(
        saveTag: String, saveTitle: String, liveTag: String, liveTitle: String,
        case: String = "control",
    ) {
        fun offset(tag: String, title: String): Float {
            val button = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            val text = composeRule.onNode(hasText(title) and hasAnyAncestor(hasTestTag(tag)),
                useUnmergedTree = true).getUnclippedBoundsInRoot()
            return (text.top - button.top).value
        }
        val saveOffset = offset(saveTag, saveTitle)
        val liveOffset = offset(liveTag, liveTitle)
        assertTrue("$case $liveTag: title top offsets must match within 1dp (save=$saveOffset, live=$liveOffset)",
            abs(saveOffset - liveOffset) <= 1f)
    }
}
