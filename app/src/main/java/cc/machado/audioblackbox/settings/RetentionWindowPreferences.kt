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
 * Only a value that is valid under [isValidRetentionMinutes] -- in
 * `[AudioConfig.RETENTION_WINDOW_MIN_MINUTES, AudioConfig.RETENTION_WINDOW_MAX_MINUTES]` **and** a
 * multiple of [AudioConfig.RETENTION_WINDOW_STEP_MINUTES] -- is ever handed out, on both ends:
 * [setBufferDurationMinutes] rejects a write that fails that predicate, and
 * [bufferDurationMinutesFlow]/[currentBufferDurationMinutes] fall back to
 * [AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES] on a *read* that finds a present-but-invalid value
 * -- not just an absent key. That second guard (`@techlead` adjudication on PR #57, item 1) matters
 * because an invalid persisted value is reachable through normal use, not only a corrupt/hand-edited
 * file: a future release narrowing the bounds/step combined with a downgrade, or the option set
 * otherwise shrinking, can leave a value on disk that was valid when written and is not any more.
 * Without the read-side guard that value would still reach
 * [cc.machado.audioblackbox.service.RecorderService]'s companion `AudioConfig`/`RingBuffer`'s eager
 * `ByteArray(capacityBytes)` allocation on every single launch.
 *
 * Issue #73 changed the domain from a fixed list (`[5, 15, 30, 60]`) to a range with a step, but
 * this guard's *reason for existing* is unchanged, and now also covers a value that is in range but
 * off-step (e.g. `37`) -- that shape did not exist under the old fixed-list validation (every valid
 * value implicitly satisfied "one of the list" and "a multiple of a step" simultaneously) but is a
 * distinct way to be invalid under a range+step domain, and must degrade to the default exactly like
 * an out-of-range value does. `15` -- a value valid under the old list -- remains valid here (in
 * range, a multiple of 5), so this migration needs no data fixup.
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

    /** Persists [minutes]. Throws [IllegalArgumentException] if [minutes] fails
     * [isValidRetentionMinutes] -- callers (the settings screen's retention stepper) only ever
     * offer bounded, on-step values, so this rejects anything else as a programming error rather
     * than silently clamping it. */
    suspend fun setBufferDurationMinutes(minutes: Int)
}

/** The single oracle for "is [minutes] a value this app will ever persist or hand out" (issue
 * #73): in range **and** on-step. Shared by both the read-side fallback and the write-side
 * `require` in [DataStoreRetentionWindowPreferences] and [InMemoryRetentionWindowPreferences] so
 * the two can never quietly drift apart. */
fun isValidRetentionMinutes(minutes: Int): Boolean =
    minutes in AudioConfig.RETENTION_WINDOW_MIN_MINUTES..AudioConfig.RETENTION_WINDOW_MAX_MINUTES &&
        minutes % AudioConfig.RETENTION_WINDOW_STEP_MINUTES == 0

/**
 * Production [RetentionWindowPreferences], backed by a dedicated [DataStore] file.
 *
 * Takes the [DataStore] itself, not a [Context] (see the secondary `operator fun invoke` below
 * for the [Context]-based factory [cc.machado.audioblackbox.ui.MainActivity] actually calls) --
 * this is the seam that lets a JVM unit test (`RetentionWindowPreferencesTest`) exercise the real
 * persistence/round-trip logic against a `PreferenceDataStoreFactory`-built [DataStore] pointed at
 * a temp file, with no `Context`/Robolectric/instrumented test required, while production code
 * still only ever constructs this from a real `Context`.
 */
class DataStoreRetentionWindowPreferences(private val dataStore: DataStore<Preferences>) : RetentionWindowPreferences {

    // `@techlead` adjudication on PR #57, item 1 (`@sec` finding): validated on read, not just on
    // write. A value that is *present but invalid* is reachable through entirely normal use --
    // not just a hand-edited/corrupt file -- if a future release ever narrows
    // AudioConfig.RETENTION_WINDOW_MIN_MINUTES/MAX_MINUTES/STEP_MINUTES and the app is downgraded,
    // or the valid domain otherwise shrinks, after a value that was valid under the old bounds was
    // persisted. Issue #73 widened what "invalid" can mean -- in range but off-step (e.g. 37) is
    // now a distinct failure mode alongside out-of-range -- so this checks the same
    // isValidRetentionMinutes predicate the write side enforces, not just range membership.
    // Falling through to the same DEFAULT_BUFFER_DURATION_MINUTES the absent-key case already uses
    // -- rather than propagating the stored value -- is what stops an invalid Int from ever
    // reaching RecorderService's companion `AudioConfig`/`RingBuffer`'s eager
    // `ByteArray(capacityBytes)` allocation, which would otherwise OOM on every single launch with
    // nothing pointing at the cause.
    override val bufferDurationMinutesFlow: Flow<Int> = dataStore.data.map { prefs ->
        val stored = prefs[KEY_BUFFER_DURATION_MINUTES]
        if (stored != null && isValidRetentionMinutes(stored)) {
            stored
        } else {
            AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES
        }
    }

    override suspend fun currentBufferDurationMinutes(): Int = bufferDurationMinutesFlow.first()

    override suspend fun setBufferDurationMinutes(minutes: Int) {
        require(isValidRetentionMinutes(minutes)) {
            "bufferDurationMinutes must be in " +
                "${AudioConfig.RETENTION_WINDOW_MIN_MINUTES}..${AudioConfig.RETENTION_WINDOW_MAX_MINUTES} " +
                "and a multiple of ${AudioConfig.RETENTION_WINDOW_STEP_MINUTES}, was $minutes"
        }
        dataStore.edit { prefs -> prefs[KEY_BUFFER_DURATION_MINUTES] = minutes }
    }

    companion object {
        private val KEY_BUFFER_DURATION_MINUTES = intPreferencesKey("buffer_duration_minutes")

        /** Production factory: builds the real, disk-backed [DataStoreRetentionWindowPreferences]
         * from a [Context] -- the constructor above stays [Context]-free for testability (see its
         * doc). [cc.machado.audioblackbox.ui.MainActivity] calls this, not the constructor
         * directly. */
        operator fun invoke(context: Context): DataStoreRetentionWindowPreferences =
            DataStoreRetentionWindowPreferences(context.applicationContext.retentionWindowDataStore)
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
        require(isValidRetentionMinutes(minutes)) {
            "bufferDurationMinutes must be in " +
                "${AudioConfig.RETENTION_WINDOW_MIN_MINUTES}..${AudioConfig.RETENTION_WINDOW_MAX_MINUTES} " +
                "and a multiple of ${AudioConfig.RETENTION_WINDOW_STEP_MINUTES}, was $minutes"
        }
        state.value = minutes
    }
}
