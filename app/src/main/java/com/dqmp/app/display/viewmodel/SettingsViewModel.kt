package com.dqmp.app.display.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.dqmp.app.display.model.DisplaySettings
import com.dqmp.app.display.repository.SettingsRepository
import com.dqmp.app.display.audio.EnhancedAudioManager

data class SettingsUiState(
    val settings: DisplaySettings = DisplaySettings(),
    val isLoading: Boolean = false
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    
    private val settingsRepository = SettingsRepository(application)
    private val audioManager = EnhancedAudioManager.createInstance(application)
    
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    
    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { 
                    it.copy(settings = settings, isLoading = false) 
                }
                // Update audio manager with new settings
                audioManager.updateSettings(settings)
            }
        }
    }
    
    fun updateSettings(newSettings: DisplaySettings) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            settingsRepository.updateSettings(newSettings)
        }
    }
    
    fun testAnnouncement() {
        audioManager.announceToken("A001", "Counter 1")
    }
    
    override fun onCleared() {
        super.onCleared()
        audioManager.release()
    }
}