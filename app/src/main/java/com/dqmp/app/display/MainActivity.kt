package com.dqmp.app.display

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import okhttp3.OkHttpClient
import okhttp3.Request
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.dqmp.app.display.data.SettingsRepository
import com.dqmp.app.display.data.ConfigurationManager
import com.dqmp.app.display.data.DisplaySettings
import com.dqmp.app.display.audio.ProfessionalAudioManager
import com.dqmp.app.display.ui.*
import com.dqmp.app.display.ui.theme.DqmpNativeTheme
import com.dqmp.app.display.ui.theme.Emerald500
import com.dqmp.app.display.ui.theme.Slate900
import com.dqmp.app.display.viewmodel.DisplayState
import com.dqmp.app.display.viewmodel.DisplayViewModel
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var backPressCount = 0
    private var lastBackPressTime = 0L
    private var menuPressCount = 0
    private var lastMenuPressTime = 0L
    private val recentAudioEvents = ConcurrentHashMap<String, Long>()
    private val recentTokenSpeech = ConcurrentHashMap<String, Long>()
    
    // Professional components
    private var displayViewModel: DisplayViewModel? = null
    private var configurationManager: ConfigurationManager? = null
    private var audioManager: ProfessionalAudioManager? = null
    private val ttsHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        audioManager?.shutdown()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check if launched from boot
        val autoStart = intent.getBooleanExtra("auto_start", false)
        val kioskMode = intent.getBooleanExtra("kiosk_mode", false)
        
        if (autoStart) {
            Log.i("DQMP_MAIN", "App launched automatically on boot")
        }
        
        // Initialize professional components
        configurationManager = ConfigurationManager(this)
        
        // Initialize TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(java.util.Locale.US)
                tts?.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                tts?.setSpeechRate(0.95f)
            }
        }
        
        // Initialize Professional Audio Manager
        audioManager = ProfessionalAudioManager(this, lifecycleScope)
        
        // Set media volume to maximum for announcements
        val audioMgr = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val maxVolume = audioMgr.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        audioMgr.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, maxVolume, 0)
        Log.d("DQMP_AUDIO", "📢 Media volume set to maximum: $maxVolume")
        
        // --- Enhanced Immersive Mode & Kiosk Setup ---
        setupKioskMode(kioskMode)
        
        // --- Core Application Dependencies ---
        val repository = SettingsRepository(applicationContext)
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return DisplayViewModel(repository) as T
            }
        }
        
        displayViewModel = ViewModelProvider(this, factory)[DisplayViewModel::class.java]

        setContent {
            DqmpNativeTheme {
                val viewModel: DisplayViewModel = displayViewModel!!
                val state by viewModel.state.collectAsState()
                val showSettings by viewModel.showSettings.collectAsState()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box {
                        when (val s = state) {
                            is DisplayState.Initial -> LoadingUI("Starting DQMP...")
                            is DisplayState.Setup -> {
                                // Check if device is already configured
                                val isConfigured = configurationManager?.isConfigured() ?: false
                                if (isConfigured) {
                                    // Auto-load saved configuration
                                    LaunchedEffect(Unit) {
                                        val config = configurationManager?.getConfiguration()
                                        config?.let { 
                                            viewModel.saveSettings(it.outletId, it.baseUrl)
                                        }
                                    }
                                    LoadingUI("Loading saved configuration...")
                                } else {
                                    // Show QR code setup for new device
                                    QRCodeSetupScreen(
                                        onConfigurationReceived = { outletId, baseUrl ->
                                            viewModel.saveSettings(outletId, baseUrl)
                                        },
                                        onManualSetup = {
                                            // Fall back to manual setup
                                        }
                                    )
                                }
                            }
                            is DisplayState.Loading -> LoadingUI("Connecting to SLT Cloud...")
                            is DisplayState.Success -> EnhancedDisplayScreen(
                                s.data, 
                                s.counters, 
                                s.branchStatus, 
                                s.isStale,
                                s.lastSync,
                                s.clockSkew
                            )
                            is DisplayState.Error -> ErrorUI(s.message, { viewModel.retry() }, { viewModel.resetApp() })
                        }

                        // Settings Overlay
                        if (showSettings) {
                            val successState = state as? DisplayState.Success
                            if (successState != null) {
                                DisplaySettingsScreen(
                                    currentSettings = successState.data.displaySettings ?: DisplaySettings(),
                                    onSettingsChange = { newSettings ->
                                        viewModel.updateDisplaySettings(newSettings)
                                    },
                                    onClose = { viewModel.hideSettings() }
                                )
                            }
                        }
                    }
                }
                
                // --- Reset Trigger Logging ---
                LaunchedEffect(state) {
                    if (state is DisplayState.Setup) {
                        Log.d("DQMP_APP", "Application is in SETUP state - waiting for user input.")
                    }
                }
                
                // --- Audio Announcement Observer ---
                LaunchedEffect(Unit) {
                    viewModel.announcementEvent.collect { event ->
                        Log.d("DQMP_AUDIO", "MainActivity caught announcement event: ${event.eventType}")

                        val normalizedLang = normalizeAnnouncementLanguage(event.preferredLanguage)
                        val dedupeKey = "${event.eventType}|${event.tokenNumber}|${event.counterNumber}|$normalizedLang|${event.customText}".lowercase()
                        val speechKey = "${event.tokenNumber}|${event.counterNumber}|$normalizedLang".lowercase()
                        val nowMs = System.currentTimeMillis()
                        recentAudioEvents.entries.removeIf { nowMs - it.value > 3500L }
                        recentTokenSpeech.entries.removeIf { nowMs - it.value > 8000L }
                        val seenAt = recentAudioEvents[dedupeKey]
                        if (seenAt != null && (nowMs - seenAt) < 3500L) {
                            Log.d("DQMP_AUDIO", "⏭️ Skipping duplicate audio event: $dedupeKey")
                            return@collect
                        }

                        val recentSpeechAt = recentTokenSpeech[speechKey]
                        if (recentSpeechAt != null && (nowMs - recentSpeechAt) < 8000L) {
                            Log.d("DQMP_AUDIO", "⏭️ Skipping duplicate token speech: $speechKey")
                            return@collect
                        }

                        recentAudioEvents[dedupeKey] = nowMs
                        recentTokenSpeech[speechKey] = nowMs
                        
                        // Get current display settings to check if announcements are enabled
                        val currentState = viewModel.state.value
                        val playTone = (currentState as? DisplayState.Success)?.data?.displaySettings?.playTone ?: true
                        val chimeVolumePercent = (event.chimeVolume ?: 100).coerceIn(0, 100)
                        val voiceVolumePercent = (event.voiceVolume ?: 300).coerceIn(0, 300)
                        val shouldPlayChime = playTone || event.eventType == "TEST_CHIME"
                        
                        // 1. Play Chime (if enabled) - EXACTLY like web dashboard
                        if (shouldPlayChime) {
                            try {
                                val played = withTimeoutOrNull(5000L) {
                                    playAnnouncementChime(chimeVolumePercent)
                                } ?: false
                                if (!played) {
                                    Log.w("DQMP_AUDIO", "⚠️ Chime did not complete cleanly before timeout")
                                }
                                delay(200L)
                            } catch (e: Exception) { 
                                Log.e("DQMP_AUDIO", "❌ Announcement chime error: ${e.message}", e) 
                            }
                        } else {
                            Log.d("DQMP_AUDIO", "🔇 Announcement chime disabled by settings")
                        }
                        
                        // 2. Build Phrase based on language and event type - EXACTLY like web dashboard
                        val lang = normalizedLang
                        val isRecall = event.eventType == "RECALL"
                        val num = event.tokenNumber
                        val counter = event.counterNumber ?: "?"
                        
                        val phrase = when (event.eventType) {
                            "TEST_CHIME" -> "" // Just chime, no TTS
                            "TEST_VOICE" -> event.customText ?: when (lang) {
                                "si", "sinhala" -> "ස්පීකර් පරීක්‍ෂණය. මෙය නිසි ලෙස ක්‍රියාත්මක වේ."
                                "ta", "tamil" -> "ஒலிபெருக்கி சோதனை. இது சரியாக வேலை செய்கிறது."
                                else -> "Testing the speakers. It is working fine."
                            }
                            "MANUAL_ANNOUNCEMENT" -> event.customText ?: ""
                            "CONFIG_SUCCESS" -> "" // Just ding, no TTS for configuration success
                            else -> when (lang) {
                                "si", "sinhala" -> if (isRecall) {
                                    "ටෝකන් අංක $num නැවත කැඳවනු ලැබේ. කරුණාකර වහාම කවුන්ටර අංක $counter වෙත පැමිණෙන්න."
                                } else {
                                    "ටෝකන් අංක $num, කරුණාකර කවුන්ටර අංක $counter වෙත පැමිණෙන්න."
                                }
                                "ta", "tamil" -> if (isRecall) {
                                    "அடையாள எண் $num மீண்டும் அழைக்கப்படுகிறது. உடனடியாக கவுண்டர் $counter க்கு வரவும்."
                                } else {
                                    "அடையாள எண் $num, தயவுசெய்து கவுண்டர் எண் $counter க்கு செல்லவும்."
                                }
                                else -> if (isRecall) {
                                    "Token number $num is being recalled. Please proceed to counter number $counter immediately."
                                } else {
                                    "Token number $num, please proceed to counter number $counter."
                                }
                            }
                        }
                        
                        // 3. Play Voice (Stream from Backend to match Web Dashboard)
                        if (phrase.isNotBlank()) {
                            val baseUrl = (currentState as? DisplayState.Success)?.baseUrl 
                                ?: SettingsRepository.DEFAULT_URL
                            
                            Log.d("DQMP_AUDIO", "Announcement Triggered: Type=$isRecall, Language=$lang, Phrase=$phrase")
                            speakBackend(baseUrl, phrase, lang, voiceVolumePercent)
                        } else {
                            Log.d("DQMP_AUDIO", "Skipping TTS for event type: ${event.eventType}")
                        }
                    }
                }
            }
        }
    }

    private suspend fun speakBackend(baseUrl: String, text: String, lang: String, voiceVolumePercent: Int = 300) {
        val sanitizedUrl = baseUrl.removeSuffix("/")
        try {
            val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
            val ttsBase = if (sanitizedUrl.endsWith("/api")) sanitizedUrl else "$sanitizedUrl/api"
            val ttsUrl = "$ttsBase/tts/speak?text=$encodedText&lang=$lang&gender=female"

            // Use fully fetched audio for reliability; streaming can cut long Sinhala/Tamil recalls.
            Log.d("DQMP_AUDIO", "Playing fetched backend TTS from: $ttsUrl")
            playFetchedTts(ttsUrl, voiceVolumePercent, text, lang)
        } catch (e: Exception) {
            Log.e("DQMP_AUDIO", "Failed to setup backend TTS player", e)
            val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
            val ttsBase = if (sanitizedUrl.endsWith("/api")) sanitizedUrl else "$sanitizedUrl/api"
            val ttsUrl = "$ttsBase/tts/speak?text=$encodedText&lang=$lang&gender=female"
            playFetchedTts(ttsUrl, voiceVolumePercent, text, lang)
        }
    }

    private suspend fun playFetchedTts(ttsUrl: String, voiceVolumePercent: Int, text: String, lang: String) {
        var needsFallback = false
        
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val request = Request.Builder().url(ttsUrl).build()
                ttsHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Fetched TTS failed with status ${response.code}")
                    }

                    val audioBytes = response.body?.bytes()
                        ?: throw IllegalStateException("Fetched TTS returned empty body")

                    val tempFile = File.createTempFile("dqmp_tts_", ".mp3", cacheDir)
                    tempFile.writeBytes(audioBytes)

                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            try {
                                val mp = MediaPlayer()
                                mp.setAudioAttributes(
                                    android.media.AudioAttributes.Builder()
                                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                        .build()
                                )
                                mp.setDataSource(tempFile.absolutePath)
                                
                                var completed = false
                                fun finish() {
                                    if (!completed) {
                                        completed = true
                                        try {
                                            mp.release()
                                        } catch (e: Exception) {}
                                        tempFile.delete()
                                        if (continuation.isActive) continuation.resume(Unit)
                                    }
                                }

                                mp.setOnPreparedListener {
                                    val normalized = (voiceVolumePercent.coerceIn(0, 300) / 300f).coerceIn(0f, 1f)
                                    it.setVolume(normalized, normalized)
                                    it.start()
                                    Log.d("DQMP_AUDIO", "Voice announcement started via fetched TTS")
                                }
                                mp.setOnCompletionListener {
                                    Log.d("DQMP_AUDIO", "Voice announcement completed via fetched TTS")
                                    finish()
                                }
                                mp.setOnErrorListener { player, what, extra ->
                                    Log.e("DQMP_AUDIO", "Fetched TTS playback failed (error: $what, $extra). Falling back to device TTS.")
                                    needsFallback = true
                                    finish()
                                    true
                                }
                                
                                continuation.invokeOnCancellation {
                                    finish()
                                }
                                
                                mp.prepareAsync()
                            } catch (playError: Exception) {
                                Log.e("DQMP_AUDIO", "Fetched TTS setup failed", playError)
                                needsFallback = true
                                tempFile.delete()
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                        }
                    }
                }
            } catch (fetchError: Exception) {
                Log.e("DQMP_AUDIO", "Fetched TTS request failed", fetchError)
                needsFallback = true
            }
        }
        
        if (needsFallback) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                fallbackSpeakWait(text, lang)
            }
        }
    }

    private suspend fun fallbackSpeakWait(text: String, lang: String) {
        val ttsInstance = tts ?: return
        
        suspendCancellableCoroutine<Unit> { continuation ->
            var completed = false
            fun finish() {
                if (!completed) {
                    completed = true
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }

            configureFallbackTtsLanguage(lang)
            val utteranceId = java.util.UUID.randomUUID().toString()
            
            ttsInstance.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) finish()
                }
                override fun onError(id: String?) {
                    if (id == utteranceId) finish()
                }
            })
            
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            
            val result = ttsInstance.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result == TextToSpeech.ERROR) {
                finish()
            }
            
            continuation.invokeOnCancellation {
                ttsInstance.stop()
            }
        }
    }

    private fun configureFallbackTtsLanguage(lang: String) {
        val ttsInstance = tts ?: return
        val locale = when (lang.lowercase()) {
            "si", "sinhala" -> java.util.Locale("si", "LK")
            "ta", "tamil" -> java.util.Locale("ta", "LK")
            else -> java.util.Locale.US
        }

        val result = ttsInstance.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w("DQMP_AUDIO", "Fallback TTS language $lang not supported, using US English")
            ttsInstance.setLanguage(java.util.Locale.US)
        }
    }

    private fun normalizeAnnouncementLanguage(raw: String?): String {
        return when (raw?.trim()?.lowercase()) {
            "si", "sinhala", "sinhalese" -> "si"
            "ta", "tamil" -> "ta"
            "en", "english" -> "en"
            else -> "en"
        }
    }

    private suspend fun playAnnouncementChime(chimeVolumePercent: Int): Boolean {
        return suspendCancellableCoroutine { continuation ->
            try {
                val player = MediaPlayer.create(this@MainActivity, R.raw.announcement)
                if (player == null) {
                    Log.e("DQMP_AUDIO", "❌ Failed to create MediaPlayer for announcement chime")
                    continuation.resume(false)
                    return@suspendCancellableCoroutine
                }

                player.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build()
                )

                val chimeVolume = (chimeVolumePercent / 100f).coerceIn(0f, 1f)
                player.setVolume(chimeVolume, chimeVolume)

                var completed = false
                fun finish(ok: Boolean) {
                    if (completed) return
                    completed = true
                    try {
                        player.release()
                    } catch (_: Exception) {
                    }
                    continuation.resume(ok)
                }

                player.setOnCompletionListener {
                    Log.d("DQMP_AUDIO", "✅ Chime playback completed")
                    finish(true)
                }

                player.setOnErrorListener { _, what, extra ->
                    Log.e("DQMP_AUDIO", "❌ Chime playback error: $what, $extra")
                    finish(false)
                    true
                }

                continuation.invokeOnCancellation {
                    try {
                        player.release()
                    } catch (_: Exception) {
                    }
                }

                Log.d("DQMP_AUDIO", "🔊 Playing announcement chime")
                player.start()
            } catch (e: Exception) {
                Log.e("DQMP_AUDIO", "❌ Failed during chime playback setup", e)
                continuation.resume(false)
            }
        }
    }

    @Composable
    private fun LoadingUI(msg: String) {
        Box(modifier = Modifier.fillMaxSize().background(com.dqmp.app.display.ui.theme.Slate50), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Emerald500, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(24.dp))
                Text(msg, color = com.dqmp.app.display.ui.theme.Slate900, style = MaterialTheme.typography.titleLarge)
            }
        }
    }

    @Composable
    private fun ErrorUI(msg: String, onRetry: () -> Unit, onReset: () -> Unit) {
        Box(modifier = Modifier.fillMaxSize().background(com.dqmp.app.display.ui.theme.Slate50), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(48.dp)
            ) {
                Text("CONNECTION LOST", style = MaterialTheme.typography.displayMedium, color = Color.Red)
                Spacer(Modifier.height(16.dp))
                Text(msg, color = com.dqmp.app.display.ui.theme.Slate900, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(48.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Emerald500)) {
                        Text("TRY AGAIN", color = Color.White)
                    }
                    OutlinedButton(onClick = onReset, border = androidx.compose.foundation.BorderStroke(1.dp, com.dqmp.app.display.ui.theme.Slate300)) {
                        Text("RESET APP", color = com.dqmp.app.display.ui.theme.Slate500)
                    }
                }
                
                Spacer(Modifier.height(32.dp))
                Text("Support Tip: Press BACK 5 times rapidly to clear config.", color = com.dqmp.app.display.ui.theme.Slate400, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    private fun setupKioskMode(enableKiosk: Boolean) {
        // Enhanced Immersive Mode & App Shell Hardware setup
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        // Professional display settings
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        
        // Set brightness to maximum for retail visibility
        window.attributes = window.attributes.apply {
            screenBrightness = 1.0f
        }
        
        if (enableKiosk) {
            Log.i("DQMP_KIOSK", "Kiosk mode enabled - device locked to DQMP display")
        }
    }
    
    /**
     * Professional Key Event Handler for TV Remote Navigation
     * - BACK 5 times: Emergency reset (field engineer access)
     * - MENU 3 times: Open display settings (technician access)
     * - No timeouts - runs indefinitely
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val now = System.currentTimeMillis()
            if (now - lastBackPressTime < 500) {
                backPressCount++
            } else {
                backPressCount = 1
            }
            lastBackPressTime = now
            
            if (backPressCount >= 5) {
                Log.w("DQMP_ADMIN", "Emergency admin reset sequence detected")
                
                try {
                    // Clear configuration
                    lifecycleScope.launch {
                        configurationManager?.clearConfiguration()
                    }
                    
                    // Reset ViewModel
                    displayViewModel?.resetApp()
                    
                    // Reset audio
                    audioManager?.clearQueue()
                    
                    Log.i("DQMP_ADMIN", "Complete system reset performed")
                    
                } catch (e: Exception) {
                    Log.e("DQMP_ADMIN", "Reset failed", e)
                }
                
                backPressCount = 0
                return true
            }
        }
        
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_TV_INPUT) {
            val now = System.currentTimeMillis()
            if (now - lastMenuPressTime < 1000) {
                menuPressCount++
            } else {
                menuPressCount = 1
            }
            lastMenuPressTime = now
            
            if (menuPressCount >= 3) {
                Log.i("DQMP_SETTINGS", "Professional settings access sequence detected")
                
                // Toggle settings screen
                displayViewModel?.toggleSettings()
                
                menuPressCount = 0
                return true
            }
        }
        
        return super.onKeyUp(keyCode, event)
    }
}
