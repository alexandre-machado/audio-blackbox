package cc.machado.audioblackbox

import android.Manifest
import android.app.Notification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import cc.machado.audioblackbox.audio.AudioCaptureEngine
import cc.machado.audioblackbox.audio.AudioConfig
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.service.RecorderNotification
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator-only regression test for issue #30: the notification's buffered-duration text froze
 * mid-recording, invisible to four PR review rounds, found only by a human staring at a phone.
 * Drives the real production classes -- [AudioCaptureEngine] reading the real microphone and
 * [RecorderNotification.build] formatting real elapsed wall-clock time -- so it fails the same
 * way a human noticed it: the rendered text not moving.
 *
 * Uses its own [AudioCaptureEngine] instance, not [cc.machado.audioblackbox.service.RecorderService]'s
 * companion singleton -- that singleton is hardcoded to a 30-minute buffer, which would make
 * exercising saturation here impractically slow. A 1-minute buffer (the smallest [AudioConfig]
 * allows) keeps this test bounded while still exercising the same production code paths.
 */
@RunWith(AndroidJUnit4::class)
class NotificationBufferedDurationTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val engine = AudioCaptureEngine(config = AudioConfig(bufferDurationMinutes = 1))

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        engine.stop()
    }

    @Test
    fun bufferedDuration_advancesWhileRecording_andPinsAtSaturation() {
        engine.start()
        assertTrue(
            "capture never reached Recording",
            pollUntil(10_000) { engine.state.value is CaptureState.Recording },
        )

        val early = waitForNonNullDuration()
        Thread.sleep(8_000)
        val later = engine.bufferedDurationMillis() ?: 0L
        assertTrue(
            "buffered duration must advance while Recording (was $early after start, still " +
                "$later after 8s more -- this is exactly issue #30's regression)",
            later > early,
        )

        val earlyText = extraText(RecorderNotification.build(context, CaptureState.Recording, early))
        val laterText = extraText(RecorderNotification.build(context, CaptureState.Recording, later))
        assertNotEquals(
            "rendered notification text must change as buffered duration advances",
            earlyText,
            laterText,
        )

        // Saturation: once the 1-minute window is full, the value must pin rather than keep
        // climbing past the buffer's actual retained capacity.
        assertTrue(
            "buffered duration never reached saturation at the configured 60_000ms window",
            pollUntil(70_000, intervalMillis = 1_000) { (engine.bufferedDurationMillis() ?: 0L) >= 60_000L },
        )
        val saturated = engine.bufferedDurationMillis() ?: 0L
        Thread.sleep(5_000)
        val stillSaturated = engine.bufferedDurationMillis() ?: 0L
        assertEquals(
            "buffered duration must pin at the buffer window once saturated, not keep climbing",
            saturated,
            stillSaturated,
        )
    }

    private fun extraText(notification: Notification): String? =
        notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun waitForNonNullDuration(): Long {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val value = engine.bufferedDurationMillis()
            if (value != null && value > 0) return value
            Thread.sleep(100)
        }
        return engine.bufferedDurationMillis() ?: 0L
    }

    private fun pollUntil(timeoutMillis: Long, intervalMillis: Long = 250, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(intervalMillis)
        }
        return condition()
    }
}
