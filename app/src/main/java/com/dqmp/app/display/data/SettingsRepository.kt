package com.dqmp.app.display.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dqmp_production_settings")

class SettingsRepository(private val context: Context) {
    
    companion object {
        val KEY_OUTLET_ID = stringPreferencesKey("outlet_id")
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val DEFAULT_URL = "https://sltsecmanage.slt.lk:7443/"
    }

    val outletId: Flow<String?> = context.dataStore.data.map { it[KEY_OUTLET_ID] }
    val baseUrl: Flow<String> = context.dataStore.data.map { it[KEY_BASE_URL] ?: DEFAULT_URL }

    /**
     * Helper to get current settings synchronously on a background thread for non-Compose contexts.
     */
    suspend fun getSettingsSnapshot(): Pair<String?, String> {
        val prefs = context.dataStore.data.first()
        return Pair(prefs[KEY_OUTLET_ID], prefs[KEY_BASE_URL] ?: DEFAULT_URL)
    }

    suspend fun saveSettings(id: String, url: String) {
        // Validation: Ensure URL ends with / for Retrofit
        val sanitizedUrl = if (url.endsWith("/")) url else "$url/"
        context.dataStore.edit {
            it[KEY_OUTLET_ID] = id.trim()
            it[KEY_BASE_URL] = sanitizedUrl.trim()
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit {
            it.clear()
        }
    }
}
