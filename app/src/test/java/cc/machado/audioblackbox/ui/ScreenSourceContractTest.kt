package cc.machado.audioblackbox.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two structural contracts issue #221 fixed, by reading the screen sources directly
 * rather than composing them. This repo deliberately has no Robolectric/Compose-test JVM tier (see
 * [cc.machado.audioblackbox.ui.CompactHarnessApp] and `ScreenLayoutTest`'s class doc) -- real
 * layout/measurement/rendered-colour questions belong to the instrumented, emulator-backed tier
 * this repo reserves for that (`ScreenLayoutTest`, extended for both of these same contracts). A
 * mechanical source check is what lets a fast, deterministic version of each contract run in the
 * `testDebugUnitTest` gate as well, without a device.
 *
 * Regression test for issue #221 -- verified to fail against the pre-fix source and pass after:
 * - Gallery previously nested its own `Scaffold`/`TopAppBar` inside the app's single outer
 *   `Scaffold` (`AppScaffold.kt`), a real violation of the one-`Scaffold` rule documented on
 *   `SettingsScreen.kt` (issues #73/#78).
 * - Every card-level primary CTA button (Save, forward-recording start/stop, Settings Apply) must
 *   share one colour, so none of them can drift back to the wallpaper-derived dynamic colour the
 *   others override away from.
 *
 * Issue #335 changed *how* the second bullet holds for Save/forward-recording: they moved off
 * `Material3`'s `Button` (and therefore off `colors = primaryCtaButtonColors()`) entirely, onto
 * [cc.machado.audioblackbox.ui.theme.AvionicsPanelButton] -- both calls in `ActionPanelRow` now
 * share that one primitive, which is a stronger guarantee than matching colour arguments ever was
 * (there is no second colour to drift to). [forwardRecordingButtonsShareTheSaveButtonsColourOverride]
 * checks that structural sharing instead of counting `primaryCtaButtonColors()` call sites.
 */
class ScreenSourceContractTest {

    private fun resolveFile(path: String): File {
        val direct = File(path)
        if (direct.isFile) return direct
        val fromModuleParent = File("app", path)
        if (fromModuleParent.isFile) return fromModuleParent
        return direct
    }

    private fun readSource(relativePath: String): String {
        val file = resolveFile(relativePath)
        assertTrue("expected source file to exist at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun galleryScreenDoesNotHostItsOwnScaffold() {
        val source = readSource("src/main/java/cc/machado/audioblackbox/ui/gallery/GalleryScreen.kt")
        assertFalse(
            "GalleryScreen.kt must not import material3.Scaffold -- it consumes AppScaffold's " +
                "single outer Scaffold instead (issues #73/#78, #221)",
            source.contains("import androidx.compose.material3.Scaffold"),
        )
        assertFalse(
            "GalleryScreen.kt must not construct its own Scaffold(...)",
            Regex("""\bScaffold\s*\(""").containsMatchIn(source),
        )
        assertFalse(
            "GalleryScreen.kt must not import material3.TopAppBar -- the app uses the shared " +
                "44dp badge header row instead (issue #221)",
            source.contains("import androidx.compose.material3.TopAppBar"),
        )
    }

    @Test
    fun forwardRecordingButtonsShareTheSaveButtonsColourOverride() {
        val dashboardSource = readSource("src/main/java/cc/machado/audioblackbox/ui/dashboard/DashboardScreen.kt")
        val panelButtonCallSites = Regex("""AvionicsPanelButton\s*\(""").findAll(dashboardSource).count()
        assertTrue(
            "expected the Save button and the Live (forward-recording) button to both be built " +
                "from AvionicsPanelButton in DashboardScreen.kt -- found $panelButtonCallSites call " +
                "site(s), expected at least 2 (issue #335 moved both off Material3's Button, so " +
                "there is no separate primaryCtaButtonColors() override left to drift apart)",
            panelButtonCallSites >= 2,
        )

        // `@sec`'s PR #343 round-2 finding: sharing one primitive is not by itself proof of shared
        // colour -- AvionicsPanelButton takes ledColor as a per-call argument, so this still checks
        // issue #221's actual concern (a CTA colour drifting off the app's brand colour) rather than
        // assuming the shared call site makes that impossible.
        val flightOrangeLedSites = Regex("""ledColor\s*=\s*FlightOrange""").findAll(dashboardSource).count()
        assertTrue(
            "expected both AvionicsPanelButton call sites (Save, Live) to set ledColor = " +
                "FlightOrange -- found $flightOrangeLedSites site(s), expected at least 2 " +
                "(issue #221's concern, restated for the #335 primitive: no card-level primary " +
                "action may drift off the app's brand colour)",
            flightOrangeLedSites >= 2,
        )
    }
}
