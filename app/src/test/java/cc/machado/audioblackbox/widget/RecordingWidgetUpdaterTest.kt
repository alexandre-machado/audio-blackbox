package cc.machado.audioblackbox.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Regression tests for [RecordingWidgetUpdater.refreshAll]'s two early-return guards (PR #278
 * review, `@rev` finding 3): "no widgets placed" and "no [AppWidgetManager] available". Both are
 * plain Kotlin over mockable framework interfaces -- no Robolectric needed -- and were previously
 * untested despite the review's framing implying this wiring is not JVM-testable at all.
 *
 * Deliberately does NOT test the non-empty/happy path: that requires
 * [RecordingWidgetRenderer.render] to actually run, which constructs a real `RemoteViews` --
 * a genuine Android framework class this repo's plain JVM unit tests cannot stand in for (see
 * that class's own doc). `ComponentName`'s constructor is mocked via Mockito's `mockConstruction`
 * (inline mock maker, mockito-core 5+, already relied on elsewhere in this suite for
 * `AudioRecord.getMinBufferSize`) purely so it does not throw the stub jar's "not mocked"
 * `RuntimeException` -- its return value is never asserted on.
 *
 * Oracle for both tests: [AppWidgetManager.updateAppWidget] -- which is what would actually push a
 * repaint to the launcher -- must never be called when there is nothing to repaint.
 */
class RecordingWidgetUpdaterTest {

    @Test
    fun `refreshAll does nothing when no widget instance is placed`() {
        val context = mock<Context>()
        val manager = mock<AppWidgetManager>()
        Mockito.mockStatic(AppWidgetManager::class.java).use { staticManager ->
            staticManager.`when`<AppWidgetManager> { AppWidgetManager.getInstance(context) }.thenReturn(manager)
            Mockito.mockConstruction(ComponentName::class.java).use {
                whenever(manager.getAppWidgetIds(any())).thenReturn(IntArray(0))

                RecordingWidgetUpdater.refreshAll(context)

                verify(manager, never()).updateAppWidget(any<IntArray>(), any())
            }
        }
    }

    @Test
    fun `refreshAll does nothing when no AppWidgetManager is available`() {
        val context = mock<Context>()
        Mockito.mockStatic(AppWidgetManager::class.java).use { staticManager ->
            staticManager.`when`<AppWidgetManager> { AppWidgetManager.getInstance(context) }.thenReturn(null)

            // Must return before even constructing a ComponentName/calling getAppWidgetIds --
            // if it didn't, this would throw the stub jar's "not mocked" RuntimeException, since
            // nothing here mocks ComponentName's constructor.
            RecordingWidgetUpdater.refreshAll(context)
        }
    }
}
