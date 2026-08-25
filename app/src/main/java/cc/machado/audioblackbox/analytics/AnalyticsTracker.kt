package cc.machado.audioblackbox.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Privacy-first analytics tracker contract (issue #98).
 *
 * Privacy Invariants:
 * - Strictly zero audio content or waveforms recorded.
 * - Strictly zero audio transcripts or speech data recorded.
 * - Strictly zero file names, directory paths, or storage identifiers recorded.
 * - Strictly zero location, device identifiers, or PII recorded.
 *
 * Only coarse operational signals (engine lifecycle states, requested duration in minutes,
 * format string, export file sizes in bytes, and error reason enums) are tracked.
 */
interface AnalyticsTracker {
    /** Coarse state transitions: "idle", "recording", "paused", "error". */
    fun trackEngineStateChanged(state: String)

    /** When a save action is triggered with requested window in minutes. */
    fun trackSaveTriggered(durationMinutes: Int)

    /** When a save action finishes writing: duration, container format, and file size in bytes. */
    fun trackSaveCompleted(durationMinutes: Int, format: String, fileSizeBytes: Long)

    /** When the configured retention window capacity is changed. */
    fun trackRetentionWindowChanged(windowMinutes: Int)

    /** Operational error categories and sanitized messages. */
    fun trackError(errorType: String, message: String? = null)
}

/**
 * Concrete [AnalyticsTracker] logging events to [FirebaseAnalytics].
 */
class FirebaseAnalyticsTracker(
    private val firebaseAnalytics: FirebaseAnalytics,
    private val bundleFactory: () -> Bundle = { Bundle() },
) : AnalyticsTracker {

    override fun trackEngineStateChanged(state: String) {
        val bundle = bundleFactory().apply {
            putString(PARAM_STATE, state)
        }
        firebaseAnalytics.logEvent(EVENT_ENGINE_STATE_CHANGED, bundle)
    }

    override fun trackSaveTriggered(durationMinutes: Int) {
        val bundle = bundleFactory().apply {
            putInt(PARAM_DURATION_MINUTES, durationMinutes)
        }
        firebaseAnalytics.logEvent(EVENT_SAVE_TRIGGERED, bundle)
    }

    override fun trackSaveCompleted(durationMinutes: Int, format: String, fileSizeBytes: Long) {
        val bundle = bundleFactory().apply {
            putInt(PARAM_DURATION_MINUTES, durationMinutes)
            putString(PARAM_FORMAT, format)
            putLong(PARAM_FILE_SIZE_BYTES, fileSizeBytes)
        }
        firebaseAnalytics.logEvent(EVENT_SAVE_COMPLETED, bundle)
    }

    override fun trackRetentionWindowChanged(windowMinutes: Int) {
        val bundle = bundleFactory().apply {
            putInt(PARAM_WINDOW_MINUTES, windowMinutes)
        }
        firebaseAnalytics.logEvent(EVENT_RETENTION_WINDOW_CHANGED, bundle)
    }

    override fun trackError(errorType: String, message: String?) {
        val bundle = bundleFactory().apply {
            putString(PARAM_ERROR_TYPE, errorType)
            if (message != null) {
                putString(PARAM_MESSAGE, message)
            }
        }
        firebaseAnalytics.logEvent(EVENT_ERROR, bundle)
    }

    companion object {
        const val EVENT_ENGINE_STATE_CHANGED = "engine_state_changed"
        const val EVENT_SAVE_TRIGGERED = "save_triggered"
        const val EVENT_SAVE_COMPLETED = "save_completed"
        const val EVENT_RETENTION_WINDOW_CHANGED = "retention_window_changed"
        const val EVENT_ERROR = "app_error"

        const val PARAM_STATE = "state"
        const val PARAM_DURATION_MINUTES = "duration_minutes"
        const val PARAM_FORMAT = "format"
        const val PARAM_FILE_SIZE_BYTES = "file_size_bytes"
        const val PARAM_WINDOW_MINUTES = "window_minutes"
        const val PARAM_ERROR_TYPE = "error_type"
        const val PARAM_MESSAGE = "message"
    }
}

/**
 * No-op implementation for environments where Firebase is unavailable or analytics is disabled.
 */
object NoOpAnalyticsTracker : AnalyticsTracker {
    override fun trackEngineStateChanged(state: String) = Unit
    override fun trackSaveTriggered(durationMinutes: Int) = Unit
    override fun trackSaveCompleted(durationMinutes: Int, format: String, fileSizeBytes: Long) = Unit
    override fun trackRetentionWindowChanged(windowMinutes: Int) = Unit
    override fun trackError(errorType: String, message: String?) = Unit
}

/**
 * Singleton accessor and factory for [AnalyticsTracker].
 */
object AnalyticsProvider {
    @Volatile
    private var tracker: AnalyticsTracker? = null

    fun initialize(tracker: AnalyticsTracker) {
        this.tracker = tracker
    }

    fun get(): AnalyticsTracker {
        return tracker ?: NoOpAnalyticsTracker
    }

    fun get(context: Context): AnalyticsTracker {
        return tracker ?: synchronized(this) {
            tracker ?: try {
                val firebaseAnalytics = FirebaseAnalytics.getInstance(context.applicationContext)
                FirebaseAnalyticsTracker(firebaseAnalytics).also { tracker = it }
            } catch (_: Throwable) {
                NoOpAnalyticsTracker.also { tracker = it }
            }
        }
    }

    fun setForTesting(tracker: AnalyticsTracker?) {
        this.tracker = tracker
    }
}
