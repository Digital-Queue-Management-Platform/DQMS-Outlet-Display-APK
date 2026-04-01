package com.dqmp.app.display.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Production-ready Device Manager for Android TV APK
 * 
 * Handles device registration, heartbeat, and configuration management
 * Integrates with existing backend API endpoints for seamless deployment
 */

@Serializable
data class DeviceRegistrationInfo(
    val deviceId: String,
    val deviceName: String,
    val macAddress: String,
    val androidVersion: String,
    val appVersion: String,
    val timestamp: Long
)

@Serializable
data class DeviceRegistrationRequest(
    val deviceId: String,
    val deviceInfo: DeviceRegistrationInfo,
    val setupCode: String
)

@Serializable
data class DeviceHeartbeat(
    val deviceId: String,
    val timestamp: Long,
    val status: String = "active",
    val appVersion: String,
    val connectionStatus: String = "connected"
)

@Serializable
data class ConfigurationCheckResponse(
    val isConfigured: Boolean,
    val outletId: String? = null,
    val outletName: String? = null,
    val baseUrl: String? = null,
    val displaySettings: JsonObject? = null
)

class DeviceManager(
    private val context: Context,
    private val baseUrl: String = "https://sltsecmanage.slt.lk:7443"
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    companion object {
        private const val TAG = "DeviceManager"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L // 30 seconds
        private const val CONFIG_CHECK_INTERVAL_MS = 3_000L // 3 seconds
        private const val CONNECTION_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 10_000
    }
    
    /**
     * Start polling for device configuration
     * Called from QR setup screen when waiting for manager to configure device
     */
    suspend fun pollForConfiguration(
        deviceId: String,
        onConfigurationFound: (String, String) -> Unit,
        onError: (String) -> Unit
    ): Job {
        return scope.launch {
            Log.i(TAG, "🔍 Starting configuration polling for device: $deviceId")
            
            while (isActive) {
                try {
                    val config = checkDeviceConfiguration(deviceId)
                    if (config.isConfigured && config.outletId != null) {
                        Log.i(TAG, "✅ Configuration found for outlet: ${config.outletId}")
                        onConfigurationFound(config.outletId, baseUrl)
                        break
                    } else {
                        Log.d(TAG, "⏳ Configuration not ready yet, retrying in ${CONFIG_CHECK_INTERVAL_MS}ms")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "❌ Configuration check failed: ${e.message}")
                    onError("Configuration check failed: ${e.message}")
                }
                
                delay(CONFIG_CHECK_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Check if device has been configured by teleshop manager
     */
    suspend fun checkDeviceConfiguration(deviceId: String): ConfigurationCheckResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/api/teleshop-manager/check-device-config/$deviceId")
                val connection = url.openConnection() as HttpURLConnection
                
                connection.apply {
                    requestMethod = "GET"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = CONNECTION_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                }
                
                val responseCode = connection.responseCode
                Log.d(TAG, "📡 Config check response: $responseCode")
                
                if (responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.readText()
                    reader.close()
                    
                    Log.d(TAG, "📋 Config response: $response")
                    json.decodeFromString<ConfigurationCheckResponse>(response)
                } else {
                    val errorReader = BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream))
                    val errorResponse = errorReader.readText()
                    errorReader.close()
                    
                    Log.w(TAG, "❌ Config check failed with code $responseCode: $errorResponse")
                    ConfigurationCheckResponse(isConfigured = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "💥 Config check exception", e)
                throw e
            }
        }
    }
    
    /**
     * Start sending heartbeat to backend
     * Should be called when APK is connected and operational
     */
    fun startHeartbeat(deviceId: String, outletId: String) {
        stopHeartbeat() // Stop any existing heartbeat
        
        Log.i(TAG, "💓 Starting heartbeat for device: $deviceId, outlet: $outletId")
        
        heartbeatJob = scope.launch {
            while (isActive) {
                try {
                    val success = sendHeartbeat(deviceId, outletId)
                    if (success) {
                        Log.d(TAG, "💓 Heartbeat sent successfully")
                    } else {
                        Log.w(TAG, "💔 Heartbeat failed")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "💥 Heartbeat exception", e)
                }
                
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Stop heartbeat service
     */
    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        Log.d(TAG, "🛑 Heartbeat stopped")
    }
    
    /**
     * Send heartbeat to backend
     */
    private suspend fun sendHeartbeat(deviceId: String, outletId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val heartbeat = DeviceHeartbeat(
                    deviceId = deviceId,
                    timestamp = System.currentTimeMillis(),
                    appVersion = getAppVersion(),
                    connectionStatus = "connected"
                )
                
                val url = URL("$baseUrl/api/teleshop-manager/device-heartbeat/$deviceId")
                val connection = url.openConnection() as HttpURLConnection
                
                connection.apply {
                    requestMethod = "PUT"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = CONNECTION_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                }
                
                // Send heartbeat data
                val writer = OutputStreamWriter(connection.outputStream)
                writer.write(json.encodeToString(heartbeat))
                writer.flush()
                writer.close()
                
                val responseCode = connection.responseCode
                Log.d(TAG, "💓 Heartbeat response: $responseCode")
                
                if (responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = reader.readText()
                    reader.close()
                    Log.d(TAG, "💓 Heartbeat response body: $response")
                    true
                } else {
                    val errorReader = BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream))
                    val errorResponse = errorReader.readText()
                    errorReader.close()
                    Log.w(TAG, "💔 Heartbeat failed with code $responseCode: $errorResponse")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "💥 Heartbeat send exception", e)
                false
            }
        }
    }
    
    /**
     * Generate device info for registration/heartbeat
     */
    fun generateDeviceInfo(): DeviceRegistrationInfo {
        val deviceId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
        
        val deviceName = android.os.Build.MODEL
        val androidVersion = android.os.Build.VERSION.RELEASE
        val appVersion = getAppVersion()
        val macAddress = getMacAddress()
        
        return DeviceRegistrationInfo(
            deviceId = deviceId,
            deviceName = deviceName,
            macAddress = macAddress,
            androidVersion = androidVersion,
            appVersion = appVersion,
            timestamp = System.currentTimeMillis()
        )
    }
    
    /**
     * Get app version
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.versionCode})"
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    /**
     * Get MAC address (placeholder for newer Android versions)
     */
    private fun getMacAddress(): String {
        return try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo?.macAddress ?: "02:00:00:00:00:00"
        } catch (e: Exception) {
            "02:00:00:00:00:00"
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        stopHeartbeat()
        scope.cancel()
        Log.i(TAG, "🧹 DeviceManager cleaned up")
    }
    
    /**
     * Test connectivity to backend
     */
    suspend fun testConnectivity(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/api/health")
                val connection = url.openConnection() as HttpURLConnection
                
                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                
                val responseCode = connection.responseCode
                Log.d(TAG, "🔗 Connectivity test response: $responseCode")
                
                responseCode == 200
            } catch (e: Exception) {
                Log.e(TAG, "🔗 Connectivity test failed", e)
                false
            }
        }
    }
}

/**
 * Device status tracking
 */
object DeviceStatus {
    const val ACTIVE = "active"
    const val INACTIVE = "inactive"
    const val CONFIGURATION_PENDING = "configuration_pending"
    const val CONFIGURATION_COMPLETE = "configuration_complete"
    const val ERROR = "error"
}

/**
 * Connection status tracking
 */
object ConnectionStatus {
    const val CONNECTED = "connected"
    const val CONNECTING = "connecting"
    const val DISCONNECTED = "disconnected"
    const val RECONNECTING = "reconnecting"
    const val ERROR = "error"
}