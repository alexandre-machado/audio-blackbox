package cc.machado.audioblackbox.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveAudioFormatPreferencesTest {
    // Oracle: real DataStore reads, including unknown persisted IDs, retain the AAC default.
    @Test fun missingAndUnknownIdsSelectAac() = runTest {
        val file = File.createTempFile("format-default", ".preferences_pb").apply { delete() }
        val job = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job)) { file }
            val preferences = SaveAudioFormatPreferences(store)
            assertEquals(SaveAudioFormat.AAC, preferences.current())
            store.edit { it[stringPreferencesKey("save_audio_format")] = "FUTURE_FORMAT" }
            assertEquals(SaveAudioFormat.AAC, preferences.current())
        } finally { job.cancelAndJoin(); file.delete() }
    }

    // Oracle: OPUS survives closing and reopening the actual preference file, with its stable ID.
    @Test fun opusSurvivesReopenAndAacRequiresExplicitSelection() = runTest {
        val file = File.createTempFile("format-persist", ".preferences_pb").apply { delete() }
        val firstJob = SupervisorJob()
        val secondJob = SupervisorJob()
        try {
            val first = PreferenceDataStoreFactory.create(scope = CoroutineScope(firstJob)) { file }
            SaveAudioFormatPreferences(first).set(SaveAudioFormat.OPUS)
            firstJob.cancelAndJoin()
            val second = PreferenceDataStoreFactory.create(scope = CoroutineScope(secondJob)) { file }
            val preferences = SaveAudioFormatPreferences(second)
            assertEquals(SaveAudioFormat.OPUS, preferences.current())
            preferences.set(SaveAudioFormat.AAC)
            assertEquals(SaveAudioFormat.AAC, preferences.current())
        } finally { firstJob.cancelAndJoin(); secondJob.cancelAndJoin(); file.delete() }
    }
}
