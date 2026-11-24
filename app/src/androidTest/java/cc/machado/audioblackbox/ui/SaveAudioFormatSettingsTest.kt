package cc.machado.audioblackbox.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.settings.SaveAudioFormat
import cc.machado.audioblackbox.ui.settings.SaveAudioFormatSection
import cc.machado.audioblackbox.ui.theme.AudioBlackboxTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SaveAudioFormatSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** Oracle: restored unsupported OPUS remains visible; its menu choice is disabled, while
     * explicit AAC selection reaches the callback and changes the real selected-option copy.
     * Compact PT-BR at 150% font scale forces the content to scroll instead of fitting naturally.
     */
    @Test fun unsupportedRestoredOpusRequiresExplicitAacInCompactPortugueseSettings() {
        val configuration = Configuration(rule.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("pt-BR"))
        }
        val context = rule.activity.createConfigurationContext(configuration)
        val selected = mutableStateOf(SaveAudioFormat.OPUS)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalContext provides context,
                LocalDensity provides Density(density.density, 1.5f)) {
                AudioBlackboxTheme {
                    Box(Modifier.requiredSize(360.dp, 320.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            SaveAudioFormatSection(selected.value, false, false) { selected.value = it }
                        }
                    }
                }
            }
        }
        rule.onNodeWithText(context.getString(R.string.settings_format_opus_helper))
            .performScrollTo().assertIsDisplayed()
        assertEquals(SaveAudioFormat.OPUS, selected.value)
        rule.onNodeWithText(context.getString(R.string.settings_format_opus_unavailable))
            .performScrollTo().assertIsDisplayed()
        rule.onNode(hasText(context.getString(R.string.settings_format_opus)) and hasClickAction())
            .performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        // The menu lives in a popup window. Wait (bounded) for its item to exist, then fire the
        // item's real click action instead of injecting touch into the popup. The touch-injection
        // route failed on CI with the node gone mid-gesture, while the semantics route still
        // drives the production onClick -> onSelect wiring asserted below.
        val aacItem = hasText(context.getString(R.string.settings_format_aac)) and hasClickAction()
        try {
            rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodes(aacItem).fetchSemanticsNodes().size == 1 }
        } catch (timeout: ComposeTimeoutException) {
            val roots = rule.onAllNodes(isRoot(), useUnmergedTree = true)
            repeat(roots.fetchSemanticsNodes().size) { roots[it].printToLog("SaveAudioFormat") }
            throw timeout
        }
        rule.onAllNodes(hasText("Opus") and hasClickAction()).onLast().assertIsNotEnabled()
        rule.onNode(aacItem).assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(SaveAudioFormat.AAC, selected.value)
        rule.onNodeWithText(context.getString(R.string.settings_format_aac_helper))
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.settings_format_opus_helper)).assertDoesNotExist()
    }
}
