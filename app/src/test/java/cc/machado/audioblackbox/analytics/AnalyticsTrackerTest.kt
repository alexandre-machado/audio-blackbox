package cc.machado.audioblackbox.analytics

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * Unit tests for [AnalyticsTracker], [FirebaseAnalyticsTracker], [NoOpAnalyticsTracker], and [AnalyticsProvider].
 *
 * The Oracle: Verifies that FirebaseAnalytics is called with the exact expected privacy-first
 * event names and parameters ("engine_state_changed", "save_triggered", "save_completed",
 * "retention_window_changed", "app_error") and that no PII, audio payloads, or file names are leaked.
 */
class AnalyticsTrackerTest {

    @After
    fun tearDown() {
        AnalyticsProvider.setForTesting(null)
    }

    @Test
    fun `trackEngineStateChanged logs event with state parameter`() {
        val firebaseAnalytics = mock<FirebaseAnalytics>()
        val bundle = mock<Bundle>()
        val tracker = FirebaseAnalyticsTracker(firebaseAnalytics, bundleFactory = { bundle })

        tracker.trackEngineStateChanged("recording")

        verify(bundle).putString("state", "recording")
        verify(firebaseAnalytics).logEvent("engine_state_changed", bundle)
    }

    @Test
    fun `trackSaveTriggered logs event with durationMinutes parameter`() {
        val firebaseAnalytics = mock<FirebaseAnalytics>()
        val bundle = mock<Bundle>()
        val tracker = FirebaseAnalyticsTracker(firebaseAnalytics, bundleFactory = { bundle })

        tracker.trackSaveTriggered(15)

        verify(bundle).putInt("duration_minutes", 15)
        verify(firebaseAnalytics).logEvent("save_triggered", bundle)
    }

    @Test
    fun `trackSaveCompleted logs event with duration, format, and fileSize`() {
        val firebaseAnalytics = mock<FirebaseAnalytics>()
        val bundle = mock<Bundle>()
        val tracker = FirebaseAnalyticsTracker(firebaseAnalytics, bundleFactory = { bundle })

        tracker.trackSaveCompleted(durationMinutes = 30, format = "m4a", fileSizeBytes = 2048000L)

        verify(bundle).putInt("duration_minutes", 30)
        verify(bundle).putString("format", "m4a")
        verify(bundle).putLong("file_size_bytes", 2048000L)
        verify(firebaseAnalytics).logEvent("save_completed", bundle)
    }

    @Test
    fun `trackRetentionWindowChanged logs event with windowMinutes parameter`() {
        val firebaseAnalytics = mock<FirebaseAnalytics>()
        val bundle = mock<Bundle>()
        val tracker = FirebaseAnalyticsTracker(firebaseAnalytics, bundleFactory = { bundle })

        tracker.trackRetentionWindowChanged(45)

        verify(bundle).putInt("window_minutes", 45)
        verify(firebaseAnalytics).logEvent("retention_window_changed", bundle)
    }

    @Test
    fun `trackError without message logs event with errorType only`() {
        val firebaseAnalytics = mock<FirebaseAnalytics>()
        val bundle = mock<Bundle>()
        val tracker = FirebaseAnalyticsTracker(firebaseAnalytics, bundleFactory = { bundle })

        tracker.trackError(errorType = "UNSUPPORTED_CONFIG")

        verify(bundle).putString("error_type", "UNSUPPORTED_CONFIG")
        verify(bundle, never()).putString(eq("message"), any())
        verify(firebaseAnalytics).logEvent("app_error", bundle)
    }

    @Test
    fun `trackError with message logs event with errorType and message`() {
        val firebaseAnalytics = mock<FirebaseAnalytics>()
        val bundle = mock<Bundle>()
        val tracker = FirebaseAnalyticsTracker(firebaseAnalytics, bundleFactory = { bundle })

        tracker.trackError(errorType = "SINK_OPEN_FAILED", message = "Permission denied")

        verify(bundle).putString("error_type", "SINK_OPEN_FAILED")
        verify(bundle).putString("message", "Permission denied")
        verify(firebaseAnalytics).logEvent("app_error", bundle)
    }

    @Test
    fun `NoOpAnalyticsTracker methods execute safely without error`() {
        NoOpAnalyticsTracker.trackEngineStateChanged("idle")
        NoOpAnalyticsTracker.trackSaveTriggered(15)
        NoOpAnalyticsTracker.trackSaveCompleted(15, "m4a", 1024L)
        NoOpAnalyticsTracker.trackRetentionWindowChanged(30)
        NoOpAnalyticsTracker.trackError("SOME_ERROR", "Some detail")
    }

    @Test
    fun `AnalyticsProvider defaults to NoOpAnalyticsTracker`() {
        AnalyticsProvider.setForTesting(null)
        assertSame(NoOpAnalyticsTracker, AnalyticsProvider.get())
    }

    @Test
    fun `AnalyticsProvider get returns initialized tracker`() {
        val customTracker = mock<AnalyticsTracker>()
        AnalyticsProvider.initialize(customTracker)

        assertSame(customTracker, AnalyticsProvider.get())
    }
}
