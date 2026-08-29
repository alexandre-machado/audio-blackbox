package cc.machado.audioblackbox.ui

import androidx.activity.ComponentActivity

/**
 * Activity used only by [ScreenshotCaptureTest], in place of the generic `ComponentActivity` that
 * `createAndroidComposeRule<ComponentActivity>()` would otherwise launch.
 *
 * `androidx.compose.ui:ui-test-manifest` (the `debugImplementation` that supplies a launchable
 * `ComponentActivity` for Compose UI tests) hardcodes that placeholder activity's own
 * `AndroidManifest.xml` entry to `android:theme="@android:style/Theme.Material.Light.NoActionBar"`
 * -- a manifest-merge-time override that wins over this app's `<application android:theme=
 * "@style/Theme.AudioBlackbox">` for that one activity, regardless of what the app itself declares.
 * Its light `android:windowBackground` then shows through any strip of the window that the Compose
 * content does not paint over (PR #227 review, `@rev`/`@sec`: a flat light-grey band at the very
 * bottom of every store screenshot, where the app's own dark `cockpit_bg` should be).
 *
 * This activity is declared in `app/src/androidTest/AndroidManifest.xml` with
 * `android:theme="@style/Theme.AudioBlackbox"` -- the same theme [cc.machado.audioblackbox.MainActivity]
 * carries in the shipped app -- so any unpainted strip renders the same dark `windowBackground` a
 * real user would see, instead of the test library's unrelated light one. It adds no behaviour of
 * its own; it exists purely to carry a different manifest theme than the library's placeholder.
 */
class ScreenshotHostActivity : ComponentActivity()
