package cc.machado.audioblackbox.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import cc.machado.audioblackbox.audio.AudioConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        val firstScope = CoroutineScope(SupervisorJob())
        val firstDataStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        DataStoreRetentionWindowPreferences(firstDataStore).setBufferDurationMinutes(60)
        // Cancelling this scope is what actually stands in for "the process died" -- DataStore
        // refuses a second live instance on the same file otherwise (by design, to catch real
        // multi-instance bugs), so this is not incidental test cleanup, it is the thing that makes
        // the assertion below a real "after a restart" read rather than the same live object.
        firstScope.cancel()

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
}
