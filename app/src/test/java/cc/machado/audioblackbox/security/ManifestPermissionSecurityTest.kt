package cc.machado.audioblackbox.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Security & Privacy manifest permission verification (issue #119, #148).
 *
 * Asserts that:
 * 1. The source manifest declares no network permissions directly and strips AD_ID.
 * 2. The **merged release manifest** (the output of AGP manifest merging across all dependencies)
 *    declares strictly zero network permissions (`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
 *    `CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`) and no `AD_ID`.
 *
 * Task coupling: `testDebugUnitTest` in `app/build.gradle.kts` explicitly depends on
 * `processReleaseMainManifest` to guarantee the merged release manifest is generated prior to test execution.
 * If the manifest file is missing, the test fails loudly with an explicit AssertionError rather than skipping.
 */
class ManifestPermissionSecurityTest {

    @Test
    fun mergedReleaseManifest_declaresZeroNetworkPermissions() {
        val manifestFile = resolveMergedReleaseManifest()

        val permissions = extractPermissions(manifestFile)

        assertFalse("Merged release manifest must NOT request android.permission.INTERNET", permissions.contains("android.permission.INTERNET"))
        assertFalse("Merged release manifest must NOT request android.permission.ACCESS_NETWORK_STATE", permissions.contains("android.permission.ACCESS_NETWORK_STATE"))
        assertFalse("Merged release manifest must NOT request android.permission.ACCESS_WIFI_STATE", permissions.contains("android.permission.ACCESS_WIFI_STATE"))
        assertFalse("Merged release manifest must NOT request android.permission.CHANGE_WIFI_STATE", permissions.contains("android.permission.CHANGE_WIFI_STATE"))
        assertFalse("Merged release manifest must NOT request android.permission.CHANGE_NETWORK_STATE", permissions.contains("android.permission.CHANGE_NETWORK_STATE"))

        val expectedPermissions = setOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "cc.machado.audioblackbox.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        assertTrue(
            "Merged release manifest permissions ($permissions) must be a subset of expected permissions ($expectedPermissions)",
            expectedPermissions.containsAll(permissions),
        )
    }

    @Test
    fun mergedReleaseManifest_doesNotContainAdvertisingIdPermission() {
        val manifestFile = resolveMergedReleaseManifest()
        val permissions = extractPermissions(manifestFile)
        assertFalse(
            "Merged release manifest must NOT contain com.google.android.gms.permission.AD_ID",
            permissions.contains("com.google.android.gms.permission.AD_ID"),
        )
    }

    private fun resolveMergedReleaseManifest(): File {
        val candidates = listOf(
            File("build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml"),
            File("app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw AssertionError(
                "Merged release manifest is missing! Expected at ${candidates.map { it.absolutePath }}. " +
                    "testDebugUnitTest must depend on processReleaseMainManifest.",
            )
    }
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

    @Test
    fun sourceManifest_explicitlyRemovesAdvertisingIdPermission() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue("Source manifest must exist at ${manifestFile.absolutePath}", manifestFile.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(manifestFile)
        val usesPermissions = doc.getElementsByTagName("uses-permission")
        var adIdRemoved = false

        for (i in 0 until usesPermissions.length) {
            val node = usesPermissions.item(i)
            val name = node.attributes.getNamedItem("android:name")?.nodeValue
            val toolsNode = node.attributes.getNamedItem("tools:node")?.nodeValue
            if (name == "com.google.android.gms.permission.AD_ID" && toolsNode == "remove") {
                adIdRemoved = true
            }
        }
        assertTrue("Source manifest must explicitly declare tools:node=\"remove\" for AD_ID permission", adIdRemoved)
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
            val toolsNode = node.attributes.getNamedItem("tools:node")?.nodeValue
            if (name != null && toolsNode != "remove") {
                result.add(name)
            }
        }
        return result
    }
}
