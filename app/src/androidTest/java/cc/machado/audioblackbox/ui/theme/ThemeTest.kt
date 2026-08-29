package cc.machado.audioblackbox.ui.theme

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #225: [AudioBlackboxTheme] must resolve to the fixed cockpit ground regardless of the
 * *device's* light/dark setting -- there is no light scheme and no `isSystemInDarkTheme()` branch
 * left to take.
 *
 * Before this issue, `Theme.kt` built its [androidx.compose.material3.ColorScheme] from
 * `isSystemInDarkTheme()` (plus the platform's dynamic colour on API 31+): forcing the device
 * configuration to `UI_MODE_NIGHT_NO` (light) would have resolved a stock Material light scheme --
 * a pale background, not [CockpitSlate] -- which is exactly the failure this test is written to
 * catch. Forcing `UI_MODE_NIGHT_YES` (dark) is included too so the test cannot pass by coincidence
 * of the emulator's default night-mode setting; both configurations must render the same fixed
 * cockpit background for the assertion to be meaningful.
 */
@RunWith(AndroidJUnit4::class)
class ThemeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun assertBackgroundIsCockpitGround(nightMode: Int) {
        composeRule.setContent {
            val config = Configuration(LocalConfiguration.current).apply { uiMode = nightMode }
            val configuredContext = LocalContext.current.createConfigurationContext(config)
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalContext provides configuredContext,
            ) {
                AudioBlackboxTheme {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .testTag(BACKGROUND_TEST_TAG),
                    )
                }
            }
        }

        val pixel = composeRule.onNodeWithTag(BACKGROUND_TEST_TAG)
            .captureToImage()
            .toPixelMap()[0, 0]

        assertEquals(
            "AudioBlackboxTheme's background must be the fixed cockpit ground (CockpitSlate) " +
                "regardless of the device's light/dark setting (uiMode=$nightMode), not a " +
                "system-derived light/dark Material scheme.",
            CockpitSlate,
            pixel,
        )
    }

    @Test
    fun backgroundIsCockpitGroundWhenDeviceIsInLightMode() {
        assertBackgroundIsCockpitGround(Configuration.UI_MODE_NIGHT_NO)
    }

    @Test
    fun backgroundIsCockpitGroundWhenDeviceIsInDarkMode() {
        assertBackgroundIsCockpitGround(Configuration.UI_MODE_NIGHT_YES)
    }

    private companion object {
        const val BACKGROUND_TEST_TAG = "theme_test_background"
    }
}
