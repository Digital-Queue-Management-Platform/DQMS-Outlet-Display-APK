package com.dqmp.app.display.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.dqmp.app.display.model.DisplaySettings
import com.dqmp.app.display.model.ConfigurationData
import com.dqmp.app.display.repository.SettingsRepository
import com.dqmp.app.display.network.ConfigurationService
import android.util.Log

data class ConfigurationUiState(
    val isConfigured: Boolean = false,
    val isConfiguring: Boolean = false,
    val connectionStatus: String = "waiting", // waiting, connecting, error, configured
    val outletId: String = "",
    val outletName: String = "",
    val errorMessage: String = ""
)

class ConfigurationViewModel(application: Application) : AndroidViewModel(application) {
    
    private val settingsRepository = SettingsRepository(application)
    private val configurationService = ConfigurationService()
    
    private val _uiState = MutableStateFlow(ConfigurationUiState())
    val uiState: StateFlow<ConfigurationUiState> = _uiState.asStateFlow()
    
    init {
        // Check if already configured
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { 
                    it.copy(
                        isConfigured = settings.isConfigured,
                        outletId = settings.outletId,
                        outletName = settings.outletName
                    ) 
                }
            }
        }
        
        // Listen for configuration from external source (deep links, etc.)
        observeConfigurationUpdates()
    }
    
    private fun observeConfigurationUpdates() {
        // This would listen for configuration data from teleshop manager
        // Implementation depends on how the configuration is received
        // (deep links, local network discovery, etc.)
    }
    
    fun configure(configurationData: ConfigurationData) {
        viewModelScope.launch {
            try {
                _uiState.update { 
                    it.copy(
                        isConfiguring = true, 
                        connectionStatus = "connecting",
                        errorMessage = ""
                    ) 
                }
                
                // Validate configuration with server
                val isValid = configurationService.validateConfiguration(configurationData)
                
                if (isValid) {
                    // Update settings
                    val updatedSettings = DisplaySettings(
                        outletId = configurationData.outletId,
                        outletName = configurationData.outletName,
                        serverUrl = configurationData.serverUrl,
                        isConfigured = true,
                        configurationId = configurationData.configurationId,
                        theme = configurationData.theme,
                        language = configurationData.language
                    )
                    
                    settingsRepository.updateSettings(updatedSettings)
                    
                    _uiState.update { 
                        it.copy(
                            isConfiguring = false,
                            isConfigured = true,
                            connectionStatus = "configured",
                            outletId = configurationData.outletId,
                            outletName = configurationData.outletName
                        ) 
                    }
                    
                    Log.d("Configuration", "Successfully configured outlet: ${configurationData.outletId}")
                } else {
                    throw Exception("Invalid configuration data")
                }
            } catch (e: Exception) {
                Log.e("Configuration", "Configuration failed", e)
                _uiState.update { 
                    it.copy(
                        isConfiguring = false,
                        connectionStatus = "error",
                        errorMessage = e.message ?: "Configuration failed"
                    ) 
                }
            }
        }
    }
    
    fun resetConfiguration() {
        viewModelScope.launch {
            try {
                // Clear all settings
                val defaultSettings = DisplaySettings(
                    isConfigured = false,
                    configurationId = "",
                    outletId = "",
                    outletName = "",
                    serverUrl = ""
                )
                
                settingsRepository.updateSettings(defaultSettings)
                
                _uiState.update { 
                    ConfigurationUiState() 
                }
                
                Log.d("Configuration", "Configuration reset successfully")
            } catch (e: Exception) {
                Log.e("Configuration", "Failed to reset configuration", e)
                _uiState.update { 
                    it.copy(
                        errorMessage = "Failed to reset configuration"
                    ) 
                }
            }
        }
    }
    
    fun retryConfiguration() {
        _uiState.update { 
            it.copy(
                connectionStatus = "waiting",
                errorMessage = ""
            ) 
        }
    }
    
    // Handle configuration from QR code scan or deep link
    fun handleConfigurationUrl(url: String) {
        viewModelScope.launch {
            try {
                val configData = configurationService.parseConfigurationUrl(url)
                configure(configData)
            } catch (e: Exception) {
                Log.e("Configuration", "Failed to parse configuration URL", e)
                _uiState.update { 
                    it.copy(
                        connectionStatus = "error",
                        errorMessage = "Invalid configuration URL"
                    ) 
                }
            }
        }
    }
}