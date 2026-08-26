package cc.machado.audioblackbox

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import cc.machado.audioblackbox.audio.CaptureState
import cc.machado.audioblackbox.service.RecorderNotification
import cc.machado.audioblackbox.service.RecorderService
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator-only regression test for issue #30: the notification's buffered-duration text froze
 * mid-recording -- invisible to four PR review rounds, found only by a human staring at a phone --
 * because nothing re-posted the notification while [CaptureState] stayed steadily
 * [CaptureState.Recording] (a [kotlinx.coroutines.flow.StateFlow] never re-emits an unchanged
 * value, so the transition-driven collector in [RecorderService.onCreate] never fired again). The
 * fix, [cc.machado.audioblackbox.service.PeriodicNotificationRefresher], ticks every
 * [cc.machado.audioblackbox.service.PeriodicNotificationRefresher.DEFAULT_INTERVAL_MILLIS] (10s)
 * while Recording.
 *
 * Starts the real [RecorderService] (`ACTION_START`) and reads the notification the OS actually
 * has posted, via `NotificationManager.getActiveNotifications()` -- not
 * [RecorderNotification.build] called directly with test-supplied arguments, which would only
 * prove `build` is a pure function of its own parameters and could never fail against the #30
 * regression (round-3 `@rev`/`@techlead` finding on PR #35: the original version of this test did
 * exactly that and was rejected as vacuous). The oracle here is the posted text moving on its own,
 * sampled twice in a row with no [CaptureState] transition driving either change, across a window
 * spanning more than two refresh ticks.
 */
@RunWith(AndroidJUnit4::class)
class NotificationBufferedDurationTest {

    // POST_NOTIFICATIONS is a runtime permission only from API 33 (Tiramisu); it is not a
    // grantable permission on the API 30 floor this tier targets (see scripts/ci/avd.env), and
    // GrantPermissionRule.grant() throws if asked to grant it there -- same guard as
    // InterruptionSpliceTest.
    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        *(
            listOf(Manifest.permission.RECORD_AUDIO) +
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    listOf(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    emptyList()
                }
            ).toTypedArray()
    )

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val notificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        context.startService(RecorderService.stopIntent(context))
        pollUntil(timeoutMillis = 15_000) {
            RecorderService.engine.state.value is CaptureState.Idle && !RecorderService.isServiceRunning.value
        }
    }

    @After
    fun tearDown() {
        context.startService(RecorderService.stopIntent(context))
        pollUntil(timeoutMillis = 15_000) {
            RecorderService.engine.state.value is CaptureState.Idle && !RecorderService.isServiceRunning.value
        }
    }

    @Test
    fun postedNotificationText_keepsChangingWhileRecording_withNoStateTransition() {
        context.startForegroundService(RecorderService.startIntent(context))
        assertTrue(
            "capture never reached Recording",
            pollUntil(timeoutMillis = 15_000) { RecorderService.engine.state.value is CaptureState.Recording },
        )

        val text0 = pollForNonNullText(timeoutMillis = 10_000)
        assertNotNull("no notification for this app was ever observed as posted", text0)

        // Must clear a full refresher tick (10s) with margin. CaptureState stays Recording
        // throughout this wait -- no transition occurs, so only the periodic refresher (or a
        // pre-#30 regression's absence of one) can explain what happens here.
        val text1 = waitForTextChange(previous = text0, timeoutMillis = 18_000)
        assertNotNull(
            "posted notification text never changed within 18s of steady Recording with no " +
                "CaptureState transition -- this is exactly issue #30's regression: nothing but " +
                "a state transition refreshes the posted notification, and none occurred here",
            text1,
        )

        // A second consecutive change rules out a single stray refresh (e.g. a race on the
        // initial post) and confirms this is a genuinely periodic driver that keeps ticking for
        // as long as Recording continues, spanning more than two refresh ticks in total.
        val text2 = waitForTextChange(previous = text1, timeoutMillis = 18_000)
        assertNotNull(
            "posted notification text changed only once; a genuinely periodic refresher must " +
                "keep ticking on every subsequent interval too, not just once",
            text2,
        )
    }

    private fun currentPostedText(): String? =
        notificationManager
            ?.activeNotifications
            ?.firstOrNull { it.id == RecorderNotification.NOTIFICATION_ID && it.packageName == context.packageName }
            ?.notification
            ?.extras
            ?.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()

    private fun pollForNonNullText(timeoutMillis: Long): String? {
        var text: String? = null
        pollUntil(timeoutMillis = timeoutMillis) {
            text = currentPostedText()
            text != null
        }
        return text
    }

    private fun waitForTextChange(previous: String?, timeoutMillis: Long): String? {
        var changed: String? = null
        pollUntil(timeoutMillis = timeoutMillis, intervalMillis = 500) {
            val current = currentPostedText()
            if (current != null && current != previous) {
                changed = current
                true
            } else {
                false
            }
        }
        return changed
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
