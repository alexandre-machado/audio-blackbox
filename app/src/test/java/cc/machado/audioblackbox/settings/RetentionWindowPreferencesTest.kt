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
        DataStoreRetentionWindowPreferences(firstDataStore).setBufferDurationMinutes(60)
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

        assertEquals(60, reloaded.currentBufferDurationMinutes())
        assertEquals(60, reloaded.bufferDurationMinutesFlow.first())
    }

    @Test
    fun `bufferDurationMinutesFlow reacts to a later write on the same instance, not just the value at construction`() = runTest {
        val preferences = DataStoreRetentionWindowPreferences(newDataStore())
        assertEquals(AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES, preferences.bufferDurationMinutesFlow.first())

        preferences.setBufferDurationMinutes(15)

        assertEquals(15, preferences.bufferDurationMinutesFlow.first())
    }

    @Test
    fun `setBufferDurationMinutes rejects a value outside the bounded options instead of silently persisting it`() = runTest {
        val preferences = DataStoreRetentionWindowPreferences(newDataStore())

        var thrown: IllegalArgumentException? = null
        try {
            preferences.setBufferDurationMinutes(45)
        } catch (e: IllegalArgumentException) {
            thrown = e
        }

        assertEquals(true, thrown != null)
        // Nothing was written -- still the fallback.
        assertEquals(AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES, preferences.currentBufferDurationMinutes())
    }

    // `@techlead` adjudication on PR #57, item 1 (`@sec` finding): a value that is *present but
    // out of bounds* is reachable through entirely normal use -- not only a hand-edited/corrupt
    // file -- e.g. a future release changing AudioConfig.RETENTION_WINDOW_OPTIONS_MINUTES combined
    // with a downgrade, or the option set otherwise shrinking, after a value from the old set was
    // persisted. This writes directly through a raw DataStore<Preferences> (bypassing
    // setBufferDurationMinutes's own write-side `require`, which is exactly the point: this
    // simulates a value that reached disk some other way, not one this class itself would ever
    // write today) using the identical key name DataStoreRetentionWindowPreferences uses --
    // Preferences DataStore keys compare by name+type, not instance identity, so this is a real
    // stand-in for "whatever is on disk", the same as `PreferencesProto` would produce.
    private val rawKeyBufferDurationMinutes = intPreferencesKey("buffer_duration_minutes")

    @Test
    fun `a persisted out-of-bounds value degrades to the fallback instead of reaching the caller`() = runTest {
        val rawDataStore = newDataStore()
        rawDataStore.edit { prefs -> prefs[rawKeyBufferDurationMinutes] = 1000 }

        val preferences = DataStoreRetentionWindowPreferences(rawDataStore)

        assertEquals(
            "an out-of-bounds stored value must never reach a caller that will use it to size a buffer",
            AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES,
            preferences.currentBufferDurationMinutes(),
        )
        assertEquals(AudioConfig.DEFAULT_BUFFER_DURATION_MINUTES, preferences.bufferDurationMinutesFlow.first())
    }
}
