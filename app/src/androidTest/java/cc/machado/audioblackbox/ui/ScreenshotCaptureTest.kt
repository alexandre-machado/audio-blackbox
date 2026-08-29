package cc.machado.audioblackbox.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.machado.audioblackbox.R
import java.util.Locale
import org.junit.Assert.assertFalse
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

    // ScreenshotHostActivity, not the generic ComponentActivity createAndroidComposeRule would
    // otherwise launch: it carries Theme.AudioBlackbox (dark windowBackground) so a capture's
    // unpainted strips render the app's real dark ground instead of ui-test-manifest's own
    // placeholder theme, which is hardcoded light regardless of this app's manifest (PR #227
    // review, `@rev`/`@sec` -- see ScreenshotHostActivity's doc for the full history).
    @get:Rule
    val composeRule = createAndroidComposeRule<ScreenshotHostActivity>()

    @Test
    fun capturesAllDestinationsAtTheDeviceWindowSize() {
        // Mirrors MainActivity, which draws edge to edge -- without this the window would be
        // pre-inset by the system bars and the picture would not show what the user sees.
        composeRule.runOnUiThread { composeRule.activity.enableEdgeToEdge() }
        composeRule.setContent { HarnessApp(Destination.DASHBOARD) }

        capture("01-dashboard")
        galleryTab().performClick()
        capture("02-gallery")
        settingsTab().performClick()
        capture("03-settings")
    }

    @Test
    fun capturesAllDestinationsInTheCompactAssertionWindow() {
        composeRule.setContent { CompactHarnessApp(Destination.DASHBOARD) }

        capture("04-dashboard-compact")
        galleryTab().performClick()
        capture("05-gallery-compact")
        settingsTab().performClick()
        capture("06-settings-compact")
    }

    /**
     * Store-listing captures (issue #151): the showcase fixtures, rendered once per locale, with the
     * locale pinned in-process rather than inherited from the device.
     *
     * ## Why the locale is not set on the device
     * It cannot be, reliably. `settings get system system_locales` is what the resource resolver
     * actually follows, and on the CI AVD it persists across sessions: it survives
     * `settings put ... en-US` followed by a framework restart (the restart rewrites it back from
     * the running config), and it survives `settings delete` plus
     * `setprop persist.sys.locale en-US` plus `adb reboot`. Worse, `getprop persist.sys.locale`
     * happily reports the value you asked for while `am get-config` reports `pt-rBR` -- so the
     * device lies about it, and the failure is silent: a valid PNG in the wrong language. That is
     * how a previous capture run produced a pt-BR image sitting in the en-US store slot.
     *
     * `createConfigurationContext` sidesteps all of it. The locale comes from this file, the AVD's
     * state is irrelevant, and the result is reproducible on any device or emulator.
     *
     * ## Why both locales are captured in one test
     * So the last assertion can exist. The realistic failure here is not a crash, it is the two
     * locales coming out byte-identical -- which is exactly what happened once before and was caught
     * only by running `md5sum` by hand afterwards. Comparing them inside the test makes that a build
     * failure instead of something a human has to remember to check.
     */
    @Test
    fun capturesShowcaseInBothLocalesAndTheyDiffer() {
        composeRule.runOnUiThread { composeRule.activity.enableEdgeToEdge() }

        // showcaseDashboardFixture is CaptureState.Recording, which renders an infinite pulse
        // animation. Left on the automatic clock, waitForIdle would never see a settled frame.
        // Driving the clock by hand also makes the captured frame deterministic rather than
        // whichever point of the pulse the capture happened to land on.
        composeRule.mainClock.autoAdvance = false

        var localeTag by mutableStateOf(LOCALE_EN)
        var destination by mutableStateOf(Destination.DASHBOARD)

        composeRule.setContent {
            val localized = remember(localeTag) { localizedContext(localeTag) }
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
            ) {
                ShowcaseApp(destination)
            }
        }

        val written = mutableMapOf<String, ByteArray>()
        // Deliberately does NOT call Locale.setDefault. Providing LocalConfiguration covers resource
        // lookup, and that is all this app needs: the only numerics it formats -- the capture date
        // and the file size in GalleryFormat -- are built with a hardcoded pattern and an explicit
        // Locale.US, by choice ("a numeric date pattern, not translated"). So the JVM default locale
        // has no effect on any pixel captured here, and setting it would be a no-op dressed up as a
        // safeguard. If locale-sensitive formatting is ever introduced, this is the place to add it
        // back -- with a capture that demonstrably changes, not on the assumption that it must.
        for (tag in listOf(LOCALE_EN, LOCALE_PT)) {
            localeTag = tag
            for ((dest, name) in DESTINATIONS) {
                destination = dest
                // Two frames: one for the recomposition triggered by the state change above, one to
                // land on a fixed point of the pulse animation.
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.mainClock.advanceTimeBy(ANIMATION_SETTLE_MILLIS)
                written["$tag-$name"] = capture("$tag-$name")
            }
        }

        // The failure this guards against is silent: same picture, two store slots, one of them in
        // the wrong language. Compared per screen rather than in aggregate so the message names
        // which screen failed to translate.
        for ((_, name) in DESTINATIONS) {
            val en = written.getValue("$LOCALE_EN-$name")
            val pt = written.getValue("$LOCALE_PT-$name")
            assertFalse(
                "$name rendered byte-identically in $LOCALE_EN and $LOCALE_PT -- the locale was not " +
                    "applied, and one of these would ship to the wrong store listing",
                en.contentEquals(pt),
            )
        }
    }

    /**
     * A [Context] whose resources resolve in [tag], regardless of the device's own locale.
     *
     * Built from the *instrumentation* target context rather than the activity, so it carries the
     * app's resources and not the test APK's.
     */
    private fun localizedContext(tag: String): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(tag)))
        }
        return base.createConfigurationContext(config)
    }

    private fun galleryTab() =
        composeRule.onNode(hasText(composeRule.activity.getString(R.string.nav_gallery_label)) and hasClickAction())

    private fun settingsTab() =
        composeRule.onNode(hasText(composeRule.activity.getString(R.string.nav_settings_label)) and hasClickAction())

    /** Returns the PNG bytes written, so a caller can compare two captures without re-reading them. */
    private fun capture(name: String): ByteArray {
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
        return file.readBytes()
    }

    private companion object {
        /** Must match the path `scripts/ci/run-instrumented-tier.sh` pulls from. */
        const val SCREENSHOT_DIR_NAME = "screenshots"

        /** Must match the directory names under `distribution/metadata/android/`. */
        const val LOCALE_EN = "en-US"
        const val LOCALE_PT = "pt-BR"

        /** Long enough to be well past any entry transition, so the frame is a settled one. */
        const val ANIMATION_SETTLE_MILLIS = 2_000L

        /**
         * Capture order and file naming. The numeric prefixes match the store asset numbering
         * (`1_dashboard`, `2_gallery`, `3_settings`) so the mapping from capture to published asset
         * is positional and does not need a lookup table.
         */
        val DESTINATIONS = listOf(
            Destination.DASHBOARD to "01-dashboard",
            Destination.GALLERY to "02-gallery",
            Destination.SETTINGS to "03-settings",
        )
    }
}
