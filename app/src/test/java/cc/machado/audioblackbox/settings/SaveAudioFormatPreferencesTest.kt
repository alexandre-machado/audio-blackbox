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
    // Oracle: the production delegate replaces a malformed protobuf and exposes AAC, then
    // remains writable. Testing an independently constructed handler would miss its wiring.
    @Test fun corruptProductionPreferencesRecoverToAac() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("corrupt-format").toFile()
        val context = org.mockito.kotlin.mock<android.content.Context>()
        org.mockito.kotlin.whenever(context.applicationContext).thenReturn(context)
        org.mockito.kotlin.whenever(context.filesDir).thenReturn(dir)
        File(dir, "datastore/save_audio_format_prefs.preferences_pb").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(0x0a, 0x7f))
        }
        val preferences = SaveAudioFormatPreferences(context)
        assertEquals(SaveAudioFormat.AAC, preferences.current())
        preferences.set(SaveAudioFormat.OPUS)
        assertEquals(SaveAudioFormat.OPUS, preferences.current())
    }
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
