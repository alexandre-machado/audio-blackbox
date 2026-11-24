package cc.machado.audioblackbox.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.ui.theme.AudioBlackboxTheme
import cc.machado.audioblackbox.ui.theme.AvionicsCard
import cc.machado.audioblackbox.ui.theme.AvionicsPanelButton
import cc.machado.audioblackbox.ui.theme.AvionicsPanelButtonRow
import cc.machado.audioblackbox.ui.theme.SCREEN_GUTTER
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LiveSaveCaptionLayoutTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val caption = mutableStateOf("")
    private val windowWidth = mutableStateOf(COMPACT_WINDOW_WIDTH)
    private var captionLayout: TextLayoutResult? = null

    private fun renderPanel() {
        composeRule.setContent {
            AudioBlackboxTheme {
                Box(Modifier.requiredSize(windowWidth.value, COMPACT_WINDOW_HEIGHT).padding(SCREEN_GUTTER)) {
                    AvionicsCard {
                        AvionicsPanelButtonRow(
                            save = {
                                AvionicsPanelButton(
                                    label = "SAVE", onClick = {}, enabled = false,
                                    caption = caption.value,
                                    // Read the caption Text's actual layout, not the simple-string
                                    // semantics path's reconstructed MultiParagraph + layout size.
                                    // ParagraphLayoutCache.slowCreateTextLayoutResultOrNull uses
                                    // max constraints for that paragraph but the original, smaller
                                    // layoutSize: even short text then reports didOverflowWidth.
                                    onCaptionTextLayout = { captionLayout = it },
                                )
                            },
                            live = { AvionicsPanelButton(label = "LIVE", onClick = {}) },
                        )
                    }
                }
            }
        }
    }

    /** Oracle: the real production caption is one complete, unclipped line. The screenshot's
     * buffered caption is a positive control, checked before live text in every locale/width.
     * At 360dp: 360 - 48 gutter - 32 card padding = 280dp stacked button, 233dp caption.
     * At 480dp: 400dp row => (400 - 8) / 2 = 196dp button, 149dp caption.
     * Both subtract 28dp button padding + 9dp LED + 10dp gap. No duplicate Text/style fixture.
     */
    @Test fun allLocalesFitCompactPanel() {
        renderPanel()
        for (width in listOf(COMPACT_WINDOW_WIDTH, 480.dp)) {
            composeRule.runOnIdle { windowWidth.value = width }
            for (tag in listOf("en", "pt-BR", "de", "es", "fr", "it")) {
                val config = Configuration(composeRule.activity.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(tag))
                }
                val context = composeRule.activity.createConfigurationContext(config)
                val texts = listOf(
                    "buffered control" to context.getString(R.string.dashboard_panel_save_caption_buffered, "18:00"),
                    "live" to context.getString(R.string.dashboard_panel_save_caption_live),
                )
                for ((kind, text) in texts) {
                    val layout = measure(text.uppercase()) // Same uppercase() as DashboardScreen.
                    val expectedSlot = with(composeRule.density) {
                        (if (width == COMPACT_WINDOW_WIDTH) 233.dp else 149.dp).roundToPx()
                    }
                    assertTrue("$tag $kind: actual caption slot ${layout.layoutInput.constraints}",
                        kotlin.math.abs(expectedSlot - layout.layoutInput.constraints.maxWidth) <= 2)
                    assertFits("$tag $kind at $width", layout)
                }
            }
        }
    }

    /** Input mutation: replace the caption with an unbroken long string in the same production
     * panel. Its real layout must clip and the SAME oracle must reject it, even if maxLines or
     * ellipsis is later introduced. This runs on the emulator alongside the positive controls.
     */
    @Test fun overlongCaptionIsRejectedByTheSameOracle() {
        renderPanel()
        val layout = measure("W".repeat(512))
        assertTrue("mutation must really clip: ${describe(layout)}", layout.hasVisualOverflow)
        val failure = assertThrows(AssertionError::class.java) { assertFits("mutation", layout) }
        assertTrue(failure.message.orEmpty().contains("one complete line"))
    }

    private fun measure(text: String): TextLayoutResult {
        composeRule.runOnIdle { caption.value = text }
        return composeRule.runOnIdle {
            checkNotNull(captionLayout).also {
                assertEquals("callback must describe the current caption", text, it.layoutInput.text.text)
            }
        }
    }

    private fun assertFits(case: String, layout: TextLayoutResult) {
        assertTrue("$case must occupy one complete line: ${describe(layout)}",
            layout.lineCount == 1 && !layout.hasVisualOverflow && !layout.isLineEllipsized(0) &&
                layout.getLineEnd(0) == layout.layoutInput.text.length)
    }

    private fun describe(layout: TextLayoutResult) =
        "lines=${layout.lineCount}, size=${layout.size}, paragraphWidth=${layout.multiParagraph.width}, " +
            "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, " +
            "constraints=${layout.layoutInput.constraints}"
}
