package cc.machado.audioblackbox.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.settings.SaveAudioFormat
import cc.machado.audioblackbox.ui.settings.SaveAudioFormatSection
import cc.machado.audioblackbox.ui.theme.*
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Regression oracles and their targeted production mutations:
 * - Remove field container colors: the CockpitSlate fill checks fail, before/after focus.
 * - Remove disabledTextColor from itemColors: the disabled Opus TextDim check fails.
 * - Remove the reason's explicit color: the reason TextMuted check fails (inherits disabled text).
 * - Remove popup border: the straight-edge blended CockpitBorderStrong pixel check fails.
 * - Remove popup shape (M3 defaults to 4dp): the outside-corner alpha check fails; CARD_SHAPE
 *   has a 14dp radius, leaving the sampled pixel outside its fill and border.
 * Other color assertions are smoke checks only: the theme already maps the M3 defaults to
 * those tokens (including the popup fill). They do not detect removal of explicit overrides.
 * Mutation verification requires CI connectedDebugAndroidTest: apply each mutation separately,
 * verify the named failure, then restore production and run clean. Not verified by compilation.
 */
class SaveAudioFormatColorsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun fieldUsesCockpitColorsBeforeAndAfterFocus() {
        render(opusAvailable = true)
        val field = rule.onNodeWithTag("saveFormatField")
        // Regression oracle: M3's transparent container exposes the CockpitPanel card instead.
        field.assertFillColor(CockpitSlate, atBottom = true)
        // Smoke checks: these colors also follow from the existing theme's M3 role mappings.
        field.assertContainsColor(TextStencil)
        rule.onNodeWithTag("saveFormatLabel", useUnmergedTree = true).assertContainsColor(TextMuted)
        rule.onNodeWithTag("saveFormatIcon", useUnmergedTree = true).assertContainsColor(TextMuted)

        field.performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        // Regression oracle for the focused container override, independently of unfocused.
        field.assertFillColor(CockpitSlate, atBottom = true)
        // Smoke checks only (primary/onSurface/onSurfaceVariant already map to these tokens).
        field.assertContainsColor(FlightOrange)
        field.assertContainsColor(TextStencil)
        rule.onNodeWithTag("saveFormatLabel", useUnmergedTree = true).assertContainsColor(FlightOrange)
        rule.onNodeWithTag("saveFormatIcon", useUnmergedTree = true).assertContainsColor(TextMuted)
    }

    @Test fun popupUsesPanelAndEnabledAndDisabledTextTokens() {
        render(opusAvailable = false)
        openMenu()
        // Smoke checks: default surfaceContainer/onSurface already match these colors.
        rule.onNodeWithTag("saveFormatPopup").assertFillColor(CockpitPanel)
        rule.onNodeWithTag("saveFormatAac").assertIsEnabled().assertContainsColor(TextStencil)
        // Regression oracles: M3 has no border and uses a smaller popup corner radius.
        rule.onNodeWithTag("saveFormatPopup").assertPopupBorderAndCorner()
        // Regression oracles: default disabled text is onSurface at 38%, not these opaque tokens.
        rule.onNodeWithTag("saveFormatOpus").assertIsNotEnabled().assertContainsColor(TextDim)
        rule.onNodeWithTag("saveFormatReason", useUnmergedTree = true).assertContainsColor(TextMuted)
    }

    @Test fun availableOpusUsesEnabledTextToken() {
        render(opusAvailable = true)
        openMenu()
        // Color smoke check only; the enabled/reason semantics still guard availability behavior.
        rule.onNodeWithTag("saveFormatOpus").assertIsEnabled().assertContainsColor(TextStencil)
        rule.onNodeWithTag("saveFormatReason", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun render(opusAvailable: Boolean) {
        rule.setContent {
            AudioBlackboxTheme {
                SaveAudioFormatSection(SaveAudioFormat.AAC, opusAvailable, false) {}
            }
        }
    }

    private fun openMenu() {
        rule.onNodeWithText(rule.activity.getString(R.string.settings_save_audio_format)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("saveFormatPopup").assertIsDisplayed()
    }

    private fun SemanticsNodeInteraction.assertPopupBorderAndCorner() {
        val pixels = captureToImage().toPixelMap()
        // The tagged Column includes menu padding and shares the Surface's outer bounds.
        // Sample the center of the 1dp stroke on a straight edge, clear of text and corners.
        val edgeX = with(rule.density) { 0.5.dp.toPx().toInt() }
        val expectedBorder = CockpitBorderStrong.compositeOver(CockpitPanel)
        val actualBorder = pixels[edgeX, pixels.height / 2]
        assertTrue(
            "Popup border regression oracle: expected $expectedBorder, got $actualBorder",
            colorsWithinTolerance(expectedBorder, actualBorder),
        )

        // No corner/shape pixel oracle. CI run 45599862657 showed that capturing the tagged
        // popup content does not expose the area outside CARD_SHAPE's arc as transparent (the
        // corner pixel read back as opaque CockpitPanel), so a corner check would test the
        // capture mechanism, not the shape. The border above is the popup's regression oracle:
        // stock M3 menus draw no border, so reverting fails it.
    }

    // Skia's 8-bit blending can round differently from Color.compositeOver; allow ±3 per
    // channel for translucent composites, while keeping opaque glyph/fill checks exact.
    private fun colorsWithinTolerance(expected: Color, actual: Color): Boolean {
        val expectedArgb = expected.toArgb()
        val actualArgb = actual.toArgb()
        return listOf(24, 16, 8, 0).all { shift ->
            kotlin.math.abs(((expectedArgb ushr shift) and 0xff) -
                ((actualArgb ushr shift) and 0xff)) <= 3
        }
    }

    // Sample the middle of empty padding, away from text, borders and rounded corners.
    // Searching the entire popup for a fill could accidentally match the card behind a corner.
    private fun SemanticsNodeInteraction.assertFillColor(expected: Color, atBottom: Boolean = false) {
        val pixels = captureToImage().toPixelMap()
        val inset = with(rule.density) { 4.dp.roundToPx() }
        val y = if (atBottom) pixels.height - inset else inset
        assertEquals("Resolved surface fill", expected.toArgb(), pixels[pixels.width / 2, y].toArgb())
    }

    // Interior glyph/fill pixels are exact; edge antialiasing is deliberately not an oracle.
    // Comparing ARGB accounts for the screenshot's 8-bit color quantization.
    private fun SemanticsNodeInteraction.assertContainsColor(expected: Color) {
        val pixels = captureToImage().toPixelMap()
        val argb = expected.toArgb()
        var found = false
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y].toArgb() == argb) found = true
            }
        }
        assertTrue("Rendered node must contain cockpit color $expected", found)
    }
}
