package com.dqmp.app.display.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.dqmp.app.display.model.DisplaySettings
import com.dqmp.app.display.model.QueueItem
import com.dqmp.app.display.model.TokenCall
import com.dqmp.app.display.network.WebSocketClient
import com.dqmp.app.display.audio.EnhancedAudioManager
import com.dqmp.app.display.repository.SettingsRepository

data class OutletDisplayUiState(
    val outletName: String = "",
    val queueItems: List<QueueItem> = emptyList(),
    val currentTokenCall: TokenCall? = null,
    val connectionStatus: String = "Disconnected",
    val settings: DisplaySettings = DisplaySettings()
)

class OutletDisplayViewModel(application: Application) : AndroidViewModel(application) {
    
    private val settingsRepository = SettingsRepository(application)
    private val audioManager = EnhancedAudioManager.createInstance(application)
    private lateinit var webSocketClient: WebSocketClient
    
    private val _uiState = MutableStateFlow(OutletDisplayUiState())
    val uiState: StateFlow<OutletDisplayUiState> = _uiState.asStateFlow()
    
    init {
        // Load settings
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
                // Update audio manager settings
                audioManager.updateSettings(settings)
            }
        }
    }
    
    fun connectToOutlet(outletId: String) {
        val settings = _uiState.value.settings
        
        // Initialize WebSocket client
        webSocketClient = WebSocketClient(
            baseUrl = settings.serverUrl,
            audioManager = audioManager
        )
        
        // Observe WebSocket events
        viewModelScope.launch {
            webSocketClient.connectionStatus.collect { status ->
                _uiState.update { 
                    it.copy(connectionStatus = status.name) 
                }
            }
        }
        
        viewModelScope.launch {
            webSocketClient.queueUpdates.collect { queueItems ->
                _uiState.update { 
                    it.copy(queueItems = queueItems) 
                }
            }
        }
        
        viewModelScope.launch {
            webSocketClient.tokenCalls.collect { tokenCall ->
                _uiState.update { 
                    it.copy(currentTokenCall = tokenCall) 
                }
            }
        }
        
        // Connect to WebSocket
        webSocketClient.connect(outletId)
        
        // Load outlet info
        loadOutletInfo(outletId)
    }
    
    private fun loadOutletInfo(outletId: String) {
        // In a real implementation, you would fetch this from your API
        // For now, we'll use a placeholder
        _uiState.update { 
            it.copy(outletName = "Outlet #$outletId") 
        }
    }
    
    fun updateSettings(newSettings: DisplaySettings) {
        viewModelScope.launch {
            settingsRepository.updateSettings(newSettings)
        }
    }
    
    fun testAnnouncement() {
        audioManager.announceToken("A001", "Counter 1")
    }
    
    override fun onCleared() {
        super.onCleared()
        if (::webSocketClient.isInitialized) {
            webSocketClient.disconnect()
        }
        audioManager.release()
    }
}