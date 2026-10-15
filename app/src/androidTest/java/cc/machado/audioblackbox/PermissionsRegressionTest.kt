package cc.machado.audioblackbox

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device twin of the JVM `ManifestPermissionSecurityTest` allowlist: the installed APK must
 * request exactly the owner-approved permission set, nothing more and nothing less.
 *
 * History: this test was born for issue #119 ("no data of any kind may leave the device"), lifted
 * from the closed PR #122 per issue #129, and asserted that `INTERNET` and
 * `ACCESS_NETWORK_STATE` were absent. Issue #441 supersedes that rule, it does not delete it: the
 * optional Pro unlock uses Google Play Billing, whose library manifest merges in `INTERNET`,
 * `ACCESS_NETWORK_STATE` and `com.android.vending.BILLING`. Those three are now approved, and
 * every other network-adjacent permission (`ACCESS_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `AD_ID`,
 * ...) stays forbidden because exact set equality rejects anything not listed.
 *
 * Why this lives on device and not only in the JVM test: [PackageManager.getPackageInfo] reads
 * the permissions actually baked into the installed APK, i.e. the real output of manifest merging
 * for the variant CI installs, not the source `AndroidManifest.xml`. The source manifest never
 * declared `INTERNET` itself even while Firebase was linked (#119), so a source-only check is
 * vacuous for library-merged permissions.
 *
 * The androidx `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` is namespaced by the applicationId, which
 * carries the debug build type's suffix on the installed test target. It is derived from the real
 * [android.content.Context.getPackageName] rather than hardcoded, so this test checks the same set
 * as the JVM release allowlist regardless of which suffix the variant uses.
 */
@RunWith(AndroidJUnit4::class)
class PermissionsRegressionTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    // Oracle: any permission merged into, or dropped from, the installed APK (relative to the
    // #441 approved list) fails this equality, with both sets in the message.
    @Test
    fun installedManifestRequestsExactlyApprovedPermissions() {
        val packageName = context.packageName
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val requestedPermissions = info.requestedPermissions?.toSet().orEmpty()

        val expectedPermissions = setOf(
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.ACCESS_NETWORK_STATE,
            "com.android.vending.BILLING",
            android.Manifest.permission.RECORD_AUDIO,
            "android.permission.POST_NOTIFICATIONS",
            android.Manifest.permission.FOREGROUND_SERVICE,
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.Manifest.permission.RECEIVE_BOOT_COMPLETED,
            "$packageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )

        assertEquals(
            "installed APK permissions must exactly match the approved allowlist (#441, " +
                "superseding #119's no-network rule)",
            expectedPermissions,
            requestedPermissions,
        )
    }
}
