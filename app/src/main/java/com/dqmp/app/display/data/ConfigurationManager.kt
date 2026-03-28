package com.dqmp.app.display.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Professional Configuration Manager
 * 
 * Handles secure, encrypted storage of outlet configuration with backup/restore
 * capabilities. Provides one-time setup with permanent configuration storage
 * suitable for retail deployment environments.
 */

@Serializable
data class OutletConfiguration(
    val outletId: String,
    val baseUrl: String,
    val setupCode: String,
    val configuredAt: Long,
    val configuredBy: String = "QR_SETUP",
    val deviceInfo: DeviceConfigInfo,
    val displaySettings: DisplaySettings = DisplaySettings(),
    val audioSettings: AudioSettings = AudioSettings(),
    val securitySettings: SecuritySettings = SecuritySettings()
)

@Serializable
data class DeviceConfigInfo(
    val deviceId: String,
    val deviceName: String,
    val macAddress: String,
    val androidVersion: String,
    val appVersion: String,
    val firstConfigured: Long,
    val lastUpdated: Long
)

@Serializable
data class AudioSettings(
    val enabled: Boolean = true,
    val volume: Float = 0.8f,
    val ttsLanguage: String = "en",
    val ttsSpeed: Float = 1.0f,
    val playTone: Boolean = true,
    val announceCustomerName: Boolean = true,
    val announceCounterNumber: Boolean = true
)

@Serializable
data class SecuritySettings(
    val kioskMode: Boolean = true,
    val allowManualConfig: Boolean = false,
    val requireSetupCode: Boolean = true,
    val autoLock: Boolean = true,
    val adminResetEnabled: Boolean = true
)

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "outlet_config")

