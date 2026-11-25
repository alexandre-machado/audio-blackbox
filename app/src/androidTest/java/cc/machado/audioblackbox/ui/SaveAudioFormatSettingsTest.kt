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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
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

    // Oracle: only a mismatched active session produces the qualifier, with polite semantics;
    // the enabled dropdown still changes selection and the selected helper remains present.
    @Test fun liveFormatQualifierTracksMismatchAndStop() {
        val selected = mutableStateOf(SaveAudioFormat.AAC)
        val running = mutableStateOf<SaveAudioFormat?>(SaveAudioFormat.AAC)
        rule.setContent {
            AudioBlackboxTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SaveAudioFormatSection(selected.value, true, false, running.value) { selected.value = it }
                }
            }
        }
        rule.onNodeWithTag("saveFormatNextRecording").assertDoesNotExist()
        rule.onNodeWithTag("saveFormatField").assertIsEnabled().performClick()
        rule.onNodeWithTag("saveFormatOpus").assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("saveFormatNextRecording").performScrollTo().assertIsDisplayed()
            .assertTextEquals(rule.activity.getString(R.string.settings_format_next_recording_aac))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion,
                androidx.compose.ui.semantics.LiveRegionMode.Polite))
        rule.onNodeWithText(rule.activity.getString(R.string.settings_format_opus_helper)).assertExists()
        rule.runOnIdle { selected.value = SaveAudioFormat.AAC }
        rule.onNodeWithTag("saveFormatNextRecording").assertDoesNotExist()
        rule.runOnIdle { running.value = SaveAudioFormat.OPUS }
        rule.onNodeWithTag("saveFormatNextRecording")
            .assertTextEquals(rule.activity.getString(R.string.settings_format_next_recording_opus))
        rule.runOnIdle { running.value = null }
        rule.onNodeWithTag("saveFormatNextRecording").assertDoesNotExist()
    }

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
                            SaveAudioFormatSection(selected.value, false, false, SaveAudioFormat.AAC) { selected.value = it }
                        }
                    }
                }
            }
        }
        rule.onNodeWithText(context.getString(R.string.settings_format_opus_helper))
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("saveFormatNextRecording").performScrollTo().assertIsDisplayed()
            .assertTextEquals(context.getString(R.string.settings_format_next_recording_aac))
        assertEquals(SaveAudioFormat.OPUS, selected.value)
        rule.onNodeWithText(context.getString(R.string.settings_format_opus_unavailable))
            .performScrollTo().assertIsDisplayed()
        // Proven on CI (run 45460759832, b96b392): clicking the field's label opens the menu and
        // the AAC item is then found by its text alone. Only the touch injection INTO the popup
        // item failed there, so the item is clicked through its OnClick semantics action instead.
        rule.onNodeWithText(context.getString(R.string.settings_save_audio_format))
            .performScrollTo().performClick()
        // The menu is a separate popup window: the test's LocalContext/LocalConfiguration override
        // (pt-BR) does not reach it, so its items render in the activity's own locale. CI run
        // 45521665809 printed the open popup with "AAC (default)". Match what the popup shows.
        val aacText = rule.activity.getString(R.string.settings_format_aac)
        try {
            rule.waitUntil(timeoutMillis = 5_000) { rule.onAllNodesWithText(aacText).fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: ComposeTimeoutException) {
            // logcat is not uploaded by CI; put the whole semantics tree (every root, including
            // popups) into the failure message so the CI log shows whether the menu opened at all.
            val roots = rule.onAllNodes(isRoot(), useUnmergedTree = true)
            val tree = (0 until roots.fetchSemanticsNodes().size).joinToString("\n---\n") {
                roots[it].printToString(maxDepth = Int.MAX_VALUE)
            }
            throw AssertionError("format menu never showed '$aacText'; semantics roots:\n$tree", timeout)
        }
        rule.onAllNodesWithText("Opus").onLast().assertIsNotEnabled()
        rule.onAllNodesWithText(aacText).onLast().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithTag("saveFormatNextRecording").assertDoesNotExist()
        assertEquals(SaveAudioFormat.AAC, selected.value)
        rule.onNodeWithText(context.getString(R.string.settings_format_aac_helper))
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.settings_format_opus_helper)).assertDoesNotExist()
    }
}
