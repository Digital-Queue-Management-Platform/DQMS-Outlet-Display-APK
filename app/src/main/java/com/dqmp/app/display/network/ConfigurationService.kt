package com.dqmp.app.display.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import com.dqmp.app.display.model.ConfigurationData
import android.util.Log
import java.net.URL
import java.util.concurrent.TimeUnit

class ConfigurationService {
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    
    companion object {
        private const val TAG = "ConfigurationService"
    }
    
    /**
     * Validate configuration with the server
     */
    suspend fun validateConfiguration(configData: ConfigurationData): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${configData.serverUrl}/api/outlets/${configData.outletId}/validate"
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Configuration-ID", configData.configurationId)
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val json = JSONObject(responseBody ?: "{}")
                    
                    Log.d(TAG, "Configuration validation response: $json")
                    return@withContext json.optBoolean("valid", false)
                } else {
                    Log.w(TAG, "Configuration validation failed with code: ${response.code}")
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Configuration validation error", e)
            false
        }
    }
    
    /**
     * Parse configuration URL from QR code or deep link
     */
    suspend fun parseConfigurationUrl(url: String): ConfigurationData = withContext(Dispatchers.IO) {
        try {
            when {
                url.startsWith("dqmp://configure") -> {
                    parseDeepLinkConfiguration(url)
                }
                url.startsWith("http") -> {
                    fetchRemoteConfiguration(url)
                }
                else -> {
                    // Try to parse as JSON directly
                    parseJsonConfiguration(url)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse configuration URL: $url", e)
            throw IllegalArgumentException("Invalid configuration format")
        }
    }
    
    private fun parseDeepLinkConfiguration(url: String): ConfigurationData {
        val uri = android.net.Uri.parse(url)
        
        return ConfigurationData(
            outletId = uri.getQueryParameter("outletId") 
                ?: throw IllegalArgumentException("Missing outletId"),
            outletName = uri.getQueryParameter("outletName") 
                ?: "Outlet ${uri.getQueryParameter("outletId")}",
            serverUrl = uri.getQueryParameter("serverUrl") 
                ?: throw IllegalArgumentException("Missing serverUrl"),
            configurationId = uri.getQueryParameter("configId") 
                ?: throw IllegalArgumentException("Missing configId"),
            theme = uri.getQueryParameter("theme") ?: "emerald",
            language = uri.getQueryParameter("language") ?: "en"
        )
    }
    
    private suspend fun fetchRemoteConfiguration(url: String): ConfigurationData {
        val request = Request.Builder()
            .url(url)
            .build()
        
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to fetch configuration: ${response.code}")
            }
            
            val responseBody = response.body?.string() 
                ?: throw Exception("Empty configuration response")
            
            return parseJsonConfiguration(responseBody)
        }
    }
    
    private fun parseJsonConfiguration(jsonString: String): ConfigurationData {
        val json = JSONObject(jsonString)
        
        return ConfigurationData(
            outletId = json.getString("outletId"),
            outletName = json.optString("outletName", "Outlet ${json.getString("outletId")}"),
            serverUrl = json.getString("serverUrl"),
            configurationId = json.getString("configurationId"),
            theme = json.optString("theme", "emerald"),
            language = json.optString("language", "en")
        )
    }
    
    /**
     * Register device with the server
     */
    suspend fun registerDevice(
        serverUrl: String,
        deviceId: String,
        deviceName: String
    ): String = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", deviceName)
                put("deviceType", "android_tv_display")
                put("version", "1.0.0")
            }
            
            val requestBody = RequestBody.create(
                "application/json".toMediaType(),
                json.toString()
            )
            
            val request = Request.Builder()
                .url("$serverUrl/api/devices/register")
                .post(requestBody)
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val responseJson = JSONObject(responseBody ?: "{}")
                    
                    return@withContext responseJson.getString("registrationId")
                } else {
                    throw Exception("Device registration failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Device registration error", e)
            throw e
        }
    }
    
    /**
     * Check configuration status with server
     */
    suspend fun checkConfigurationStatus(
        serverUrl: String,
        deviceId: String
    ): ConfigurationData? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$serverUrl/api/devices/$deviceId/configuration")
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (!responseBody.isNullOrEmpty()) {
                        return@withContext parseJsonConfiguration(responseBody)
                    }
                }
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Configuration status check error", e)
            null
        }
    }
    
    /**
     * Notify server of successful configuration
     */
    suspend fun confirmConfiguration(
        serverUrl: String,
        configurationId: String,
        deviceId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("configurationId", configurationId)
                put("deviceId", deviceId)
                put("status", "configured")
                put("timestamp", System.currentTimeMillis())
            }
            
            val requestBody = RequestBody.create(
                "application/json".toMediaType(),
                json.toString()
            )
            
            val request = Request.Builder()
                .url("$serverUrl/api/configuration/confirm")
                .post(requestBody)
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Configuration confirmation error", e)
            false
        }
    }
}