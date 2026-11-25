package cc.machado.audioblackbox.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class SaveAudioFormat {
    AAC, OPUS;

    companion object {
        fun fromStoredId(id: String?): SaveAudioFormat = entries.firstOrNull { it.name == id } ?: AAC
    }
}

/** Export-only preference: changing this never rebuilds capture or resizes the ring buffer. */
class SaveAudioFormatPreferences(private val store: DataStore<Preferences>) {
    val format: Flow<SaveAudioFormat> = store.data.map { SaveAudioFormat.fromStoredId(it[KEY]) }
    suspend fun current(): SaveAudioFormat = format.first()
    suspend fun set(format: SaveAudioFormat) { store.edit { it[KEY] = format.name } }

    companion object {
        private val KEY = stringPreferencesKey("save_audio_format")
        operator fun invoke(context: Context): SaveAudioFormatPreferences =
            SaveAudioFormatPreferences(context.applicationContext.saveAudioFormatDataStore)
    }
}

private val Context.saveAudioFormatDataStore by preferencesDataStore(
    name = "save_audio_format_prefs",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
        androidx.datastore.preferences.core.emptyPreferences()
    },
)
