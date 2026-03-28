package com.dqmp.app.display.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dqmp.app.display.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed class DisplayState {
    object Initial : DisplayState()
    object Setup : DisplayState()
    object Loading : DisplayState()
    data class Success(
        val data: DisplayData,
        val counters: List<CounterStatus>,
        val branchStatus: BranchStatusResponse,
        val isStale: Boolean = false,
        val lastSync: Long = System.currentTimeMillis(),
        val clockSkew: Long = 0L,
        val baseUrl: String = ""
    ) : DisplayState()
    data class Error(val message: String, val lastOutletId: String? = null) : DisplayState()
}

data class TokenCallEvent(
    val tokenNumber: String,
    val counterNumber: Int?,
    val customerName: String?,
    val preferredLanguage: String? = "en",
    val eventType: String = "CALL",
    val customText: String? = null
)

class DisplayViewModel(private val repository: SettingsRepository) : ViewModel() {
    private val _state = MutableStateFlow<DisplayState>(DisplayState.Initial)
    val state: StateFlow<DisplayState> = _state
    
    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings

    private var pollingJob: Job? = null
    private var webSocket: WebSocket? = null
    private var apiService: DqmpApiService? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().build()
    
    private var activeBaseUrl: String = SettingsRepository.DEFAULT_URL
    
    private var lastSuccessfulData: Triple<DisplayData, List<CounterStatus>, BranchStatusResponse>? = null
    private var lastSuccessTime: Long = 0
    private var currentSkew: Long = 0L
    
    // For audio announcements
    private val _announcementEvent = MutableSharedFlow<TokenCallEvent>(replay = 0)
    val announcementEvent = _announcementEvent.asSharedFlow()
    private var lastAnnouncedTokenId: String? = null
    private var lastAnnouncedCounter: Int? = null

    init {
        viewModelScope.launch {
            // Re-activate app when settings change
            combine(repository.outletId, repository.baseUrl) { id, url ->
                Pair(id, url)
            }.collect { (id, url) ->
                if (id.isNullOrBlank()) {
                    _state.value = DisplayState.Setup
                } else {
                    initialize(id, url)
                }
            }
        }
    }

    private fun initialize(outletId: String, baseUrl: String) {
        activeBaseUrl = baseUrl
        stopAll()
        setupApi(baseUrl)
        startPolling(outletId)
        startWebSocket(outletId, baseUrl)
    }

    private fun setupApi(baseUrl: String) {
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        apiService = retrofit.create(DqmpApiService::class.java)
    }

    private fun startPolling(outletId: String) {
        pollingJob = viewModelScope.launch {
            while (isActive) {
                fetchData(outletId)
                // Intelligent polling: Poll less frequently if Websocket is active, 
                // but poll immediately on errors.
                delay(if (webSocket != null) 30000 else 10000) 
            }
        }
    }

