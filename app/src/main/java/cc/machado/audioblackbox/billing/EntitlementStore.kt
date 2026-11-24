package cc.machado.audioblackbox.billing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

interface EntitlementStore {
    suspend fun read(): Entitlement?
    suspend fun write(entitlement: Entitlement, checkedAtMillis: Long)
}

class DataStoreEntitlementStore(private val dataStore: DataStore<Preferences>) : EntitlementStore {
    override suspend fun read(): Entitlement? = when (dataStore.data.first()[STATE]) {
        "PURCHASE" -> Entitlement.Pro(ProSource.PURCHASE)
        "GRANDFATHERED" -> Entitlement.Pro(ProSource.GRANDFATHERED)
        "FREE" -> Entitlement.Free
        else -> null
    }

    override suspend fun write(entitlement: Entitlement, checkedAtMillis: Long) {
        require(entitlement != Entitlement.Unknown)
        dataStore.edit {
            it[STATE] = (entitlement as? Entitlement.Pro)?.source?.name ?: "FREE"
            it[CHECKED_AT] = checkedAtMillis
        }
    }

    companion object {
        private val STATE = stringPreferencesKey("entitlement")
        private val CHECKED_AT = longPreferencesKey("checked_at_ms")
        operator fun invoke(context: Context) =
            DataStoreEntitlementStore(context.applicationContext.proEntitlementDataStore)
    }
}

private val Context.proEntitlementDataStore by preferencesDataStore(name = "pro_entitlement")
