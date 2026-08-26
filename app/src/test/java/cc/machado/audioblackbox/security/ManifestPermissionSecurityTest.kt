package cc.machado.audioblackbox.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Security & Privacy defense-in-depth check for issue #119 -- NOT the load-bearing guarantee.
 *
 * This class used to carry two methods and neither reliably guarded the privacy claim published
 * in `docs/release/privacy-policy.md` ("It is physically impossible for the app to send data over
 * the network") -- see issue #129:
 *
 * - The source-manifest check (kept below, renamed) can never fail for the regression it was
 *   originally described as covering: `src/main/AndroidManifest.xml` never declared `INTERNET`
 *   even while Firebase Analytics was linked (issue #119's own finding) -- a dependency merges
 *   that permission in at build time, and this file cannot see that.
 * - The merged-manifest check has been deleted outright rather than kept `if (exists())`-guarded:
 *   on a clean checkout, or in any unit-test job where `processReleaseMainManifest`/
 *   `processDebugMainManifest` has not run, its loop body never executed and the test passed
 *   vacuously -- green while proving nothing. There is no unit-test-safe way to *require* that
 *   Gradle-generated file exists without coupling this test's execution order to another task, so
 *   this class does not attempt to reconstruct the merged-manifest guarantee at all anymore.
 *
 * The real guarantee -- the one that actually reads what the OS installed, which is the true
 * output of manifest merging and therefore cannot be fooled by any of the above -- is
 * [cc.machado.audioblackbox.PermissionsRegressionTest], an *instrumented* test lifted from the
 * closed PR #122 (`privacy/119-remove-firebase-analytics`). See its doc for why
 * `PackageManager.getPackageInfo(..., GET_PERMISSIONS)` on the installed package is the durable
 * form of this assertion.
 */
class ManifestPermissionSecurityTest {

    /**
     * Defense-in-depth only, explicitly not the regression guard: this cannot fail merely because
     * some future dependency merges `INTERNET` back in via its own manifest (see class doc) -- it
     * only catches the source manifest itself being edited to add a network permission directly.
     * Kept because that is still a real, if narrower, thing worth catching cheaply on every unit
     * test run, not because it substitutes for
     * [cc.machado.audioblackbox.PermissionsRegressionTest].
     */
    @Test
    fun sourceManifestDefenseInDepth_doesNotDirectlyDeclareNetworkPermissions() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue("Source manifest must exist at ${manifestFile.absolutePath}", manifestFile.exists())

        val permissions = extractPermissions(manifestFile)

        assertFalse("Source manifest must NOT request android.permission.INTERNET", permissions.contains("android.permission.INTERNET"))
        assertFalse("Source manifest must NOT request android.permission.ACCESS_NETWORK_STATE", permissions.contains("android.permission.ACCESS_NETWORK_STATE"))
        assertFalse("Source manifest must NOT request android.permission.ACCESS_WIFI_STATE", permissions.contains("android.permission.ACCESS_WIFI_STATE"))

        // Assert allowed set only
        val expectedPermissions = setOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED",
        )
        assertTrue("Manifest permissions ($permissions) must be a subset of expected permissions ($expectedPermissions)",
            expectedPermissions.containsAll(permissions))
    }

    private fun extractPermissions(file: File): Set<String> {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(file)
        val usesPermissions = doc.getElementsByTagName("uses-permission")
        val result = mutableSetOf<String>()

        for (i in 0 until usesPermissions.length) {
            val node = usesPermissions.item(i)
            val name = node.attributes.getNamedItem("android:name")?.nodeValue
            if (name != null) {
                result.add(name)
            }
        }
        return result
    }
}