class ConfigurationManager(private val context: Context) {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    // Encrypted SharedPreferences for sensitive data
    private val encryptedPrefs by lazy {
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "dqmp_secure_config",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e("CONFIG_MGR", "Failed to create encrypted preferences", e)
            context.getSharedPreferences("dqmp_config_fallback", Context.MODE_PRIVATE)
        }
    }
    
    // DataStore keys
    private val configurationKey = stringPreferencesKey("outlet_configuration")
    private val backupConfigKey = stringPreferencesKey("backup_configuration")
    private val lastSyncKey = stringPreferencesKey("last_sync_time")
    
    /**
     * Save complete outlet configuration securely
     */
    suspend fun saveConfiguration(config: OutletConfiguration) {
        try {
            // Save to encrypted preferences (primary storage)
            encryptedPrefs.edit()
                .putString("outlet_config", json.encodeToString(config))
                .putString("last_updated", System.currentTimeMillis().toString())
                .apply()
            
            // Save to DataStore (backup)
            context.configDataStore.edit { prefs ->
                prefs[configurationKey] = json.encodeToString(config)
                prefs[lastSyncKey] = System.currentTimeMillis().toString()
            }
            
            Log.i("CONFIG_MGR", "Configuration saved successfully for outlet: ${config.outletId}")
            
        } catch (e: Exception) {
            Log.e("CONFIG_MGR", "Failed to save configuration", e)
            throw ConfigurationException("Failed to save outlet configuration", e)
        }
    }
    
    /**
     * Load outlet configuration
     */
    suspend fun getConfiguration(): OutletConfiguration? {
        return try {
            // Try encrypted preferences first
            val configJson = encryptedPrefs.getString("outlet_config", null)
            if (configJson != null) {
                return json.decodeFromString<OutletConfiguration>(configJson)
            }
            
            // Fall back to DataStore
            var config: OutletConfiguration? = null
            context.configDataStore.data.collect { prefs ->
                prefs[configurationKey]?.let { jsonString ->
                    config = json.decodeFromString<OutletConfiguration>(jsonString)
                }
            }
            
            config
        } catch (e: Exception) {
            Log.e("CONFIG_MGR", "Failed to load configuration", e)
            null
        }
    }
    
    /**
     * Check if device is configured
     */
    fun isConfigured(): Boolean {
        return try {
            encryptedPrefs.contains("outlet_config") || 
            encryptedPrefs.contains("outlet_id") // Legacy support
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get configuration as Flow for reactive updates
     */
    fun getConfigurationFlow(): Flow<OutletConfiguration?> {
        return context.configDataStore.data.map { prefs ->
            try {
                prefs[configurationKey]?.let { jsonString ->
                    json.decodeFromString<OutletConfiguration>(jsonString)
                }
            } catch (e: Exception) {
                Log.e("CONFIG_MGR", "Failed to deserialize configuration", e)
                null
            }
        }
    }
    
    /**
     * Update display settings only
     */
    suspend fun updateDisplaySettings(settings: DisplaySettings) {
        val currentConfig = getConfiguration()
        currentConfig?.let { config ->
            val updatedConfig = config.copy(
                displaySettings = settings,
                deviceInfo = config.deviceInfo.copy(lastUpdated = System.currentTimeMillis())
            )
            saveConfiguration(updatedConfig)
        }
    }
    
    /**
     * Update audio settings only
     */
    suspend fun updateAudioSettings(settings: AudioSettings) {
        val currentConfig = getConfiguration()
        currentConfig?.let { config ->
            val updatedConfig = config.copy(
                audioSettings = settings,
                deviceInfo = config.deviceInfo.copy(lastUpdated = System.currentTimeMillis())
            )
            saveConfiguration(updatedConfig)
        }
    }
    
    /**
     * Clear all configuration (admin reset)
     */
    suspend fun clearConfiguration() {
        try {
            // Clear encrypted preferences
            encryptedPrefs.edit().clear().apply()
            
            // Clear DataStore
            context.configDataStore.edit { prefs ->
                prefs.clear()
            }
            
            Log.i("CONFIG_MGR", "Configuration cleared successfully")
            
        } catch (e: Exception) {
            Log.e("CONFIG_MGR", "Failed to clear configuration", e)
            throw ConfigurationException("Failed to clear configuration", e)
        }
    }
    
    /**
     * Create backup of current configuration
     */
    suspend fun createBackup(): String? {
        return try {
            val config = getConfiguration()
            config?.let { 
                val backupJson = json.encodeToString(it)
                context.configDataStore.edit { prefs ->
                    prefs[backupConfigKey] = backupJson
                }
                backupJson
            }
        } catch (e: Exception) {
            Log.e("CONFIG_MGR", "Failed to create backup", e)
            null
        }
    }
    
    /**
     * Restore configuration from backup
     */
    suspend fun restoreFromBackup(backupJson: String): Boolean {
        return try {
            val config = json.decodeFromString<OutletConfiguration>(backupJson)
            saveConfiguration(config)
            true
        } catch (e: Exception) {
            Log.e("CONFIG_MGR", "Failed to restore from backup", e)
            false
        }
    }
    
    /**
     * Generate device info for new configuration
     */
    fun generateDeviceInfo(): DeviceConfigInfo {
        val deviceId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
        
        val deviceName = android.os.Build.MODEL
        val androidVersion = android.os.Build.VERSION.RELEASE
        val appVersion = getAppVersion()
        val macAddress = getMacAddress()
        val now = System.currentTimeMillis()
        
        return DeviceConfigInfo(
            deviceId = deviceId,
            deviceName = deviceName,
            macAddress = macAddress,
            androidVersion = androidVersion,
            appVersion = appVersion,
            firstConfigured = now,
            lastUpdated = now
        )
    }
    
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.versionCode})"
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    private fun getMacAddress(): String {
        return try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wifiManager.connectionInfo.macAddress ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    /**
     * Validate outlet configuration
     */
    fun validateConfiguration(config: OutletConfiguration): List<String> {
        val errors = mutableListOf<String>()
        
        if (config.outletId.isBlank()) {
            errors.add("Outlet ID cannot be empty")
        }
        
        if (config.baseUrl.isBlank()) {
            errors.add("Base URL cannot be empty")
        }
        
        if (!config.baseUrl.startsWith("http://") && !config.baseUrl.startsWith("https://")) {
            errors.add("Base URL must start with http:// or https://")
        }
        
        if (config.setupCode.isBlank()) {
            errors.add("Setup code cannot be empty")
        }
        
        return errors
    }
    
    companion object {
        const val DEFAULT_BASE_URL = "https://api.dqmp.app"
        const val CONFIG_VERSION = "1.0"
    }
}

class ConfigurationException(message: String, cause: Throwable? = null) : Exception(message, cause)