package cc.machado.audioblackbox.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.core.DataStore
import cc.machado.audioblackbox.audio.AudioConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Real persistence tests for [DataStoreRetentionWindowPreferences] -- against an actual
 * [DataStore] file on disk (via `PreferenceDataStoreFactory`), not [InMemoryRetentionWindowPreferences]
 * or a mock. This is the oracle issue #45 requires: a bug where a written value never actually
 * reaches disk, or a fresh read never picks up what a previous instance wrote, would pass every
 * other test in this feature (they all use the in-memory fake) and only fail here.
 *
 * No Robolectric/Context: [DataStoreRetentionWindowPreferences] takes a [DataStore] directly (see
 * its class doc), so this builds one with `PreferenceDataStoreFactory.create` pointed at a real
 * temp file -- the same underlying storage mechanism production uses, minus the Android `Context`
 * indirection that only resolves *which* file to use.
 */
class RetentionWindowPreferencesTest {

    private lateinit var file: File
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        file = File.createTempFile("retention_window_test", ".preferences_pb")
        file.delete()
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        file.delete()
    }

    // Each call gets its own scope, so DataStore's single-active-instance-per-file guard is
    // actually exercised the way separate process lifetimes would: a new instance only becomes
    // valid once the previous one's scope is cancelled (see the round-trip test below, which does
    // that explicitly to model "the old process is gone").
    private fun newDataStore(): DataStore<Preferences> {
        val scope = CoroutineScope(SupervisorJob())
        scopes += scope
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @Test
    fun `before anything has ever been persisted, the value is the first-run fallback`() = runTest {
        val preferences = DataStoreRetentionWindowPreferences(newDataStore())

        assertEquals(AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES, preferences.currentBufferDurationMinutes())
    }

    @Test
    fun `a persisted non-default value survives a fresh instance reading the same file (process-death round trip)`() = runTest {
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob)
        val firstDataStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        DataStoreRetentionWindowPreferences(firstDataStore).setBufferDurationMinutes(45)
        // Cancelling this scope is what actually stands in for "the process died" -- DataStore
        // refuses a second live instance on the same file otherwise (by design, to catch real
        // multi-instance bugs), so this is not incidental test cleanup, it is the thing that makes
        // the assertion below a real "after a restart" read rather than the same live object.
        //
        // `cancelAndJoin`, not `cancel` (found flaky on CI, `@techlead` adjudication on PR #57
        // round 2): `CoroutineScope.cancel()` requests cancellation but does not wait for it to
        // finish -- DataStore's own internal teardown (releasing its hold on `file`) is itself
        // asynchronous, so constructing the second instance immediately after a bare `cancel()`
        // races that teardown instead of waiting for it. `cancelAndJoin` makes "the previous
        // instance is provably gone" true before the next line runs, rather than "probably gone by
        // the time the scheduler gets around to it" -- the same shape as PR #28's CountDownLatch
        // handshake replacing a probabilistic race.
        firstJob.cancelAndJoin()

        val reloaded = DataStoreRetentionWindowPreferences(newDataStore())

        assertEquals(45, reloaded.currentBufferDurationMinutes())
        assertEquals(45, reloaded.bufferDurationMinutesFlow.first())
    }

    @Test
    fun `bufferDurationMinutesFlow reacts to a later write on the same instance, not just the value at construction`() = runTest {
        val preferences = DataStoreRetentionWindowPreferences(newDataStore())
        assertEquals(AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES, preferences.bufferDurationMinutesFlow.first())

        preferences.setBufferDurationMinutes(15)

        assertEquals(15, preferences.bufferDurationMinutesFlow.first())
    }

    @Test
    fun `setBufferDurationMinutes rejects a value outside the bounded range instead of silently persisting it`() = runTest {
        val preferences = DataStoreRetentionWindowPreferences(newDataStore())

        var thrown: IllegalArgumentException? = null
        try {
            preferences.setBufferDurationMinutes(65)
        } catch (e: IllegalArgumentException) {
            thrown = e
        }

        assertEquals(true, thrown != null)
        // Nothing was written -- still the fallback.
        assertEquals(AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES, preferences.currentBufferDurationMinutes())
    }

    @Test
    fun `setBufferDurationMinutes rejects an in-range but off-step value instead of silently persisting it`() = runTest {
        // Issue #73: the stepper's domain is a range with a step, not a fixed list -- 37 is inside
        // [MIN, MAX] but not a multiple of STEP, a distinct way to be invalid from "out of range"
        // that could not exist under the old fixed-list domain.
        val preferences = DataStoreRetentionWindowPreferences(newDataStore())

        var thrown: IllegalArgumentException? = null
        try {
            preferences.setBufferDurationMinutes(37)
        } catch (e: IllegalArgumentException) {
            thrown = e
        }

        assertEquals(true, thrown != null)
        assertEquals(AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES, preferences.currentBufferDurationMinutes())
    }

    // `@techlead` adjudication on PR #57, item 1 (`@sec` finding): a value that is *present but
    // invalid* is reachable through entirely normal use -- not only a hand-edited/corrupt file --
    // e.g. a future release narrowing AudioConfig.RETENTION_WINDOW_MIN_MINUTES/MAX_MINUTES/
    // STEP_MINUTES combined with a downgrade, or the valid domain otherwise shrinking, after a
    // value that was valid under the old bounds was persisted. This writes directly through a raw
    // DataStore<Preferences> (bypassing setBufferDurationMinutes's own write-side `require`, which
    // is exactly the point: this simulates a value that reached disk some other way, not one this
    // class itself would ever write today) using the identical key name
    // DataStoreRetentionWindowPreferences uses -- Preferences DataStore keys compare by name+type,
    // not instance identity, so this is a real stand-in for "whatever is on disk", the same as
    // `PreferencesProto` would produce.
    private val rawKeyBufferDurationMinutes = intPreferencesKey("buffer_duration_minutes")

    @Test
    fun `a persisted out-of-range value degrades to the fallback instead of reaching the caller`() = runTest {
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 1000 }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore)

        assertEquals(
            "an out-of-range stored value must never reach a caller that will use it to size a buffer",
            AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES,
            preferences.currentBufferDurationMinutes(),
        )
        assertEquals(AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES, preferences.bufferDurationMinutesFlow.first())
    }

    // ---- Issue #72's interim clamp (RETENTION_WINDOW_MAX_MINUTES: 60 -> 45): the three tests
    // below are the proof this PR's task description asks for. Each writes a value that a build of
    // this app *before* the clamp could legitimately have persisted (60 was the old MAX itself;
    // 50/55 were newly reachable once #73 turned the fixed list into a 5-minute-step range). None
    // of them may throw on load, and each must resolve to a value that satisfies today's
    // isValidRetentionMinutes -- i.e. at or below the new MAX of 45. Reverting the clamp handling
    // in RetentionWindowPreferences.kt (resolveStoredRetentionMinutes) while keeping
    // AudioConfig.RETENTION_WINDOW_MAX_MINUTES at 45 makes these fail: without that handling,
    // isValidRetentionMinutes(60/55/50) is false and the old code path fell through to
    // DEFAULT_BUFFER_DURATION_MINUTES (30), not the clamped 45 these assert -- so these tests also
    // fail against a naive "just lower MAX" change with no migration handling, which is exactly
    // what they are meant to catch.

    @Test
    fun `a persisted 60 (the pre-clamp MAX) loads without throwing and clamps down to the new MAX of 45`() = runTest {
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 60 }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore)

        assertEquals(45, preferences.currentBufferDurationMinutes())
        assertEquals(45, preferences.bufferDurationMinutesFlow.first())
    }

    @Test
    fun `a persisted 55 (valid pre-clamp, now above MAX) clamps down to 45 instead of resetting to the default`() = runTest {
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 55 }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore)

        assertEquals(45, preferences.currentBufferDurationMinutes())
        assertEquals(45, preferences.bufferDurationMinutesFlow.first())
    }

    @Test
    fun `a persisted 50 (valid pre-clamp, now above MAX) clamps down to 45 instead of resetting to the default`() = runTest {
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 50 }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore)

        assertEquals(45, preferences.currentBufferDurationMinutes())
        assertEquals(45, preferences.bufferDurationMinutesFlow.first())
    }

    @Test
    fun `a persisted in-range but off-step value degrades to the fallback instead of reaching the caller`() = runTest {
        // 37 is in [MIN, MAX] but not a multiple of STEP -- this shape of invalid value did not
        // exist under the old fixed-list domain (issue #45/#57) and must be caught the same way an
        // out-of-range value is, not treated as "close enough".
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 37 }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore)

        assertEquals(
            "an off-step stored value must never reach a caller that will use it to size a buffer",
            AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES,
            preferences.currentBufferDurationMinutes(),
        )
        assertEquals(AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES, preferences.bufferDurationMinutesFlow.first())
    }

    @Test
    fun `a persisted value that was valid under the old fixed-list domain remains valid (15 needs no migration)`() = runTest {
        // 15 was a member of the pre-issue-#73 fixed list [5, 15, 30, 60] and remains valid under
        // the range+step domain (in [5, 60], a multiple of 5) -- this is the migration guarantee
        // issue #73's write-up calls out explicitly.
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 15 }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore)

        assertEquals(15, preferences.currentBufferDurationMinutes())
    }
}
