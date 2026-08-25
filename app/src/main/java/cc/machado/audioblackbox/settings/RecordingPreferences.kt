package cc.machado.audioblackbox.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists the user's intent to continuously record audio (issue #79).
 *
 * Used to determine whether a 1-tap notification should prompt the user to resume recording
 * after a device reboot or app update, avoiding ForegroundServiceStartNotAllowedException on
 * Android 14+ by never attempting an automatic background restart of a microphone FGS.
 */
interface RecordingPreferences {
    val isRecordingDesired: Flow<Boolean>
    suspend fun isRecordingDesired(): Boolean
    suspend fun setRecordingDesired(desired: Boolean)
}

class DataStoreRecordingPreferences(private val dataStore: DataStore<Preferences>) : RecordingPreferences {

    override val isRecordingDesired: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_RECORDING_DESIRED] ?: false
    }

    override suspend fun isRecordingDesired(): Boolean = isRecordingDesired.first()

    override suspend fun setRecordingDesired(desired: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_RECORDING_DESIRED] = desired
        }
    }

    companion object {
        private val KEY_RECORDING_DESIRED = booleanPreferencesKey("recording_desired")

        operator fun invoke(context: Context): DataStoreRecordingPreferences =
            DataStoreRecordingPreferences(context.applicationContext.recordingPreferencesDataStore)
    }
}

private val Context.recordingPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "recording_prefs",
)

class InMemoryRecordingPreferences(
    initialDesired: Boolean = false,
) : RecordingPreferences {
    private val state = MutableStateFlow(initialDesired)

    override val isRecordingDesired: Flow<Boolean> = state

    override suspend fun isRecordingDesired(): Boolean = state.value

    override suspend fun setRecordingDesired(desired: Boolean) {
        state.value = desired
    }
}
