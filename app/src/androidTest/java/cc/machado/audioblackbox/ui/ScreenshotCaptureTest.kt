package cc.machado.audioblackbox.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.machado.audioblackbox.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders each destination on the CI emulator and writes a PNG of it, so a reviewer can *look* at
 * the screens from a pull request instead of needing the repo owner's phone (issue #78's owner
 * decision comment: assertions say pass/fail, they do not show a picture).
 *
 * These are captures, not golden-image comparisons. Nothing here fails on a pixel difference and
 * there are no goldens to maintain or to rot -- the only thing asserted is that a non-empty image
 * was actually produced and written, so a silently missing screenshot cannot pass as success.
 * `scripts/ci/run-instrumented-tier.sh` pulls the directory off the emulator and the workflow
 * uploads it as the `screen-captures` artifact.
 *
 * Two window sizes are captured: the emulator's own, which is what the app really looks like, and
 * the fixed compact window `ScreenLayoutTest` asserts in, which is what those assertions actually
 * saw -- useful precisely when one of them fails.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotCaptureTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capturesBothDestinationsAtTheDeviceWindowSize() {
        // Mirrors MainActivity, which draws edge to edge -- without this the window would be
        // pre-inset by the system bars and the picture would not show what the user sees.
        composeRule.runOnUiThread { composeRule.activity.enableEdgeToEdge() }
        composeRule.setContent { HarnessApp(Destination.DASHBOARD) }

        capture("01-dashboard")
        settingsTab().performClick()
        capture("02-settings")
    }

    @Test
    fun capturesBothDestinationsInTheCompactAssertionWindow() {
        composeRule.setContent { CompactHarnessApp(Destination.DASHBOARD) }

        capture("03-dashboard-compact")
        settingsTab().performClick()
        capture("04-settings-compact")
    }

    private fun settingsTab() =
        composeRule.onNode(hasText(composeRule.activity.getString(R.string.nav_settings_label)) and hasClickAction())

    private fun capture(name: String) {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("captured a zero-sized bitmap for $name", bitmap.width > 0 && bitmap.height > 0)

        // The app's internal files dir, pulled with `adb exec-out run-as` by the tier script: it
        // needs no storage permission and no assumptions about what adb may read under
        // /sdcard/Android/data on a given API level.
        val dir = File(composeRule.activity.filesDir, SCREENSHOT_DIR_NAME)
        assertTrue("could not create $dir", dir.isDirectory || dir.mkdirs())
        val file = File(dir, "$name.png")
        file.outputStream().use { out ->
            assertTrue("PNG encoding failed for $file", bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        }
        assertTrue("wrote an empty file at $file", file.length() > 0L)
    }

    private companion object {
        /** Must match the path `scripts/ci/run-instrumented-tier.sh` pulls from. */
        const val SCREENSHOT_DIR_NAME = "screenshots"
    }
}
