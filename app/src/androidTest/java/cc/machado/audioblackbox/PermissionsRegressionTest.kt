package cc.machado.audioblackbox

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for issue #119: no data of any kind may leave the device.
 *
 * `app/src/main/AndroidManifest.xml` never declared `INTERNET` itself; the permission that made
 * "no network egress" false was merged in by Firebase's own library manifest at build time. A
 * source-manifest-only check is therefore vacuous here -- it would have passed even while Firebase
 * Analytics was linked, since the source manifest never mentioned INTERNET in the first place.
 *
 * [PackageManager.getPackageInfo] reads the permissions actually baked into the installed APK,
 * i.e. the real output of manifest merging (the same content emitted to
 * `app/build/intermediates/merged_manifest*/`), not the source `AndroidManifest.xml`. That makes
 * this the durable form of the assertion: if any future dependency merges `INTERNET` or
 * `ACCESS_NETWORK_STATE` back in, this test fails loudly instead of leaving the privacy claim
 * silently false again.
 */
@RunWith(AndroidJUnit4::class)
class PermissionsRegressionTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun installedManifestDeclaresNoNetworkPermissions() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requestedPermissions = info.requestedPermissions?.toList().orEmpty()

        assertFalse(
            "merged manifest must not declare INTERNET -- no data may leave the device " +
                "(issue #119); requested permissions were: $requestedPermissions",
            requestedPermissions.contains(android.Manifest.permission.INTERNET),
        )
        assertFalse(
            "merged manifest must not declare ACCESS_NETWORK_STATE -- no data may leave the " +
                "device (issue #119); requested permissions were: $requestedPermissions",
            requestedPermissions.contains(android.Manifest.permission.ACCESS_NETWORK_STATE),
        )
    }
}
