package cc.machado.audioblackbox.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.ui.theme.AudioBlackboxTheme
import cc.machado.audioblackbox.ui.theme.AvionicsPanelButton
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LiveSaveCaptionLayoutTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Oracle: real caption layout fits at the narrowest side-by-side panel width:
     * (400dp row - 8dp gap) / 2. No maxLines or ellipsis can hide overflow. */
    @Test fun allLocalesFitCompactPanel() {
        val caption = mutableStateOf("")
        composeRule.setContent {
            AudioBlackboxTheme {
                AvionicsPanelButton(label = "", onClick = {}, enabled = false,
                    caption = caption.value, modifier = Modifier.requiredWidth(196.dp))
            }
        }
        for (tag in listOf("en", "pt-BR", "de", "es", "fr", "it")) {
            val locale = Locale.forLanguageTag(tag)
            val config = Configuration(composeRule.activity.resources.configuration).apply { setLocale(locale) }
            val text = composeRule.activity.createConfigurationContext(config)
                .getString(R.string.dashboard_panel_save_caption_live).uppercase(locale)
            composeRule.runOnIdle { caption.value = text }
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(text, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("$tag caption must remain one line", 1, layouts.single().lineCount)
            assertFalse("$tag caption must not clip", layouts.single().hasVisualOverflow)
        }
    }
}
