package cc.machado.audioblackbox.widget

import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for issue #291: a real launcher rejects the entire `RemoteViews` tree
 * [RecordingWidgetRenderer.render] produces if any `RemoteViews.setInt(viewId, methodName, value)`
 * call targets a method not annotated `@RemotableViewMethod`. Confirmed on a Samsung S25
 * (`android.widget.RemoteViews$ActionException: view: android.widget.LinearLayout can't use
 * method with RemoteViews: setAccessibilityLiveRegion(int)`), which took the whole widget down
 * ("Couldn't add widget.") rather than just failing to announce.
 *
 * [RecordingWidgetRendererTest] (JVM, Tier 0) cannot catch this class of defect by construction: it
 * mocks `RemoteViews` via Mockito's inline mock maker, so `verify(views).setInt(...)` proves a call
 * was *issued*, never that a real host would *accept* it -- a mock has no `@RemotableViewMethod`
 * allowlist to reject against. This is exactly how the original #291 defect (added on PR #289)
 * passed CI: the JVM suite was green while the widget was unusable on real hardware.
 *
 * This test closes that gap by exercising the real `RemoteViews.apply` path: it builds the
 * `RemoteViews` through the actual production renderer -- the same object graph a real
 * `AppWidgetHostView` would inflate -- and applies it to a real `View` tree on the instrumented
 * (Tier 1) framework, on the main thread, the same thread the framework applies `RemoteViews` on.
 * A non-remotable reflective method throws `RemoteViews$ActionException` at `apply()` time, which
 * fails this test with the same exception a real launcher would surface, instead of silently
 * passing against a mock.
 */
@RunWith(AndroidJUnit4::class)
class RecordingWidgetRendererInstrumentedTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun render_appliesCleanlyToRealHostView() {
        lateinit var thrown: Throwable
        var succeeded = false

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = FrameLayout(context)
            try {
                val views = RecordingWidgetRenderer.render(context)
                // `RemoteViews.apply` is the same call a real `AppWidgetHostView` makes to
                // inflate/paint the widget; it runs the same `RemoteViews.getMethod`
                // `@RemotableViewMethod` allowlist check every reflective `setInt` action goes
                // through, on the real framework, not a mock.
                val applied = views.apply(context, parent)
                parent.addView(applied)
                succeeded = true
            } catch (t: Throwable) {
                thrown = t
            }
        }

        if (!succeeded) {
            throw AssertionError(
                "RecordingWidgetRenderer.render produced a RemoteViews tree a real host " +
                    "rejects at apply() time -- see issue #291. A real launcher would show " +
                    "\"Couldn't add widget.\" for this exact defect.",
                thrown,
            )
        }
    }
}