    private fun startWebSocket(outletId: String, baseUrl: String) {
        webSocket?.close(1000, "Normal reset")
        
        try {
            val uri = Uri.parse(baseUrl)
            val wsScheme = if (uri.scheme == "https") "wss" else "ws"
            val wsUrl = uri.buildUpon().scheme(wsScheme).build().toString()

            val request = Request.Builder().url(wsUrl).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val msg = json.parseToJsonElement(text).jsonObject
                        val type = msg["type"]?.jsonPrimitive?.content
                        val data = msg["data"]?.jsonObject
                        val incomingId = data?.get("outletId")?.jsonPrimitive?.content
                        
                        if (incomingId == null || incomingId == outletId) {
                            if (type in listOf("TOKEN_CALLED", "TOKEN_RECALLED", "RECALL", "CALL", "TEST_SOUND")) {
                                Log.d("DQMP_WS", "Update event received: $type. Fetching...")
                                
                                // Proactive Announcement from WS (Calls and Recalls)
                                if (type == "TOKEN_CALLED" || type == "TOKEN_RECALLED" || type == "TEST_SOUND" || type == "RECALL") {
                                    val tokenNum = data?.get("tokenNumber")?.jsonPrimitive?.content 
                                        ?: data?.get("token_number")?.jsonPrimitive?.content ?: ""
                                    val counterNum = data?.get("counterNumber")?.jsonPrimitive?.content?.toIntOrNull()
                                        ?: data?.get("counter_number")?.jsonPrimitive?.content?.toIntOrNull()
                                    val name = data?.get("customer")?.jsonObject?.get("name")?.jsonPrimitive?.content
                                        ?: data?.get("customer_name")?.jsonPrimitive?.content
                                        ?: data?.get("name")?.jsonPrimitive?.content 
                                    
                                    val langJson = data?.get("preferredLanguages")
                                    val lang = when {
                                        langJson is JsonArray && langJson.isNotEmpty() -> langJson[0].jsonPrimitive.content
                                        langJson is JsonPrimitive -> langJson.content
                                        else -> data?.get("preferred_language")?.jsonPrimitive?.content ?: "en"
                                    }
                                    
                                    val eventType = if (type == "TEST_SOUND") "TEST" 
                                                   else if (type.contains("RECALL") || type == "RECALL") "RECALL" 
                                                   else "CALL"
                                    
                                    Log.d("DQMP_AUDIO", "Emitting $eventType event for token #$tokenNum")
                                    
                                    viewModelScope.launch {
                                        _announcementEvent.emit(
                                            TokenCallEvent(
                                                tokenNumber = tokenNum,
                                                counterNumber = counterNum,
                                                customerName = name,
                                                preferredLanguage = lang,
                                                eventType = eventType,
                                                customText = data?.get("text")?.jsonPrimitive?.content
                                            )
                                        )
                                    }
                                }
                                
                                viewModelScope.launch { fetchData(outletId) }
                            }
                        }
                    } catch (e: Exception) { Log.e("DQMP_WS", "Parse error", e) }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w("DQMP_WS", "WS Failure: ${t.message}. Retry in 10s.")
                    this@DisplayViewModel.webSocket = null
                    viewModelScope.launch { delay(10000); startWebSocket(outletId, baseUrl) }
                }
            })
        } catch (e: Exception) { Log.e("DQMP_WS", "Failed to construct WS URL", e) }
    }

    private suspend fun fetchData(outletId: String) {
        val service = apiService ?: return
        try {
            if (_state.value is DisplayState.Setup || _state.value is DisplayState.Initial) {
                _state.value = DisplayState.Loading
            }

            coroutineScope {
                val dataDef = async { service.getDisplayData(outletId) }
                val countersDef = async { service.getCounterStatus(outletId) }
                val statusDef = async { service.getBranchStatus(outletId) }

                val res1 = dataDef.await()
                val res2 = countersDef.await()
                val res3 = statusDef.await()

                if (res1.isSuccessful && res2.isSuccessful && res3.isSuccessful) {
                    val data = res1.body()!!
                    val counters = res2.body()!!
                    val branch = res3.body()!!

                    // --- Sync Clock Skew ---
                    res1.headers()["Date"]?.let { serverDateStr ->
                        try {
                            val sdf = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
                            val serverDate = sdf.parse(serverDateStr)
                            serverDate?.let {
                                currentSkew = it.time - System.currentTimeMillis()
                                Log.d("DQMP_TIME", "Clock Skew updated: ${currentSkew}ms")
                            }
                        } catch (e: Exception) { Log.e("DQMP_TIME", "Failed to parse server date", e) }
                    }

                    lastSuccessfulData = Triple(data, counters, branch)
                    lastSuccessTime = System.currentTimeMillis()
                    
                    // --- Audio Announcement Logic ---
                    val currentToken = data.inService.firstOrNull()
                    if (currentToken != null && (currentToken.id != lastAnnouncedTokenId || currentToken.counterNumber != lastAnnouncedCounter)) {
                        Log.d("DQMP_AUDIO", "New token call: ${currentToken.tokenNumber}")
                        _announcementEvent.emit(
                            TokenCallEvent(
                                tokenNumber = currentToken.tokenNumber.toString(),
                                counterNumber = currentToken.counterNumber,
                                customerName = currentToken.customer?.name,
                                preferredLanguage = currentToken.customer?.preferredLanguage ?: "en"
                            )
                        )
                        lastAnnouncedTokenId = currentToken.id
                        lastAnnouncedCounter = currentToken.counterNumber
                    }
                    
                    _state.value = DisplayState.Success(data, counters, branch, clockSkew = currentSkew, baseUrl = activeBaseUrl)
                } else {
                    throw Exception("API Error: ${res1.code()} / ${res2.code()} / ${res3.code()}")
                }
            }
        } catch (e: Exception) {
            handleFetchError(e, outletId)
        }
    }

    private fun handleFetchError(e: Exception, outletId: String) {
        Log.w("DQMP_VM", "Sync error: ${e.message}")
        val cache = lastSuccessfulData
        if (cache != null && System.currentTimeMillis() - lastSuccessTime < 60000) {
            // Show stale data for up to 60 seconds of failure
            _state.value = DisplayState.Success(cache.first, cache.second, cache.third, isStale = true, lastSync = lastSuccessTime, clockSkew = currentSkew)
        } else {
            // Transition to full error screen if offline too long or no cache
            _state.value = DisplayState.Error("Sync Failed: ${e.localizedMessage}", outletId)
        }
    }

    fun retry() {
        val s = _state.value
        if (s is DisplayState.Error && s.lastOutletId != null) {
            viewModelScope.launch { fetchData(s.lastOutletId) }
        }
    }

    fun stopAll() {
        pollingJob?.cancel()
        webSocket?.close(1000, "Clean switch")
        webSocket = null
    }

    fun resetApp() {
        viewModelScope.launch {
            stopAll()
            _state.value = DisplayState.Setup
            repository.clearAll()
        }
    }

    fun saveSettings(outletId: String, baseUrl: String) {
        if (outletId == "TEST_AUDIO") {
            viewModelScope.launch { _announcementEvent.emit(TokenCallEvent("101", 5, "Sample Customer", "en")) }
            return
        }
        viewModelScope.launch {
            repository.saveSettings(outletId, baseUrl)
        }
    }

    fun updateDisplaySettings(settings: DisplaySettings) {
        val currentState = _state.value
        if (currentState is DisplayState.Success) {
            val updatedData = currentState.data.copy(displaySettings = settings)
            _state.value = currentState.copy(data = updatedData)
        }
    }

    fun toggleSettings() {
        _showSettings.value = !_showSettings.value
    }

    fun hideSettings() {
        _showSettings.value = false
    }
}
