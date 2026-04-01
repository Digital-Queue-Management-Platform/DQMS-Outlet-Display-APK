package com.dqmp.app.display.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import com.dqmp.app.display.model.QueueItem
import com.dqmp.app.display.model.TokenCall
import com.dqmp.app.display.audio.EnhancedAudioManager
import android.util.Log

class WebSocketClient(
    private val baseUrl: String,
    private val audioManager: EnhancedAudioManager
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()
    
    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()
    
    private val _queueUpdates = MutableStateFlow<List<QueueItem>>(emptyList())
    val queueUpdates: StateFlow<List<QueueItem>> = _queueUpdates.asStateFlow()
    
    private val _tokenCalls = MutableStateFlow<TokenCall?>(null)
    val tokenCalls: StateFlow<TokenCall?> = _tokenCalls.asStateFlow()
    
    enum class ConnectionStatus {
        Connected,
        Disconnected,
        Connecting,
        Error
    }
    
    fun connect(outletId: String) {
        if (webSocket != null) {
            disconnect()
        }
        
        _connectionStatus.value = ConnectionStatus.Connecting
        
        val wsUrl = baseUrl.replace("http", "ws") + "/ws/outlet/$outletId"
        val request = Request.Builder()
            .url(wsUrl)
            .build()
            
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WebSocket", "Connected to outlet $outletId")
                _connectionStatus.value = ConnectionStatus.Connected
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
            
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleMessage(bytes.utf8())
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WebSocket", "Connection closing: $code - $reason")
                _connectionStatus.value = ConnectionStatus.Disconnected
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("WebSocket", "Connection closed: $code - $reason")
                _connectionStatus.value = ConnectionStatus.Disconnected
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocket", "Connection failed", t)
                _connectionStatus.value = ConnectionStatus.Error
            }
        })
    }
    
    private fun handleMessage(message: String) {
        try {
            val json = JSONObject(message)
            val event = json.getString("event")
            
            when (event) {
                "queue_updated" -> {
                    handleQueueUpdate(json.getJSONObject("data"))
                }
                "token_called" -> {
                    handleTokenCall(json.getJSONObject("data"))
                }
                "token_recalled" -> {
                    handleTokenRecall(json.getJSONObject("data"))
                }
                "test_announcement" -> {
                    handleTestAnnouncement(json.getJSONObject("data"))
                }
            }
        } catch (e: Exception) {
            Log.e("WebSocket", "Error parsing message: $message", e)
        }
    }
    
    private fun handleQueueUpdate(data: JSONObject) {
        // Parse queue items from JSON
        val queueArray = data.getJSONArray("queue")
        val queueItems = mutableListOf<QueueItem>()
        
        for (i in 0 until queueArray.length()) {
            val item = queueArray.getJSONObject(i)
            queueItems.add(
                QueueItem(
                    id = item.getString("id"),
                    tokenNumber = item.getString("tokenNumber"),
                    status = item.getString("status"),
                    counterId = item.optString("counterId", null),
                    counterName = item.optString("counterName", null),
                    createdAt = item.getString("createdAt")
                )
            )
        }
        
        _queueUpdates.value = queueItems
    }
    
    private fun handleTokenCall(data: JSONObject) {
        val tokenCall = TokenCall(
            tokenNumber = data.getString("tokenNumber"),
            counterId = data.getString("counterId"),
            counterName = data.getString("counterName"),
            timestamp = data.getString("timestamp")
        )
        
        _tokenCalls.value = tokenCall
        
        // Trigger announcement
        audioManager.announceToken(
            tokenNumber = tokenCall.tokenNumber,
            counterName = tokenCall.counterName
        )
    }
    
    private fun handleTokenRecall(data: JSONObject) {
        val tokenCall = TokenCall(
            tokenNumber = data.getString("tokenNumber"),
            counterId = data.getString("counterId"),
            counterName = data.getString("counterName"),
            timestamp = data.getString("timestamp")
        )
        
        _tokenCalls.value = tokenCall
        
        // Trigger announcement (recall uses same pattern)
        audioManager.announceToken(
            tokenNumber = tokenCall.tokenNumber,
            counterName = tokenCall.counterName
        )
    }
    
    private fun handleTestAnnouncement(data: JSONObject) {
        val message = data.optString("message", "Test announcement")
        audioManager.announceCustomMessage(message)
    }
    
    fun disconnect() {
        webSocket?.close(1000, "Disconnect")
        webSocket = null
        _connectionStatus.value = ConnectionStatus.Disconnected
    }
    
    fun sendMessage(message: String) {
        webSocket?.send(message)
    }
}