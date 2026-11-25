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
 * Oracle: actual rendered field, popup and item pixels contain the cockpit colors, including
 * opaque disabled text and a separately legible reason. These tests inspect production rendering,
 * not a parallel palette fixture: reverting field/item colors to defaults must fail.
 * CI mutations: remove the field colors argument (CockpitSlate assertion fails); restore it,
 * change the popup containerColor to CockpitPanelRaised (CockpitPanel fails), then restore it
 * and remove item colors (TextDim fails). The default popup fill already equals CockpitPanel.
 * Run each mutation separately with connectedDebugAndroidTest and revert before the final run.
 */
class SaveAudioFormatColorsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun fieldUsesCockpitColorsBeforeAndAfterFocus() {
        render(opusAvailable = true)
        val field = rule.onNodeWithTag("saveFormatField")
        field.assertFillColor(CockpitSlate, atBottom = true)
        field.assertContainsColor(CockpitBorderStrong.compositeOver(CockpitSlate))
        field.assertContainsColor(TextStencil)
        rule.onNodeWithTag("saveFormatLabel", useUnmergedTree = true).assertContainsColor(TextMuted)
        rule.onNodeWithTag("saveFormatIcon", useUnmergedTree = true).assertContainsColor(TextMuted)

        field.performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        field.assertFillColor(CockpitSlate, atBottom = true)
        field.assertContainsColor(FlightOrange)
        field.assertContainsColor(TextStencil)
        rule.onNodeWithTag("saveFormatLabel", useUnmergedTree = true).assertContainsColor(FlightOrange)
        rule.onNodeWithTag("saveFormatIcon", useUnmergedTree = true).assertContainsColor(TextMuted)
    }

    @Test fun popupUsesPanelAndEnabledAndDisabledTextTokens() {
        render(opusAvailable = false)
        openMenu()
        rule.onNodeWithTag("saveFormatPopup").assertFillColor(CockpitPanel)
        rule.onNodeWithTag("saveFormatAac").assertIsEnabled().assertContainsColor(TextStencil)
        rule.onNodeWithTag("saveFormatOpus").assertIsNotEnabled().assertContainsColor(TextDim)
        rule.onNodeWithTag("saveFormatReason", useUnmergedTree = true).assertContainsColor(TextMuted)
    }

    @Test fun availableOpusUsesEnabledTextToken() {
        render(opusAvailable = true)
        openMenu()
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
