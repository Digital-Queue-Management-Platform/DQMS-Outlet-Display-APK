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
        val lastSync: Long = System.currentTimeMillis()
    ) : DisplayState()
    data class Error(val message: String, val lastOutletId: String? = null) : DisplayState()
}

class DisplayViewModel(private val repository: SettingsRepository) : ViewModel() {
    private val _state = MutableStateFlow<DisplayState>(DisplayState.Initial)
    val state: StateFlow<DisplayState> = _state

    private var pollingJob: Job? = null
    private var webSocket: WebSocket? = null
    private var apiService: DqmpApiService? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().build()
    
    private var lastSuccessfulData: Triple<DisplayData, List<CounterStatus>, BranchStatusResponse>? = null
    private var lastSuccessTime: Long = 0
    
    // For audio announcements (Token, Counter, Name)
    private val _announcementEvent = MutableSharedFlow<Triple<String, Int?, String?>>(replay = 0)
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
                            if (type in listOf("TOKEN_CALLED", "TOKEN_RECALLED", "NEW_TOKEN", "RECALL", "CALL")) {
                                Log.d("DQMP_WS", "Update event received: $type. Fetching...")
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

                val data = dataDef.await()
                val counters = countersDef.await()
                val branch = statusDef.await()

                lastSuccessfulData = Triple(data, counters, branch)
                lastSuccessTime = System.currentTimeMillis()
                
                // --- Audio Announcement Logic ---
                val currentToken = data.inService.firstOrNull()
                if (currentToken != null && (currentToken.id != lastAnnouncedTokenId || currentToken.counterNumber != lastAnnouncedCounter)) {
                    Log.d("DQMP_AUDIO", "New token call: ${currentToken.tokenNumber}")
                    _announcementEvent.emit(Triple(currentToken.tokenNumber.toString(), currentToken.counterNumber, currentToken.customer?.name))
                    lastAnnouncedTokenId = currentToken.id
                    lastAnnouncedCounter = currentToken.counterNumber
                }
                
                _state.value = DisplayState.Success(data, counters, branch)
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
            _state.value = DisplayState.Success(cache.first, cache.second, cache.third, isStale = true, lastSync = lastSuccessTime)
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
            viewModelScope.launch { _announcementEvent.emit(Triple("101", 5, "Sample Customer")) }
            return
        }
        viewModelScope.launch {
            repository.saveSettings(outletId, baseUrl)
        }
    }
}
