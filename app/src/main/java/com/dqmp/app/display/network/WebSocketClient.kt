package com.dqmp.app.display.network

import android.util.Log
import com.dqmp.app.display.audio.EnhancedAudioManager
import com.dqmp.app.display.data.DisplaySettings
import com.dqmp.app.display.model.QueueItem
import com.dqmp.app.display.model.TokenCall
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * Production-ready WebSocket client for DQMP Android TV display
 * Integrates with existing backend room-based subscription system
 */
class WebSocketClient(
    private val baseUrl: String,
    private val audioManager: EnhancedAudioManager,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "WebSocketClient"
        private const val HEARTBEAT_INTERVAL_MS = 25000L // 25 seconds (matching frontend)
        private const val RECONNECT_INITIAL_DELAY_MS = 1000L
        private const val RECONNECT_MAX_DELAY_MS = 30000L
        private const val RECONNECT_MULTIPLIER = 1.5f
        private const val MAX_RECONNECT_ATTEMPTS = Int.MAX_VALUE // Infinite retry
    }

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectAttempts = 0
    
    // Connection parameters
    private var currentOutletId: String? = null
    private var currentDeviceId: String? = null
    
    // OkHttp client with production settings
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS) // Keep connection alive
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // No read timeout for persistent connection
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    
    // State flows
    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()
    
    private val _queueUpdates = MutableStateFlow<List<QueueItem>>(emptyList())
    val queueUpdates: StateFlow<List<QueueItem>> = _queueUpdates.asStateFlow()
    
    private val _tokenCalls = MutableStateFlow<TokenCall?>(null)
    val tokenCalls: StateFlow<TokenCall?> = _tokenCalls.asStateFlow()
    
    private val _lastEventTime = MutableStateFlow<Long>(0L)
    val lastEventTime: StateFlow<Long> = _lastEventTime.asStateFlow()
    
    // Event callbacks for real-time updates
    var onDisplaySettingsUpdated: ((DisplaySettings) -> Unit)? = null
    var onOfficerStatusChanged: ((String, String, Boolean) -> Unit)? = null // officerId, status, isAvailable
    
    enum class ConnectionStatus {
        Connected,
        Disconnected,
        Connecting,
        Reconnecting,
        Error
    }
    
    /**
     * Connect to WebSocket with outlet and device ID
     * Implements backend's room-based subscription system
     */
    fun connect(outletId: String, deviceId: String? = null) {
        currentOutletId = outletId
        currentDeviceId = deviceId
        
        disconnect() // Clean up any existing connection
        
        Log.i(TAG, "Connecting to outlet: $outletId, device: $deviceId")
        _connectionStatus.value = ConnectionStatus.Connecting
        
        attemptConnection()
    }
    
    /**
     * Attempt WebSocket connection with proper URL parameters
     * Matches backend's connection parameter parsing
     */
    private fun attemptConnection() {
        val outletId = currentOutletId ?: return
        val deviceId = currentDeviceId ?: UUID.randomUUID().toString()
        
        val wsUrl = baseUrl.replace("http", "ws").replace("https", "wss")
        val urlBuilder = StringBuilder(wsUrl)
        
        // Add WebSocket endpoint with query parameters for backend registration
        urlBuilder.append("/?")
        urlBuilder.append("outletId=${outletId}")
        urlBuilder.append("&deviceId=${deviceId}")
        
        Log.d(TAG, "WebSocket URL: $urlBuilder")
        
        val request = Request.Builder()
            .url(urlBuilder.toString())
            .build()
            
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected to outlet: $outletId")
                _connectionStatus.value = ConnectionStatus.Connected
                reconnectAttempts = 0 // Reset reconnect counter
                
                // Start heartbeat after connection
                startHeartbeat()
                
                // Subscribe to outlet device room (redundant but ensures connection)
                sendSubscribeToOutletDevices(outletId)
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
            
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMessage(bytes.utf8())
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code - $reason")
                stopHeartbeat()
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code - $reason")
                _connectionStatus.value = ConnectionStatus.Disconnected
                stopHeartbeat()
                
                // Auto-reconnect unless explicitly disconnected
                if (code != 1000) { // 1000 = normal closure
                    scheduleReconnect()
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket connection failed", t)
                _connectionStatus.value = ConnectionStatus.Error
                stopHeartbeat()
                
                scheduleReconnect()
            }
        })
    }
    
    /**
     * Handle incoming WebSocket messages
     * Matches backend broadcast message format
     */
    private fun handleMessage(message: String) {
        try {
            _lastEventTime.value = System.currentTimeMillis()
            
            val json = JSONObject(message)
            val messageType = json.getString("type")
            
            Log.d(TAG, "Received message type: $messageType")
            
            when (messageType) {
                "TOKEN_CALLED" -> {
                    handleTokenCalled(json.getJSONObject("data"))
                }
                "TOKEN_RECALLED" -> {
                    handleTokenRecalled(json.getJSONObject("data"))
                }
                "TEST_SOUND" -> {
                    handleTestSound(json.getJSONObject("data"))
                }
                "HEARTBEAT_ACK" -> {
                    Log.v(TAG, "Heartbeat acknowledged")
                }
                "SUBSCRIBED_OUTLET_DEVICES" -> {
                    val outletId = json.optString("outletId")
                    Log.i(TAG, "Subscribed to outlet devices: $outletId")
                }
                "DISPLAY_SETTINGS_UPDATED" -> {
                    handleDisplaySettingsUpdated(json.optJSONObject("data"))
                }
                "OFFICER_STATUS_CHANGED" -> {
                    handleOfficerStatusChanged(json.optJSONObject("data"))
                }
                else -> {
                    Log.d(TAG, "Unknown message type: $messageType")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing WebSocket message: $message", e)
        }
    }
    
    /**
     * Handle TOKEN_CALLED event - core functionality
     * Matches backend officer.routes.ts broadcast format
     */
    private fun handleTokenCalled(data: JSONObject) {
        try {
            // Check if this event is for our outlet
            val eventOutletId = data.optString("outletId")
            if (eventOutletId.isNotEmpty() && eventOutletId != currentOutletId) {
                Log.d(TAG, "Ignoring TOKEN_CALLED for different outlet: $eventOutletId")
                return
            }
            
            val tokenNumber = data.optInt("tokenNumber", 0)
            val counterNumber = data.optInt("counterNumber", 0)
            val firstName = data.optString("firstName", "Customer")
            val customerLang = data.optString("customerLang", "en")
            
            Log.i(TAG, "Token called: $tokenNumber at counter $counterNumber, lang: $customerLang")
            
            val tokenCall = TokenCall(
                tokenNumber = tokenNumber.toString(),
                counterId = counterNumber.toString(),
                counterName = "Counter $counterNumber",
                timestamp = data.optString("calledAt", System.currentTimeMillis().toString()),
                customerName = firstName,
                language = customerLang
            )
            
            _tokenCalls.value = tokenCall
            
            // Trigger audio announcement
            audioManager.announceTokenCalled(
                tokenNumber = tokenNumber,
                counterNumber = counterNumber,
                customerName = firstName,
                language = customerLang
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling TOKEN_CALLED", e)
        }
    }
    
    /**
     * Handle TOKEN_RECALLED event
     */
    private fun handleTokenRecalled(data: JSONObject) {
        try {
            val eventOutletId = data.optString("outletId")
            if (eventOutletId.isNotEmpty() && eventOutletId != currentOutletId) {
                return
            }
            
            val tokenNumber = data.optInt("tokenNumber", 0)
            val counterNumber = data.optInt("counterNumber", 0)
            val firstName = data.optString("firstName", "Customer")
            val customerLang = data.optString("customerLang", "en")
            
            Log.i(TAG, "Token recalled: $tokenNumber at counter $counterNumber")
            
            // Trigger recall announcement
            audioManager.announceTokenRecalled(
                tokenNumber = tokenNumber,
                counterNumber = counterNumber,
                customerName = firstName,
                language = customerLang
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling TOKEN_RECALLED", e)
        }
    }
    
    /**
     * Handle TEST_SOUND event from manager dashboard
     * Matches teleshop-manager.routes.ts broadcast format
     */
    private fun handleTestSound(data: JSONObject) {
        try {
            val eventOutletId = data.optString("outletId")
            if (eventOutletId.isNotEmpty() && eventOutletId != currentOutletId) {
                Log.d(TAG, "Ignoring TEST_SOUND for different outlet: $eventOutletId")
                return
            }
            
            val testType = data.optString("testType", "chime")
            val lang = data.optString("lang", "en")
            val customText = data.optString("customText")
            val chimeVolume = data.optInt("chimeVolume", 100)
            val voiceVolume = data.optInt("voiceVolume", 300)
            
            Log.i(TAG, "Test sound: type=$testType, lang=$lang, chimeVolume=$chimeVolume, voiceVolume=$voiceVolume")
            
            when (testType) {
                "chime" -> {
                    audioManager.playTestChime(chimeVolume)
                }
                "voice" -> {
                    val message = if (customText.isNotEmpty()) {
                        customText
                    } else {
                        when (lang) {
                            "si" -> "ශබ්ද විකාශන යන්ත්‍ර පරීක්ෂා කිරීම. එය සාර්ථකව ක්‍රියා කරයි."
                            "ta" -> "ஒலிபெருக்கி சோதனை. இது சரியாக வேலை செய்கிறது."
                            else -> "Testing the speakers. It is working fine."
                        }
                    }
                    
                    audioManager.announceTestMessage(message, lang, voiceVolume)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling TEST_SOUND", e)
        }
    }
    
    /**
     * Handle display settings updates
     */
    private fun handleDisplaySettingsUpdated(data: JSONObject?) {
        try {
            if (data != null) {
                Log.i(TAG, "Processing display settings update: ${data}")
                
                // Parse display settings from event data
                val newSettings = DisplaySettings(
                    refresh = if (data.has("refresh")) data.getInt("refresh") else null,
                    next = if (data.has("next")) data.getInt("next") else null,
                    services = if (data.has("services")) data.getBoolean("services") else null,
                    counters = if (data.has("counters")) data.getBoolean("counters") else null,
                    recent = if (data.has("recent")) data.getBoolean("recent") else null,
                    autoSlide = if (data.has("autoSlide")) data.getBoolean("autoSlide") else null,
                    playTone = if (data.has("playTone")) data.getBoolean("playTone") else null,
                    contentScale = if (data.has("contentScale")) data.getInt("contentScale") else null
                )
                
                // Trigger callback to update configuration
                onDisplaySettingsUpdated?.invoke(newSettings)
                
                Log.i(TAG, "✅ Display settings successfully updated: refresh=${newSettings.refresh}, next=${newSettings.next}, scale=${newSettings.contentScale}")
            } else {
                Log.w(TAG, "⚠️ Display settings update received with null data")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to process display settings update: ${e.message}", e)
        }
    }
    
    /**
     * Handle officer status changes
     */
    private fun handleOfficerStatusChanged(data: JSONObject?) {
        try {
            if (data != null) {
                Log.i(TAG, "Processing officer status change: ${data}")
                
                val officerId = data.optString("officerId", "")
                val status = data.optString("status", "")
                val isAvailable = data.optBoolean("isAvailable", false)
                val counterNumber = data.optString("counterNumber", "")
                
                Log.i(TAG, "👮 Officer status: $officerId at counter $counterNumber is $status (available: $isAvailable)")
                
                // Trigger callback to update counter status display
                onOfficerStatusChanged?.invoke(officerId, status, isAvailable)
                
                // Mark event time for UI updates
                _lastEventTime.value = System.currentTimeMillis()
                
            } else {
                Log.w(TAG, "⚠️ Officer status change received with null data")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to process officer status change: ${e.message}", e)
        }
    }
    
    /**
     * Start heartbeat to keep connection alive
     * Matches backend's heartbeat expectation (25 second interval)
     */
    private fun startHeartbeat() {
        stopHeartbeat() // Clean up any existing heartbeat
        
        heartbeatJob = coroutineScope.launch {
            while (isActive && webSocket != null) {
                try {
                    val heartbeatMessage = JSONObject().apply {
                        put("type", "HEARTBEAT")
                        put("timestamp", System.currentTimeMillis())
                        if (currentDeviceId != null) {
                            put("deviceId", currentDeviceId)
                        }
                    }
                    
                    webSocket?.send(heartbeatMessage.toString())
                    Log.v(TAG, "Heartbeat sent")
                    
                    delay(HEARTBEAT_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat error", e)
                    break
                }
            }
        }
    }
    
    /**
     * Stop heartbeat
     */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
    
    /**
     * Subscribe to outlet device updates (explicit subscription)
     */
    private fun sendSubscribeToOutletDevices(outletId: String) {
        try {
            val subscribeMessage = JSONObject().apply {
                put("type", "SUBSCRIBE_OUTLET_DEVICES")
                put("outletId", outletId)
            }
            
            webSocket?.send(subscribeMessage.toString())
            Log.d(TAG, "Sent subscription request for outlet: $outletId")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending subscription", e)
        }
    }
    
    /**
     * Schedule reconnection with exponential backoff
     */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return // Already reconnecting
        
        reconnectAttempts++
        val delay = calculateReconnectDelay()
        
        Log.i(TAG, "Scheduling reconnect attempt $reconnectAttempts in ${delay}ms")
        _connectionStatus.value = ConnectionStatus.Reconnecting
        
        reconnectJob = coroutineScope.launch {
            try {
                delay(delay)
                if (isActive && currentOutletId != null) {
                    Log.i(TAG, "Attempting reconnect #$reconnectAttempts")
                    attemptConnection()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reconnect attempt failed", e)
            }
        }
    }
    
    /**
     * Calculate reconnect delay with exponential backoff
     */
    private fun calculateReconnectDelay(): Long {
        val exponentialDelay = (RECONNECT_INITIAL_DELAY_MS * 
            RECONNECT_MULTIPLIER.pow(min(reconnectAttempts - 1, 10))).toLong()
        return min(exponentialDelay, RECONNECT_MAX_DELAY_MS)
    }
    
    /**
     * Send custom message to server
     */
    fun sendMessage(message: String): Boolean {
        return try {
            webSocket?.send(message) ?: false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            false
        }
    }
    
    /**
     * Send device heartbeat with status update
     */
    fun sendDeviceHeartbeat(deviceId: String? = null): Boolean {
        return try {
            val heartbeatMessage = JSONObject().apply {
                put("type", "DEVICE_HEARTBEAT")
                put("deviceId", deviceId ?: currentDeviceId)
                put("timestamp", System.currentTimeMillis())
                put("status", "active")
            }
            
            sendMessage(heartbeatMessage.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending device heartbeat", e)
            false
        }
    }
    
    /**
     * Get connection health info
     */
    fun getConnectionInfo(): Map<String, Any> {
        return mapOf(
            "status" to connectionStatus.value.name,
            "outletId" to (currentOutletId ?: "none"),
            "deviceId" to (currentDeviceId ?: "none"),
            "reconnectAttempts" to reconnectAttempts,
            "lastEventTime" to lastEventTime.value,
            "isConnected" to (connectionStatus.value == ConnectionStatus.Connected)
        )
    }
    
    /**
     * Force reconnect (useful for testing or manual recovery)
     */
    fun forceReconnect() {
        Log.i(TAG, "Force reconnecting...")
        reconnectAttempts = 0
        disconnect()
        currentOutletId?.let { outletId ->
            connect(outletId, currentDeviceId)
        }
    }
    
    /**
     * Disconnect WebSocket cleanly
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting WebSocket")
        
        stopHeartbeat()
        reconnectJob?.cancel()
        reconnectJob = null
        
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        
        _connectionStatus.value = ConnectionStatus.Disconnected
    }
    
    /**
     * Cleanup all resources
     */
    fun cleanup() {
        disconnect()
        client.dispatcher.executorService.shutdown()
    }
}