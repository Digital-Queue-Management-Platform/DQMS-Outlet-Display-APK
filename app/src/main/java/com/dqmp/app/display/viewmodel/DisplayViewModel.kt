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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

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
    val customText: String? = null,
    val chimeVolume: Int? = null,
    val voiceVolume: Int? = null
)

class DisplayViewModel(private val repository: SettingsRepository) : ViewModel() {
    private val _state = MutableStateFlow<DisplayState>(DisplayState.Initial)
    val state: StateFlow<DisplayState> = _state
    
    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings
    
    // WebSocket connection status for UI indicator
    private val _isWebSocketConnected = MutableStateFlow(false)
    val isWebSocketConnected: StateFlow<Boolean> = _isWebSocketConnected.asStateFlow()

    // Real-time display manager for production WebSocket integration
    private var realTimeManager: RealTimeDisplayManager? = null

    private var pollingJob: Job? = null
    private var setupPollingJob: Job? = null
    private var webSocket: WebSocket? = null
    private var wsHeartbeatJob: Job? = null
    private var apiService: DqmpApiService? = null
    private val json = Json { ignoreUnknownKeys = true }
    
    // Optimized HTTP client with WebSocket keep-alive for better connection stability
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)  // Faster connection timeout
        .readTimeout(30, TimeUnit.SECONDS)     // Longer read timeout for WS
        .writeTimeout(10, TimeUnit.SECONDS)    // Faster write timeout
        .pingInterval(25, TimeUnit.SECONDS)    // Send ping every 25 seconds to keep WS alive
        .retryOnConnectionFailure(true)        // Auto-retry on connection failure
        .build()
    
    private var activeBaseUrl: String = SettingsRepository.DEFAULT_URL
    
    private var lastSuccessfulData: Triple<DisplayData, List<CounterStatus>, BranchStatusResponse>? = null
    private var lastSuccessTime: Long = 0
    private var currentSkew: Long = 0L
    private var lastDataHash: Int = 0  // For smart caching to avoid unnecessary UI updates
    
    // For audio announcements
    private val _announcementEvent = MutableSharedFlow<TokenCallEvent>(replay = 0)
    val announcementEvent = _announcementEvent.asSharedFlow()
    private var lastAnnouncedEventKey: String? = null
    private var lastLiveAudioAnnouncementKey: String? = null
    private var lastLiveAudioAnnouncementAt: Long = 0L

    // Simplified production-stable approach
    // HTTP polling ONLY (no WebSocket complexity)
    private var audioPollingJob: Job? = null
    private var lastAudioEventCheck = System.currentTimeMillis()
    private var audioEventsEnabled = true
    private var isPollingActive = false
    private var burstModeUntil = 0L // Burst polling for faster response after events
    private val processedAudioEventIds = LinkedHashMap<String, Long>()
    private val processedAudioWindowMs = 60_000L

    init {
        viewModelScope.launch {
            // Re-activate app when settings change
            combine(repository.outletId, repository.baseUrl) { id, url ->
                Pair(id, url)
            }.collect { (id, url) ->
                if (id.isNullOrBlank()) {
                    _state.value = DisplayState.Setup
                    stopAll()
                    setupApi(url)
                    startSetupPolling() // Start polling for configuration while in setup
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
        
        // Immediate data fetch for faster loading
        viewModelScope.launch {
            Log.d("DQMP_PERF", "Starting immediate data fetch for faster loading")
            fetchData(outletId)
        }
        
        startPolling(outletId)
        // PRODUCTION RELIABLE: Use HTTP polling ONLY for audio events (no WebSocket complexity)
        startAudioEventPolling(outletId, baseUrl)
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
                // Check device configuration on every poll for immediate feedback
                if (!checkDeviceConfiguration()) {
                    // Device is no longer configured - return to setup
                    Log.w("DQMP_VM", "Device configuration removed - returning to setup")
                    _state.value = DisplayState.Setup
                    break
                }
                
                fetchData(outletId)

                // Keep the same refresh behavior as the web display dashboard.
                val refreshSeconds = (_state.value as? DisplayState.Success)
                    ?.data
                    ?.displaySettings
                    ?.refresh
                    ?.coerceIn(5, 60)
                    ?: 10
                delay(refreshSeconds * 1000L)
            }
        }
    }

    private suspend fun checkDeviceConfiguration(): Boolean {
        return try {
            val service = apiService ?: return true // Default to true if no service
            val deviceId = repository.deviceId.first()
            
            if (deviceId.isEmpty()) return true // No device ID stored
            
            val response = service.checkDeviceConfig(deviceId)
            if (response.isSuccessful) {
                val configResponse = response.body()
                configResponse?.isConfigured ?: false
            } else {
                Log.w("DQMP_VM", "Config check failed: ${response.code()}")
                true // Default to true on API errors to avoid false positives
            }
        } catch (e: Exception) {
            Log.w("DQMP_VM", "Config check error: ${e.message}")
            true // Default to true on errors
        }
    }

    private fun startSetupPolling() {
        setupPollingJob?.cancel()
        setupPollingJob = viewModelScope.launch {
            while (isActive && _state.value is DisplayState.Setup) {
                try {
                    val deviceId = repository.deviceId.first()
                    if (deviceId.isNotEmpty()) {
                        val service = apiService
                        if (service != null) {
                            val response = service.checkDeviceConfig(deviceId)
                            if (response.isSuccessful) {
                                val configResponse = response.body()
                                if (configResponse?.isConfigured == true) {
                                    // Device has been configured! Save settings and switch to display
                                    val outletId = configResponse.outletId ?: ""
                                    val baseUrl = configResponse.baseUrl ?: activeBaseUrl
                                    
                                    Log.i("DQMP_VM", "Device configured for outlet: ${configResponse.outletName}")
                                    repository.saveSettings(outletId, baseUrl, deviceId)
                                    
                                    // Play configuration success ding sound
                                    viewModelScope.launch {
                                        _announcementEvent.emit(
                                            TokenCallEvent(
                                                tokenNumber = "CONFIG",
                                                counterNumber = null,
                                                customerName = null,
                                                preferredLanguage = "en",
                                                eventType = "CONFIG_SUCCESS",
                                                customText = "Configuration successful"
                                            )
                                        )
                                    }
                                    
                                    // The settings change will trigger the state transition automatically
                                    break
                                } else {
                                    Log.d("DQMP_VM", "Device not yet configured, continuing to poll...")
                                }
                            } else {
                                Log.w("DQMP_VM", "Setup config check failed: ${response.code()}")
                            }
                        }
                    } else {
                        Log.d("DQMP_VM", "No device ID available for setup polling")
                    }
                } catch (e: Exception) {
                    Log.w("DQMP_VM", "Setup polling error: ${e.message}")
                }
                
                delay(2000) // Check every 2 seconds during setup (faster QR response)
            }
        }
    }

    private fun startWebSocket(outletId: String, baseUrl: String) {
        webSocket?.close(1000, "Normal reset")
        wsHeartbeatJob?.cancel()
        
        viewModelScope.launch {
            try {
                val deviceId = repository.deviceId.first()
                val uri = Uri.parse(baseUrl)
                val wsScheme = if (uri.scheme == "https") "wss" else "ws"
                
                // Construct proper WebSocket URL with query parameters for registration
                // Backend expects: ws://server/ws?outletId=xxx&deviceId=yyy
                val wsUrlBuilder = uri.buildUpon()
                    .scheme(wsScheme)
                    .path("/ws")
                    .appendQueryParameter("outletId", outletId)
                
                if (deviceId.isNotEmpty()) {
                    wsUrlBuilder.appendQueryParameter("deviceId", deviceId)
                }
                
                val wsUrl = wsUrlBuilder.build().toString()

                Log.d("DQMP_WS", "Connecting to WebSocket: $wsUrl")
                val request = Request.Builder().url(wsUrl).build()
                webSocket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.d("DQMP_WS", "WebSocket connected successfully")
                        _isWebSocketConnected.value = true
                        
                        // Stop HTTP polling when WebSocket is working
                        stopAudioEventPolling()
                        
                        // Start heartbeat job to send periodic pings
                        wsHeartbeatJob = viewModelScope.launch {
                            while (isActive) {
                                delay(30000) // Send heartbeat every 30 seconds
                                try {
                                    webSocket.send("{\"type\":\"heartbeat\"}")
                                    Log.d("DQMP_WS", "Heartbeat sent")
                                } catch (e: Exception) {
                                    Log.w("DQMP_WS", "Heartbeat failed: ${e.message}")
                                }
                            }
                        }
                    }
                    
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val msg = json.parseToJsonElement(text).jsonObject
                            val type = msg["type"]?.jsonPrimitive?.content
                            val data = msg["data"]?.jsonObject
                            val incomingId = data?.get("outletId")?.jsonPrimitive?.content
                            
                            // Ignore heartbeat acknowledgements
                            if (type == "heartbeat" || type == "pong") {
                                Log.d("DQMP_WS", "Heartbeat acknowledged")
                                return
                            }
                            
                            if (incomingId == null || incomingId == outletId) {
                                // Handle device removal events - immediate logout
                                if (type == "DEVICE_REMOVED") {
                                    val removedDeviceId = data?.get("deviceId")?.jsonPrimitive?.content
                                    Log.w("DQMP_WS", "Device removal event received for deviceId: $removedDeviceId")
                                    
                                    viewModelScope.launch {
                                        val currentDeviceId = repository.deviceId.first()
                                        if (removedDeviceId == currentDeviceId) {
                                            Log.w("DQMP_VM", "This device was removed - immediately returning to setup")
                                            
                                            // Clear ALL device configuration immediately
                                            repository.clearDeviceId()
                                            repository.clearOutletId() 
                                            
                                            // Stop all connections immediately and return to setup
                                            stopAll()
                                            _state.value = DisplayState.Setup
                                            
                                            // Audio feedback for device removal
                                            _announcementEvent.emit(
                                                TokenCallEvent(
                                                    tokenNumber = "Device Removed",
                                                    counterNumber = null,
                                                    customerName = null,
                                                    eventType = "CONFIG_SUCCESS",
                                                    customText = "This device has been removed from the outlet display system"
                                                )
                                            )
                                        }
                                    }
                                    return@onMessage
                                }
                                
                                if (type in listOf("TOKEN_CALLED", "TOKEN_RECALLED", "RECALL", "CALL", "TEST_SOUND")) {
                                    Log.d("DQMP_WS", "Update event received: $type. Fetching...")
                                    
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
                                            type == "TEST_SOUND" -> data?.get("lang")?.jsonPrimitive?.content ?: "en"
                                            langJson is JsonArray && langJson.isNotEmpty() -> langJson[0].jsonPrimitive.content
                                            langJson is JsonPrimitive -> langJson.content
                                            else -> data?.get("preferred_language")?.jsonPrimitive?.content ?: "en"
                                        }
                                        
                                        val eventType = if (type == "TEST_SOUND") {
                                            val testType = data?.get("testType")?.jsonPrimitive?.content ?: "voice"
                                            when (testType) {
                                                "chime" -> "TEST_CHIME"
                                                "voice" -> "TEST_VOICE"
                                                else -> "TEST_VOICE"
                                            }
                                        } else if (type.contains("RECALL") || type == "RECALL") "RECALL" 
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
                                                    customText = data?.get("customText")?.jsonPrimitive?.content
                                                        ?: data?.get("text")?.jsonPrimitive?.content
                                                )
                                            )
                                        }
                                    }
                                    
                                    viewModelScope.launch { fetchData(outletId) }
                                }
                            }
                        } catch (e: Exception) { 
                            Log.e("DQMP_WS", "Parse error", e) 
                        }
                    }
                    
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Log.w("DQMP_WS", "WebSocket closing: $code - $reason")
                        _isWebSocketConnected.value = false
                        wsHeartbeatJob?.cancel()
                    }
                    
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.w("DQMP_WS", "WebSocket closed: $code - $reason")
                        _isWebSocketConnected.value = false
                        wsHeartbeatJob?.cancel()
                        this@DisplayViewModel.webSocket = null
                        
                        // Start HTTP polling as fallback during disconnection
                        if (code != 1000) {
                            startAudioEventPolling(outletId, baseUrl)
                        }
                        
                        // Auto-reconnect after abnormal closure
                        if (code != 1000) {
                            viewModelScope.launch {
                                delay(3000)
                                Log.d("DQMP_WS", "Attempting to reconnect after closure...")
                                startWebSocket(outletId, baseUrl)
                            }
                        }
                    }
                    
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w("DQMP_WS", "WS Failure: ${t.message}. Starting HTTP polling fallback...")
                        _isWebSocketConnected.value = false
                        wsHeartbeatJob?.cancel()
                        this@DisplayViewModel.webSocket = null
                        
                        // Start HTTP polling as fallback
                        startAudioEventPolling(outletId, baseUrl)
                        
                        viewModelScope.launch { 
                            if (!checkDeviceConfiguration()) {
                                Log.w("DQMP_VM", "Device removed - WebSocket failure triggered config check")
                                _state.value = DisplayState.Setup
                                return@launch
                            }
                            delay(3000) 
                            startWebSocket(outletId, baseUrl) 
                        }
                    }
                })
            } catch (e: Exception) { 
                Log.e("DQMP_WS", "Failed to construct WS URL", e) 
            }
        }
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
                    
                    // Smart caching: Only update UI if data actually changed
                    val newDataHash = data.hashCode() + counters.hashCode() + branch.hashCode()
                    val dataChanged = newDataHash != lastDataHash
                    lastDataHash = newDataHash
                    
                    // --- Audio Announcement Logic ---
                    val currentToken = data.inService.firstOrNull()
                    val liveAudioChannelActive = isPollingActive || _isWebSocketConnected.value
                    if (currentToken != null && !liveAudioChannelActive) {
                        val normalizedFallbackLang = normalizeLanguage(currentToken.customer?.preferredLanguage)
                        val fallbackAnnouncementKey = "${currentToken.tokenNumber}|${currentToken.counterNumber}|$normalizedFallbackLang"
                        val nowMs = System.currentTimeMillis()
                        val recentlyAnnouncedByLiveAudio =
                            lastLiveAudioAnnouncementKey == fallbackAnnouncementKey &&
                            (nowMs - lastLiveAudioAnnouncementAt) < 20_000L

                        if (recentlyAnnouncedByLiveAudio) {
                            Log.d("DQMP_AUDIO", "Skipping fallback duplicate; live audio already announced key=$fallbackAnnouncementKey")
                        } else {
                            // Include calledAt marker from recentlyCalled to allow announcing same token again on recall.
                            val callMarker = data.recentlyCalled.firstOrNull { it.id == currentToken.id }?.calledAt ?: ""
                            val eventKey = "${currentToken.id}|${currentToken.counterNumber}|$callMarker"
                            if (eventKey == lastAnnouncedEventKey) {
                                // Same token/counter/call marker seen already, skip duplicate fallback announcement.
                            } else {
                                Log.d("DQMP_AUDIO", "New token call: ${currentToken.tokenNumber}")
                                _announcementEvent.emit(
                                    TokenCallEvent(
                                        tokenNumber = currentToken.tokenNumber.toString(),
                                        counterNumber = currentToken.counterNumber,
                                        customerName = currentToken.customer?.name,
                                        preferredLanguage = normalizedFallbackLang
                                    )
                                )
                                lastAnnouncedEventKey = eventKey
                            }
                        }
                    } else if (currentToken != null) {
                        Log.d("DQMP_AUDIO", "Skipping fetchData fallback announcement because live audio channel is active")
                    }
                    
                    // Only update UI state if data actually changed (performance optimization)
                    if (dataChanged || _state.value !is DisplayState.Success) {
                        _state.value = DisplayState.Success(data, counters, branch, clockSkew = currentSkew, baseUrl = activeBaseUrl)
                        Log.d("DQMP_PERF", "UI updated due to data change")
                    } else {
                        Log.d("DQMP_PERF", "Skipped UI update - data unchanged")
                    }
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
        setupPollingJob?.cancel()
        stopAudioEventPolling() // Stop HTTP polling
        // Removed WebSocket complexity for production stability
        Log.i("DQMP_VM", "🛑 All services stopped (production stable mode)")
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

    /**
     * PRODUCTION RELIABLE: HTTP-only audio event polling
     * Simplified approach for maximum stability in teleshop environment
     */
    private fun startAudioEventPolling(outletId: String, baseUrl: String) {
        audioPollingJob?.cancel()
        isPollingActive = false // Reset state before check to avoid race condition
        
        if (!audioEventsEnabled) {
            Log.d("DQMP_POLL", "Audio polling disabled")
            return
        }
        
        isPollingActive = true
        Log.i("DQMP_POLL", "🎵 PRODUCTION STABLE: Starting HTTP audio polling for outlet: $outletId")
        
        audioPollingJob = viewModelScope.launch {
            try {
                while (isActive && audioEventsEnabled && isPollingActive) {
                    try {
                        // Adaptive polling: 100ms in burst mode, 300ms normally
                        val currentTime = System.currentTimeMillis()
                        val pollInterval = if (currentTime < burstModeUntil) {
                            100L // SUPER FAST during burst mode (after events)
                        } else {
                            300L // Normal fast mode
                        }
                        
                        delay(pollInterval)
                        
                        val service = apiService ?: continue
                        val sinceTime = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                            timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }.format(java.util.Date(lastAudioEventCheck))
                        
                        Log.d("DQMP_POLL", "📡 Polling (${pollInterval}ms) audio events since: $sinceTime")
                        val response = service.getAudioEvents(outletId, sinceTime)
                        
                        if (response.isSuccessful) {
                            val result = response.body()
                            if (result?.success == true && !result.events.isNullOrEmpty()) {
                                Log.i("DQMP_POLL", "🔊 FOUND ${result.events.size} AUDIO EVENTS - PROCESSING NOW!")
                                
                                // Enable burst mode for next 3 seconds after finding events
                                burstModeUntil = System.currentTimeMillis() + 3000
                                Log.i("DQMP_POLL", "⚡ BURST MODE ACTIVATED for 3 seconds - 100ms polling!")
                                
                                for (event in result.events) {
                                    Log.i("DQMP_POLL", "🎯 Processing event: ${event.type} | testType: ${event.testType} | lang: ${event.lang}")
                                    processAudioEventReliable(event)
                                }
                                
                                // Acknowledge processed events
                                val eventIds = result.events.map { it.id }
                                try {
                                    service.acknowledgeAudioEvents(outletId, mapOf("eventIds" to eventIds))
                                    Log.i("DQMP_POLL", "✅ Events acknowledged: ${eventIds.size}")
                                } catch (e: Exception) {
                                    Log.w("DQMP_POLL", "Failed to ack events: ${e.message}")
                                }
                            } else {
                                Log.d("DQMP_POLL", "No new audio events")
                            }
                            
                            // Update last check time
                            lastAudioEventCheck = System.currentTimeMillis()
                        } else {
                            Log.w("DQMP_POLL", "Audio polling failed: ${response.code()} - ${response.message()}")
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e("DQMP_POLL", "Audio polling loop error: ${e.message}", e)
                        delay(3000) // Wait longer on error
                    }
                }
            } catch (e: CancellationException) {
                Log.i("DQMP_POLL", "Audio polling coroutine cancelled")
            } finally {
                Log.i("DQMP_POLL", "🛑 Audio polling loop ended")
                isPollingActive = false
            }
        }
    }
    
    /**
     * PRODUCTION RELIABLE: Process audio event with extensive logging
     */
    private suspend fun processAudioEventReliable(event: AudioEventResponse) {
        try {
            val now = System.currentTimeMillis()
            synchronized(processedAudioEventIds) {
                // Cleanup old dedupe entries
                val iterator = processedAudioEventIds.entries.iterator()
                while (iterator.hasNext()) {
                    if (now - iterator.next().value > processedAudioWindowMs) {
                        iterator.remove()
                    }
                }

                if (processedAudioEventIds.containsKey(event.id)) {
                    Log.w("DQMP_AUDIO", "⏭️ Skipping duplicate audio event id=${event.id}, type=${event.type}")
                    return
                }

                processedAudioEventIds[event.id] = now
            }

            Log.i("DQMP_AUDIO", "🎵 ==========AUDIO EVENT PROCESSING==========")
            Log.i("DQMP_AUDIO", "📝 Event ID: ${event.id}")
            Log.i("DQMP_AUDIO", "📝 Event Type: ${event.type}")
            Log.i("DQMP_AUDIO", "📝 Test Type: ${event.testType}")
            Log.i("DQMP_AUDIO", "📝 Language: ${event.lang}")
            Log.i("DQMP_AUDIO", "📝 Custom Text: ${event.customText}")
            Log.i("DQMP_AUDIO", "📝 Chime Volume: ${event.chimeVolume}")
            Log.i("DQMP_AUDIO", "📝 Voice Volume: ${event.voiceVolume}")
            
            when (event.type) {
                "TEST_SOUND" -> {
                    val normalizedTestType = when ((event.testType ?: "").lowercase()) {
                        "chime" -> "TEST_CHIME"
                        "voice" -> "TEST_VOICE"
                        else -> "TEST_VOICE"
                    }

                    val announcement = TokenCallEvent(
                        tokenNumber = "000",
                        counterNumber = 0,
                        customerName = "",
                        preferredLanguage = event.lang ?: "en",
                        eventType = normalizedTestType,
                        customText = event.customText,
                        chimeVolume = event.chimeVolume,
                        voiceVolume = event.voiceVolume
                    )
                    
                    Log.i("DQMP_AUDIO", "🔊 EMITTING TEST_SOUND EVENT - Type: ${announcement.eventType}")
                    _announcementEvent.emit(announcement)
                    Log.i("DQMP_AUDIO", "✅ TEST_SOUND EVENT EMITTED SUCCESSFULLY!")
                }
                "TOKEN_CALLED" -> {
                    Log.i("DQMP_AUDIO", "📢 TOKEN_CALLED event received - Processing...")
                    
                    // Parse token data from the event
                    val tokenNumber = event.tokenData?.tokenNumber ?: "000"
                    val counterNumber = event.tokenData?.counterNumber ?: 0
                    val customerName = event.tokenData?.customerName ?: ""
                    
                    val normalizedEventType = if ((event.testType ?: "").contains("recall", ignoreCase = true)) {
                        "RECALL"
                    } else {
                        "CALL"
                    }

                    val resolvedLang = resolveTokenAnnouncementLanguage(tokenNumber, event.lang)

                    val announcement = TokenCallEvent(
                        tokenNumber = tokenNumber,
                        counterNumber = counterNumber,
                        customerName = customerName,
                        preferredLanguage = resolvedLang,
                        eventType = normalizedEventType,
                        customText = null,
                        chimeVolume = event.chimeVolume,
                        voiceVolume = event.voiceVolume
                    )

                    val liveKey = "$tokenNumber|$counterNumber|$resolvedLang"
                    lastLiveAudioAnnouncementKey = liveKey
                    lastLiveAudioAnnouncementAt = System.currentTimeMillis()
                    
                    Log.i("DQMP_AUDIO", "🔊 EMITTING TOKEN_CALLED EVENT - Customer: $customerName, Token: $tokenNumber, Counter: $counterNumber")
                    _announcementEvent.emit(announcement)
                    Log.i("DQMP_AUDIO", "✅ TOKEN_CALLED EVENT EMITTED SUCCESSFULLY!")
                }
                else -> {
                    Log.w("DQMP_AUDIO", "❓ Unknown event type: ${event.type}")
                }
            }
            Log.i("DQMP_AUDIO", "🎵 =======END AUDIO EVENT PROCESSING=======")
        } catch (e: Exception) {
            Log.e("DQMP_AUDIO", "❌ CRITICAL: Failed to process audio event: ${e.message}", e)
        }
    }
    
    /**
     * Stop audio polling
     */
    private fun stopAudioEventPolling() {
        audioPollingJob?.cancel()
        audioPollingJob = null
        Log.i("DQMP_POLL", "🛑 Stopped HTTP audio polling")
    }

    private fun normalizeLanguage(raw: String?): String {
        return when (raw?.trim()?.lowercase()) {
            "si", "sinhala", "sinhalese" -> "si"
            "ta", "tamil" -> "ta"
            "en", "english" -> "en"
            else -> "en"
        }
    }

    private fun resolveTokenAnnouncementLanguage(tokenNumber: String, eventLang: String?): String {
        val normalizedEventLang = normalizeLanguage(eventLang)
        if (normalizedEventLang != "en") {
            return normalizedEventLang
        }

        val stateData = (_state.value as? DisplayState.Success)?.data
        val preferred = stateData?.inService
            ?.firstOrNull { it.tokenNumber.toString() == tokenNumber }
            ?.customer?.preferredLanguage
            ?: stateData?.recentlyCalled
                ?.firstOrNull { it.tokenNumber.toString() == tokenNumber }
                ?.customer?.preferredLanguage

        return normalizeLanguage(preferred)
    }
}

@Serializable
data class TokenData(
    @Serializable(with = StringOrNumberSerializer::class)
    val tokenNumber: String = "",
    val counterNumber: Int = 0,
    val customerName: String = ""
)

object StringOrNumberSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StringOrNumber", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonPrimitive) {
                return element.content
            }
            return element.toString()
        }
        return decoder.decodeString()
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

// Data classes for HTTP polling
@Serializable 
data class AudioEventResponse(
    val id: String,
    val outletId: String,
    val type: String,
    val testType: String? = null,
    val lang: String? = null,
    val customText: String? = null,
    val chimeVolume: Int? = null,
    val voiceVolume: Int? = null,
    val timestamp: String,
    val tokenData: TokenData? = null
)

@Serializable
data class AudioEventsResult(
    val success: Boolean,
    val events: List<AudioEventResponse>?,
    val serverTime: String?,
    val count: Int?
)
