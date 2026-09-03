package cc.machado.audioblackbox.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import cc.machado.audioblackbox.R
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Regression test for [RecordingWidgetRenderer.render] (issue #279, `@rev` finding on PR #289):
 * every render must attempt `RemoteViews.setInt(R.id.widget_root, "setAccessibilityLiveRegion",
 * View.ACCESSIBILITY_LIVE_REGION_POLITE)` alongside the existing per-state
 * `setContentDescription` call, so a state change has a real chance of being announced by a real
 * launcher host -- the reflective `setInt` dispatch this repo already uses for `setColorFilter`
 * two lines below in production code, now also attempted for the live-region flag.
 *
 * Oracle: [RemoteViews.setInt] is called with exactly `(R.id.widget_root,
 * "setAccessibilityLiveRegion", View.ACCESSIBILITY_LIVE_REGION_POLITE)`. A mutation that removed
 * this call, changed the target view id, or used the wrong method name/value would leave this
 * assertion unsatisfied.
 *
 * `RemoteViews`, `Intent`, `PendingIntent`, and `ContextCompat.getColor` are all real Android
 * framework surfaces this repo has no Robolectric shim for (see [RecordingWidgetRenderer]'s own
 * doc): the `RemoteViews`/`Intent` instances `render` constructs are intercepted via Mockito's
 * `mockConstruction` (inline mock maker, already relied on elsewhere in this suite, e.g.
 * [RecordingWidgetUpdaterTest]'s `ComponentName` interception -- `Intent`'s mocked construction is
 * only there so `setAction` does not throw the stub jar's "not mocked" `RuntimeException`; its
 * return value is never asserted on), and the static
 * `PendingIntent.getForegroundService`/`ContextCompat.getColor` calls are stubbed via
 * `mockStatic` the same way [RecordingWidgetUpdaterTest] already stubs
 * `AppWidgetManager.getInstance`. This test does not assert anything about *which* state was
 * rendered -- [RecordingWidgetStateMapperTest] already covers that on the JVM without touching any
 * of these framework classes -- only that the live-region call is issued on every render,
 * regardless of state.
 */
class RecordingWidgetRendererTest {

    @Test
    fun `render attempts the setAccessibilityLiveRegion reflective call on widget_root`() {
        val context = mock<Context>()
        whenever(context.packageName).thenReturn("cc.machado.audioblackbox")
        whenever(context.getString(any())).thenReturn("stub")

        Mockito.mockStatic(ContextCompat::class.java).use { contextCompat ->
            contextCompat.`when`<Int> { ContextCompat.getColor(any(), any()) }.thenReturn(0)

            Mockito.mockStatic(PendingIntent::class.java).use { pendingIntentStatic ->
                pendingIntentStatic
                    .`when`<PendingIntent> {
                        PendingIntent.getForegroundService(any(), any(), any(), any())
                    }
                    .thenReturn(mock())

                Mockito.mockConstruction(Intent::class.java) { mockIntent, _ ->
                    // `Intent.setAction` is fluent (returns `this`); the mocked construction
                    // otherwise defaults to `null`, which fails Kotlin's platform-type null
                    // check at the call site in `RecordingWidgetRenderer.actionPendingIntent`.
                    whenever(mockIntent.setAction(any())).thenReturn(mockIntent)
                }.use {
                    Mockito.mockConstruction(RemoteViews::class.java).use { construction ->
                        RecordingWidgetRenderer.render(context)

                        val views = construction.constructed().single()
                        verify(views).setInt(
                            eq(R.id.widget_root),
                            eq("setAccessibilityLiveRegion"),
                            eq(View.ACCESSIBILITY_LIVE_REGION_POLITE),
                        )
                    }
                }
            }
        }
    }
}
