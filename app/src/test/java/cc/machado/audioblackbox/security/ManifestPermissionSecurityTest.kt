package cc.machado.audioblackbox.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Security & Privacy Invariant Test (issue #119).
 *
 * Verifies that the app declares strictly ZERO network permissions (no INTERNET, no ACCESS_NETWORK_STATE),
 * guaranteeing that it is architecturally impossible for any telemetry or audio data to leave the device.
 *
 * The Oracle: If any dependency or manifest change introduces network permissions into either the source
 * or merged manifests, this test will fail immediately.
 */
class ManifestPermissionSecurityTest {

    @Test
    fun sourceManifestDeclaresNoNetworkPermissions() {
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

    @Test
    fun mergedManifestIfPresentDeclaresNoNetworkPermissions() {
        val mergedManifestDirs = listOf(
            File("build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"),
            File("build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml"),
        )

        for (manifestFile in mergedManifestDirs) {
            if (manifestFile.exists()) {
                val permissions = extractPermissions(manifestFile)
                assertFalse("Merged manifest ($manifestFile) must NOT request android.permission.INTERNET",
                    permissions.contains("android.permission.INTERNET"))
                assertFalse("Merged manifest ($manifestFile) must NOT request android.permission.ACCESS_NETWORK_STATE",
                    permissions.contains("android.permission.ACCESS_NETWORK_STATE"))
                assertFalse("Merged manifest ($manifestFile) must NOT request android.permission.ACCESS_WIFI_STATE",
                    permissions.contains("android.permission.ACCESS_WIFI_STATE"))
            }
        }
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
