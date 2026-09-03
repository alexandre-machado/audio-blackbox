package cc.machado.audioblackbox.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
 * Regression test for [RecordingWidgetRenderer.render].
 *
 * Issue #291: this class used to assert that `render` issued
 * `RemoteViews.setInt(R.id.widget_root, "setAccessibilityLiveRegion", ...)`. That assertion passed
 * against a mocked `RemoteViews` -- it proved the call was *issued*, never that a real host would
 * *accept* it. `View.setAccessibilityLiveRegion(int)` is not annotated `@RemotableViewMethod`, so
 * `RemoteViews.getMethod` rejects it on a real launcher and takes the whole tree down with
 * `ActionException` ("Couldn't add widget."), confirmed on a Samsung S25 on 2026-09-02. The call
 * has been removed from production code; a JVM/mocked test cannot catch that class of defect by
 * construction (a mock has no `@RemotableViewMethod` allowlist to reject against), so the
 * regression coverage for it is instrumented instead -- see
 * `RecordingWidgetRendererInstrumentedTest#render_appliesCleanlyToRealHostView` in the
 * `androidTest` source set, which applies this method's real output to a real `AppWidgetHostView`.
 *
 * What remains worth asserting on the JVM is that the per-state `setContentDescription` call on
 * `widget_root` -- the mechanism that is actually safe and actually reaches TalkBack -- is issued
 * on every render.
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
 * of these framework classes -- only that the root's content description is set on every render.
 */
class RecordingWidgetRendererTest {

    @Test
    fun `render sets a content description on widget_root on every render`() {
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
                        verify(views).setContentDescription(eq(R.id.widget_root), any())
                    }
                }
            }
        }
    }
}
