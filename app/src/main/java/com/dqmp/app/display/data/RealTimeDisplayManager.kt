package com.dqmp.app.display.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.dqmp.app.display.network.WebSocketClient
import com.dqmp.app.display.audio.EnhancedAudioManager
import com.dqmp.app.display.model.TokenCall
import androidx.lifecycle.LifecycleOwner

/**
 * Real-Time Display Manager
 * 
 * Orchestrates WebSocket connections, audio management, and display updates
 * for production Android TV outlet display deployment. Integrates the production
 * WebSocketClient with display UI and audio announcements.
 */

data class DisplayUpdate(
    val type: String, // TOKEN_CALLED, TOKEN_RECALLED, DISPLAY_UPDATE, etc.
    val data: Map<String, Any>,
    val timestamp: Long = System.currentTimeMillis()
)

data class ConnectionHealth(
    val isConnected: Boolean,
    val connectionStatus: WebSocketClient.ConnectionStatus,
    val lastHeartbeat: Long = 0L,
    val reconnectAttempts: Int = 0,
    val lastError: String? = null
)

class RealTimeDisplayManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val baseUrl: String = "https://sltsecmanage.slt.lk:7443"
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Core components
    private var webSocketClient: WebSocketClient? = null
    private var audioManager: EnhancedAudioManager? = null
    private var deviceManager: DeviceManager? = null
    
    // State flows
    private val _displayUpdates = MutableSharedFlow<DisplayUpdate>(replay = 0)
    val displayUpdates: SharedFlow<DisplayUpdate> = _displayUpdates.asSharedFlow()
    
    private val _connectionHealth = MutableStateFlow(
        ConnectionHealth(isConnected = false, connectionStatus = WebSocketClient.ConnectionStatus.Disconnected)
    )
    val connectionHealth: StateFlow<ConnectionHealth> = _connectionHealth.asStateFlow()
    
    // Configuration state
    private var currentOutletId: String? = null
    private var currentDeviceId: String? = null
    private var isInitialized = false
    
    companion object {
        private const val TAG = "RealTimeDisplayManager"
    }
    
    /**
     * Initialize with outlet configuration
     */
    fun initialize(outletId: String, deviceId: String?) {
        if (isInitialized && currentOutletId == outletId && currentDeviceId == deviceId) {
            Log.d(TAG, "🔄 Already initialized with same config, skipping")
            return
        }
        
        Log.i(TAG, "🚀 Initializing RealTimeDisplayManager for outlet: $outletId")
        
        currentOutletId = outletId
        currentDeviceId = deviceId
        isInitialized = true
        
        // Initialize audio manager
        audioManager = EnhancedAudioManager.getInstance(context, lifecycleOwner, baseUrl)
        
        // Initialize device manager for heartbeat
        deviceManager = DeviceManager(context, baseUrl)
        
        // Initialize WebSocket client with production configuration
        webSocketClient = WebSocketClient(
            baseUrl = baseUrl,
            audioManager = audioManager!!,
            coroutineScope = scope
        ).apply {
            // Observe connection status
            scope.launch {
                connectionStatus.collect { status ->
                    _connectionHealth.update { health ->
                        health.copy(
                            isConnected = status == WebSocketClient.ConnectionStatus.Connected,
                            connectionStatus = status,
                            lastError = if (status == WebSocketClient.ConnectionStatus.Error) "Connection failed" else null
                        )
                    }
                }
            }
            
            // Handle real-time events that affect display
            scope.launch {
                tokenCalls.collect { tokenCall: TokenCall? ->
                    if (tokenCall != null) {
                        handleTokenCallEvent(tokenCall)
                    }
                }
            }
        }
        
        // Connect to outlet
        connectToOutlet(outletId, deviceId)
        
        // Start device heartbeat
        deviceId?.let { deviceManager?.startHeartbeat(it, outletId) }
        
        Log.i(TAG, "✅ RealTimeDisplayManager initialization complete")
    }
    
    /**
     * Connect to outlet WebSocket room
     */
    private fun connectToOutlet(outletId: String, deviceId: String?) {
        webSocketClient?.let { client ->
            Log.i(TAG, "🔌 Connecting to outlet: $outletId, device: $deviceId")
            client.connect(outletId, deviceId)
        }
    }
    
    /**
     * Handle WebSocket events that affect display
     */
    private suspend fun handleWebSocketEvent(event: Map<String, Any>) {
        val eventType = event["type"] as? String ?: return
        val eventData = event["data"] as? Map<String, Any> ?: emptyMap()
        
        Log.d(TAG, "📡 Handling display event: $eventType")
        
        // Emit display update for UI to react to
        val displayUpdate = DisplayUpdate(
            type = eventType,
            data = eventData
        )
        _displayUpdates.emit(displayUpdate)
        
        // Handle specific event types that affect display
        when (eventType) {
            "TOKEN_CALLED", "TOKEN_RECALLED" -> {
                // Audio is handled by WebSocketClient, just emit display update
                Log.i(TAG, "🔊 Token event processed: $eventType")
            }
            "DISPLAY_SETTINGS_UPDATED" -> {
                Log.i(TAG, "⚙️ Display settings updated")
                // UI will react to displayUpdates flow
            }
            "OFFICER_STATUS_CHANGED" -> {
                Log.i(TAG, "👤 Officer status changed")
                // UI will react to displayUpdates flow
            }
            "DISPLAY_RESYNC_REQUIRED" -> {
                Log.i(TAG, "🔄 Display resync requested")
                // Trigger a full display refresh
                _displayUpdates.emit(DisplayUpdate("FORCE_REFRESH", emptyMap()))
            }
        }
    }
    
    /**
     * Force reconnection
     */
    fun reconnect() {
        Log.i(TAG, "🔄 Force reconnecting WebSocket")
        currentOutletId?.let { outletId ->
            webSocketClient?.disconnect()
            webSocketClient?.connect(outletId, currentDeviceId)
        }
    }
    
    /**
     * Update connection health for monitoring
     */
    private fun updateConnectionHealth(error: String? = null) {
        _connectionHealth.update { health ->
            health.copy(
                lastHeartbeat = System.currentTimeMillis(),
                reconnectAttempts = if (error != null) health.reconnectAttempts + 1 else 0,
                lastError = error
            )
        }
    }
    
    /**
     * Test audio announcements
     */
    fun testAnnouncement(message: String = "This is a test announcement", language: String = "en", volume: Int = 80) {
        audioManager?.announceTestMessage(message, language, volume)
            ?: Log.w(TAG, "❌ Audio manager not initialized")
    }
    
    /**
     * Test chime only
     */
    fun testChime(volume: Int = 100) {
        audioManager?.playTestChime(volume)
            ?: Log.w(TAG, "❌ Audio manager not initialized")
    }
    
    /**
     * Get audio system status for debugging
     */
    fun getAudioStatus(): Map<String, Any> {
        return audioManager?.getQueueStatus() ?: mapOf("error" to "Audio manager not initialized")
    }
    
    /**
     * Handle token call events from WebSocket
     */
    private suspend fun handleTokenCallEvent(tokenCall: TokenCall) {
        try {
            Log.d(TAG, "📡 Handling token call: ${tokenCall.tokenNumber} → Counter ${tokenCall.counterId}")
            
            // Convert TokenCall to DisplayUpdate for UI
            val displayUpdate = DisplayUpdate(
                type = "TOKEN_CALLED",
                data = mapOf(
                    "tokenNumber" to tokenCall.tokenNumber,
                    "counterId" to tokenCall.counterId,
                    "counterName" to tokenCall.counterName,
                    "customerName" to tokenCall.customerName,
                    "language" to tokenCall.language,
                    "timestamp" to tokenCall.timestamp
                )
            )
            
            // Emit the display update
            _displayUpdates.emit(displayUpdate)
            
            Log.d(TAG, "✅ Token call event processed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to handle token call event", e)
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        Log.i(TAG, "🧹 Cleaning up RealTimeDisplayManager")
        
        // Stop heartbeat
        deviceManager?.stopHeartbeat()
        
        // Disconnect WebSocket
        webSocketClient?.disconnect()
        
        // Cleanup audio
        audioManager?.cleanup()
        
        // Cancel coroutines
        scope.cancel()
        
        // Reset state
        isInitialized = false
        currentOutletId = null
        currentDeviceId = null
        
        Log.i(TAG, "✅ RealTimeDisplayManager cleanup complete")
    }
    
    /**
     * Get current status for debugging/monitoring
     */
    fun getStatus(): Map<String, Any> {
        return mapOf(
            "initialized" to isInitialized,
            "outletId" to (currentOutletId ?: "none"),
            "deviceId" to (currentDeviceId ?: "none"),
            "connectionHealth" to connectionHealth.value,
            "audioStatus" to getAudioStatus(),
            "baseUrl" to baseUrl
        )
    }
    
    /**
     * Send heartbeat manually (for testing)
     */
    fun sendHeartbeat() {
        currentDeviceId?.let { deviceId ->
            currentOutletId?.let { outletId ->
                scope.launch {
                    try {
                        // Use the private sendHeartbeat method through a helper
                        val success = deviceManager?.let { dm ->
                            // The device manager will handle heartbeat internally
                            dm.testConnectivity() // Use connectivity test as a proxy
                        }
                        Log.d(TAG, "💓 Manual heartbeat result: $success")
                    } catch (e: Exception) {
                        Log.e(TAG, "💥 Manual heartbeat failed", e)
                    }
                }
            }
        }
    }
}