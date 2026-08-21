package cc.machado.audioblackbox.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cc.machado.audioblackbox.audio.AudioConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists the user's chosen retention window (issue #45) -- how many minutes of audio
 * [cc.machado.audioblackbox.audio.RingBuffer] pre-allocates RAM for, as opposed to
 * [cc.machado.audioblackbox.ui.dashboard.DashboardViewModel]'s "salvar o passado" window, which is
 * a completely different, already-separate concept (how much of what's buffered gets written to a
 * file on a given tap).
 *
 * `DataStore` (Preferences flavor), not `SharedPreferences` like
 * [cc.machado.audioblackbox.permissions.SharedPrefsOnboardingPreferences]: this is new code, and
 * `DataStore` is the currently-recommended replacement -- it is `Flow`-based (so callers observe
 * changes instead of polling a getter), does its disk I/O off the calling thread by construction,
 * and has no equivalent of `SharedPreferences.apply()`'s silent-failure-on-corruption history.
 * `SharedPrefsOnboardingPreferences` is left as-is (not a churn target of this change), but new
 * persisted state goes through `DataStore` from here on.
 *
 * Only [AudioConfig.RETENTION_WINDOW_OPTIONS_MINUTES] are ever accepted -- see
 * [setBufferDurationMinutes] -- so a corrupt or hand-edited preferences file can never hand
 * [cc.machado.audioblackbox.service.RecorderService] a value the ring buffer sizing arithmetic
 * was not deliberately bounded for.
 */
interface RetentionWindowPreferences {
    /** Reactive, defaults to [AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES] until the user has
     * ever chosen a value (first run, or a fresh install). */
    val bufferDurationMinutesFlow: Flow<Int>

    /** Synchronous read of the same value [bufferDurationMinutesFlow] would currently emit --
     * used exactly once, by [cc.machado.audioblackbox.AudioBlackboxApplication.onCreate], to
     * preload the value [cc.machado.audioblackbox.service.RecorderService]'s companion object
     * needs before its first `AudioConfig`/`AudioCaptureEngine` is constructed. Not used anywhere
     * a `Flow` collector would do -- see that call site's doc for why a one-time blocking read is
     * the correct tool there. */
    suspend fun currentBufferDurationMinutes(): Int

    /** Persists [minutes]. Throws [IllegalArgumentException] if [minutes] is not one of
     * [AudioConfig.RETENTION_WINDOW_OPTIONS_MINUTES] -- callers (the dashboard's retention
     * selector) only ever offer bounded options, so this rejects anything else as a programming
     * error rather than silently clamping it. */
    suspend fun setBufferDurationMinutes(minutes: Int)
}

/** Production [RetentionWindowPreferences], backed by a dedicated [DataStore] file. */
class DataStoreRetentionWindowPreferences(context: Context) : RetentionWindowPreferences {

    private val dataStore: DataStore<Preferences> = context.applicationContext.retentionWindowDataStore

    override val bufferDurationMinutesFlow: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_BUFFER_DURATION_MINUTES] ?: AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES
    }

    override suspend fun currentBufferDurationMinutes(): Int = bufferDurationMinutesFlow.first()

    override suspend fun setBufferDurationMinutes(minutes: Int) {
        require(minutes in AudioConfig.RETENTION_WINDOW_OPTIONS_MINUTES) {
            "bufferDurationMinutes must be one of ${AudioConfig.RETENTION_WINDOW_OPTIONS_MINUTES}, was $minutes"
        }
        dataStore.edit { prefs -> prefs[KEY_BUFFER_DURATION_MINUTES] = minutes }
    }

    companion object {
        private val KEY_BUFFER_DURATION_MINUTES = intPreferencesKey("buffer_duration_minutes")
    }
}

private val Context.retentionWindowDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "retention_window_prefs",
)

/** Context-free [RetentionWindowPreferences], backed by an in-memory [MutableStateFlow] instead
 * of a real [DataStore] file. Used as [cc.machado.audioblackbox.ui.dashboard.DashboardViewModel]'s
 * default constructor parameter (that class's constructor deliberately takes no `Context`, the
 * same reason its `onStartEngine`/`onStopEngine`/`onSaveIntent` callbacks default to no-ops -- see
 * its class doc) and by tests that need a working, observable fake without touching real disk.
 * [cc.machado.audioblackbox.ui.MainActivity] wires the real [DataStoreRetentionWindowPreferences]
 * instead. */
class InMemoryRetentionWindowPreferences(
    initialMinutes: Int = AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES,
) : RetentionWindowPreferences {

    private val state = kotlinx.coroutines.flow.MutableStateFlow(initialMinutes)

    override val bufferDurationMinutesFlow: Flow<Int> = state

    override suspend fun currentBufferDurationMinutes(): Int = state.value

    override suspend fun setBufferDurationMinutes(minutes: Int) {
        require(minutes in AudioConfig.RETENTION_WINDOW_OPTIONS_MINUTES) {
            "bufferDurationMinutes must be one of ${AudioConfig.RETENTION_WINDOW_OPTIONS_MINUTES}, was $minutes"
        }
        state.value = minutes
    }
}
