package com.dqmp.app.display.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.dqmp.app.display.model.DisplaySettings
import com.dqmp.app.display.repository.SettingsRepository
import com.dqmp.app.display.audio.EnhancedAudioManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class SettingsUiState(
    val settings: DisplaySettings = DisplaySettings(),
    val isLoading: Boolean = false
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    
    private val settingsRepository = SettingsRepository(application)
    private val audioManager = EnhancedAudioManager.createInstance(application)
    private val httpClient = OkHttpClient()
    
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

    fun playChimeOnly() {
        val volume = (_uiState.value.settings.volume * 100).toInt().coerceIn(0, 100)
        audioManager.playTestChime(volume)
    }

    fun playVoiceMessage(text: String, language: String) {
        val message = text.trim()
        if (message.isEmpty()) return

        val volume = (_uiState.value.settings.volume * 100).toInt().coerceIn(0, 100)
        audioManager.announceTestMessage(message, language, volume)
    }

    fun playAllLanguagesSequentially(english: String, sinhala: String, tamil: String) {
        viewModelScope.launch {
            val entries = listOf(
                english.trim() to "en",
                sinhala.trim() to "si",
                tamil.trim() to "ta"
            ).filter { it.first.isNotEmpty() }

            entries.forEachIndexed { index, (text, lang) ->
                playVoiceMessage(text, lang)
                if (index < entries.lastIndex) {
                    delay(200)
                }
            }
        }
    }

    suspend fun translateFromEnglish(text: String, target: String): String? = withContext(Dispatchers.IO) {
        val input = text.trim()
        if (input.isEmpty()) return@withContext null

        try {
            val baseUrl = _uiState.value.settings.serverUrl.trim().trimEnd('/')
            if (baseUrl.isEmpty()) return@withContext null

            val payload = JSONObject()
                .put("text", input)
                .put("target", target)
                .toString()

            val request = Request.Builder()
                .url("$baseUrl/api/utils/translate")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                return@withContext JSONObject(body).optString("translated").takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        audioManager.release()
    }
}