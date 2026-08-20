package cc.machado.audioblackbox

import android.app.Notification
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.service.RecorderNotification
import cc.machado.audioblackbox.service.RecorderService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cheap, fast, mic-free instrumented checks for two structural properties a JVM unit test cannot
 * reach: the manifest's declared foreground-service type (needs the real `PackageManager`) and
 * the built notification's "not swipe-dismissible" flag (needs a real `Context` to construct a
 * `Notification` at all). Neither starts the service or touches the microphone, so these run in
 * well under a second and never flake on timing.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundServiceDeclarationTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun manifestDeclaresMicrophoneForegroundServiceType() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, RecorderService::class.java),
            PackageManager.GET_META_DATA,
        )
        assertEquals(
            "RecorderService must declare exactly FOREGROUND_SERVICE_TYPE_MICROPHONE",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            info.foregroundServiceType,
        )
    }

    @Test
    fun notificationIsNotUserDismissible() {
        val notification = RecorderNotification.build(context, CaptureState.Recording, 0L)
        assertNotEquals(
            "the persistent recording notification must be ongoing (not swipe-dismissible)",
            0,
            notification.flags and Notification.FLAG_ONGOING_EVENT,
        )
    }
}
