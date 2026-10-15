package cc.machado.audioblackbox.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.ui.settings.SettingsScreen
import cc.machado.audioblackbox.ui.theme.AudioBlackboxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProCardScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // Oracle: the real Settings screen exposes all four messages; only priced Free exposes an
    // enabled purchase action, reachable in a 360x320 viewport, and dispatches the click once.
    @Test fun statesAndPurchaseInCompactSettings() {
        val configuration = android.content.res.Configuration(rule.activity.resources.configuration).apply {
            setLocale(java.util.Locale.forLanguageTag("pt-BR"))
        }
        val localized = rule.activity.createConfigurationContext(configuration)
        var state by mutableStateOf(ProCardState(ProCardStatus.FREE, "R$ 19,90"))
        var purchases = 0
        rule.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
            Box(Modifier.requiredSize(COMPACT_WINDOW_WIDTH, COMPACT_WINDOW_HEIGHT)) {
                AudioBlackboxTheme {
                    AppScaffold(Destination.SETTINGS, {}, showBottomBar = true) {
                        SettingsScreen(settingsFixture(), {}, {}, {}, {},
                            proState = state, onPurchase = { purchases++ })
                    }
                }
            }
            }
        }
        val unlock = localized.getString(R.string.pro_unlock)
        rule.onNodeWithText(unlock).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        val button = rule.onNodeWithText(unlock).getUnclippedBoundsInRoot()
        val bar = rule.onNodeWithTag(FLOATING_BOTTOM_BAR_TEST_TAG).getUnclippedBoundsInRoot()
        assertTrue("Purchase action must end above the floating bar", button.bottom <= bar.top)
        rule.runOnIdle { assertEquals(1, purchases); state = ProCardState(ProCardStatus.FREE) }
        rule.onNodeWithText(unlock).assertIsNotEnabled()
        for ((status, message) in listOf(
            ProCardStatus.PENDING to R.string.pro_pending,
            ProCardStatus.PURCHASED to R.string.pro_active,
            ProCardStatus.GRANDFATHERED to R.string.pro_early,
        )) {
            rule.runOnIdle { state = ProCardState(status) }
            rule.onNodeWithText(localized.getString(message)).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(unlock).assertDoesNotExist()
        }
    }

    // Issue #443. Oracle: when the engine reports a session that ended at the free limit, the
    // dashboard (compact 360x320dp viewport) says so with the saved file's name, and offers the
    // same purchase action, reachable and dispatched once. A user-stopped Success must show
    // neither the limit message nor the purchase action.
    @Test fun dashboardCapNoticeLinksToPurchase() {
        val savedName = "blackbox_2026-10-15_10-00-00.m4a"
        var forward by mutableStateOf<cc.machado.audioblackbox.ui.dashboard.ForwardRecordingUiState>(
            cc.machado.audioblackbox.ui.dashboard.ForwardRecordingUiState.Success(savedName, 8L, freeLimitReached = true),
        )
        var purchases = 0
        rule.setContent {
            Box(Modifier.requiredSize(COMPACT_WINDOW_WIDTH, COMPACT_WINDOW_HEIGHT)) {
                AudioBlackboxTheme {
                    AppScaffold(Destination.DASHBOARD, {}, showBottomBar = true) {
                        cc.machado.audioblackbox.ui.dashboard.DashboardScreen(
                            uiState = dashboardFixture().copy(forwardRecordingState = forward),
                            onToggleEngine = {}, onSaveRecent = {}, onDismissSaveNotice = {},
                            onStartForwardRecording = {}, onStopForwardRecording = {},
                            onDismissForwardNotice = {},
                            proState = ProCardState(ProCardStatus.FREE, "R$ 19,90"),
                            onPurchase = { purchases++ },
                        )
                    }
                }
            }
        }
        val limitMessage = rule.activity.getString(R.string.pro_limit_saved, savedName)
        val unlock = rule.activity.getString(R.string.pro_unlock)
        rule.onNodeWithText(limitMessage).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(unlock).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(1, purchases)
            forward = cc.machado.audioblackbox.ui.dashboard.ForwardRecordingUiState.Success(savedName, 8L)
        }
        rule.onNodeWithText(limitMessage).assertDoesNotExist()
        rule.onNodeWithText(unlock).assertDoesNotExist()
    }
}
