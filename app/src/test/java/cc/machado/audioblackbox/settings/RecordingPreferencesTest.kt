package cc.machado.audioblackbox.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Persistence and reactivity tests for [DataStoreRecordingPreferences] and [InMemoryRecordingPreferences] (issue #79).
 */
class RecordingPreferencesTest {

    private lateinit var file: File
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        file = File.createTempFile("recording_prefs_test", ".preferences_pb")
        file.delete()
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        file.delete()
    }

    private fun newDataStore(): DataStore<Preferences> {
        val scope = CoroutineScope(SupervisorJob())
        scopes += scope
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @Test
    fun `before anything has been persisted, desired recording state is false`() = runTest {
        val preferences = DataStoreRecordingPreferences(newDataStore())

        assertFalse(preferences.isRecordingDesired())
        assertFalse(preferences.isRecordingDesired.first())
    }

    @Test
    fun `persisting desired recording state as true survives a fresh instance reading the same file (process-death round trip)`() = runTest {
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob)
        val firstDataStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        DataStoreRecordingPreferences(firstDataStore).setRecordingDesired(true)

        firstJob.cancelAndJoin()

        val reloaded = DataStoreRecordingPreferences(newDataStore())
        assertTrue(reloaded.isRecordingDesired())
        assertTrue(reloaded.isRecordingDesired.first())
    }

    @Test
    fun `persisting desired recording state as false updates and survives a fresh instance`() = runTest {
        val firstJob = SupervisorJob()
        val firstScope = CoroutineScope(firstJob)
        val firstDataStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        val firstPrefs = DataStoreRecordingPreferences(firstDataStore)
        firstPrefs.setRecordingDesired(true)
        firstPrefs.setRecordingDesired(false)

        firstJob.cancelAndJoin()

        val reloaded = DataStoreRecordingPreferences(newDataStore())
        assertFalse(reloaded.isRecordingDesired())
        assertFalse(reloaded.isRecordingDesired.first())
    }

    @Test
    fun `isRecordingDesired flow reacts to updates on the same instance`() = runTest {
        val preferences = DataStoreRecordingPreferences(newDataStore())
        assertFalse(preferences.isRecordingDesired.first())

        preferences.setRecordingDesired(true)
        assertTrue(preferences.isRecordingDesired.first())

        preferences.setRecordingDesired(false)
        assertFalse(preferences.isRecordingDesired.first())
    }

    @Test
    fun `InMemoryRecordingPreferences behaves identically to DataStore implementation`() = runTest {
        val inMemory = InMemoryRecordingPreferences(initialDesired = false)
        assertFalse(inMemory.isRecordingDesired())
        assertFalse(inMemory.isRecordingDesired.first())

        inMemory.setRecordingDesired(true)
        assertTrue(inMemory.isRecordingDesired())
        assertTrue(inMemory.isRecordingDesired.first())

        inMemory.setRecordingDesired(false)
        assertFalse(inMemory.isRecordingDesired())
        assertFalse(inMemory.isRecordingDesired.first())
    }
}
