package cc.machado.audioblackbox.ui.settings

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cc.machado.audioblackbox.ui.settingsFixture
import cc.machado.audioblackbox.ui.theme.AudioBlackboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Issue #444, Tier 1.
 *
 * ## The oracle
 * 1. Tapping the real "Support the project" row in [SettingsScreen] invokes `onSupportClick`
 *    exactly once (the Screen reports the tap; the Route owns the Intent).
 * 2. The real Android [Intent] that [supportIntent] builds is `ACTION_VIEW` on the literal sponsor
 *    URL, so a broken action or URL in production fails here on the real framework too.
 */
class SupportLinkInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun supportRowInvokesCallbackOnce() {
        var clicks = 0
        rule.setContent {
            AudioBlackboxTheme {
                SettingsScreen(settingsFixture(), {}, {}, {}, {}, onSupportClick = { clicks++ })
            }
        }
        rule.onNodeWithTag(SETTINGS_SUPPORT_ROW_TEST_TAG).performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun supportIntentIsActionViewOnSponsorUrl() {
        val intent = supportIntent()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://github.com/sponsors/alexandre-machado", intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }
}
