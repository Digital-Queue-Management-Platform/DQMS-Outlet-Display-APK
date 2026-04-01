package com.dqmp.app.display.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.dqmp.app.display.model.DisplaySettings

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "display_settings")

class SettingsRepository(private val context: Context) {
    
    companion object {
        private val LANGUAGE_KEY = stringPreferencesKey("language")
        private val VOLUME_KEY = floatPreferencesKey("volume")
        private val ENABLE_ANNOUNCEMENTS_KEY = booleanPreferencesKey("enable_announcements")
        private val THEME_KEY = stringPreferencesKey("theme")
        private val SHOW_QUEUE_NUMBERS_KEY = booleanPreferencesKey("show_queue_numbers")
        private val AUTO_SCROLL_SPEED_KEY = intPreferencesKey("auto_scroll_speed")
        private val OUTLET_ID_KEY = stringPreferencesKey("outlet_id")
        private val SERVER_URL_KEY = stringPreferencesKey("server_url")
        private val IS_CONFIGURED_KEY = booleanPreferencesKey("is_configured")
        private val CONFIGURATION_ID_KEY = stringPreferencesKey("configuration_id")
        private val OUTLET_NAME_KEY = stringPreferencesKey("outlet_name")
    }
    
    val settings: Flow<DisplaySettings> = context.dataStore.data.map { preferences ->
        DisplaySettings(
            language = preferences[LANGUAGE_KEY] ?: "en",
            volume = preferences[VOLUME_KEY] ?: 1.0f,
            enableAnnouncements = preferences[ENABLE_ANNOUNCEMENTS_KEY] ?: true,
            theme = preferences[THEME_KEY] ?: "emerald",
            showQueueNumbers = preferences[SHOW_QUEUE_NUMBERS_KEY] ?: true,
            autoScrollSpeed = preferences[AUTO_SCROLL_SPEED_KEY] ?: 3000,
            outletId = preferences[OUTLET_ID_KEY] ?: "",
            serverUrl = preferences[SERVER_URL_KEY] ?: "http://localhost:3000",
            isConfigured = preferences[IS_CONFIGURED_KEY] ?: false,
            configurationId = preferences[CONFIGURATION_ID_KEY] ?: "",
            outletName = preferences[OUTLET_NAME_KEY] ?: ""
        )
    }
    
    suspend fun updateSettings(newSettings: DisplaySettings) {
        context.dataStore.edit { preferences ->
            preferences[LANGUAGE_KEY] = newSettings.language
            preferences[VOLUME_KEY] = newSettings.volume
            preferences[ENABLE_ANNOUNCEMENTS_KEY] = newSettings.enableAnnouncements
            preferences[THEME_KEY] = newSettings.theme
            preferences[SHOW_QUEUE_NUMBERS_KEY] = newSettings.showQueueNumbers
            preferences[AUTO_SCROLL_SPEED_KEY] = newSettings.autoScrollSpeed
            preferences[OUTLET_ID_KEY] = newSettings.outletId
            preferences[SERVER_URL_KEY] = newSettings.serverUrl
            preferences[IS_CONFIGURED_KEY] = newSettings.isConfigured
            preferences[CONFIGURATION_ID_KEY] = newSettings.configurationId
            preferences[OUTLET_NAME_KEY] = newSettings.outletName
        }
    }
    
    suspend fun updateLanguage(language: String) {
        context.dataStore.edit { preferences ->
            preferences[LANGUAGE_KEY] = language
        }
    }
    
    suspend fun updateVolume(volume: Float) {
        context.dataStore.edit { preferences ->
            preferences[VOLUME_KEY] = volume
        }
    }
    
    suspend fun updateTheme(theme: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_KEY] = theme
        }
    }
    
    suspend fun updateOutletId(outletId: String) {
        context.dataStore.edit { preferences ->
            preferences[OUTLET_ID_KEY] = outletId
        }
    }
    
    suspend fun updateServerUrl(serverUrl: String) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_URL_KEY] = serverUrl
        }
    }
    
    suspend fun resetConfiguration() {
        context.dataStore.edit { preferences ->
            preferences[IS_CONFIGURED_KEY] = false
            preferences[CONFIGURATION_ID_KEY] = ""
            preferences[OUTLET_ID_KEY] = ""
            preferences[OUTLET_NAME_KEY] = ""
            preferences[SERVER_URL_KEY] = ""
        }
    }
}