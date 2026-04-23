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
    private var audioManager: com.dqmp.app.display.audio.ProfessionalAudioManager? = null

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
                
                LaunchedEffect(Unit) {
                    viewModel.announcementEvent.collect { event ->
                        Log.i("DQMP_MAIN", "🔔 Received audio event: ${event.eventType} for token: ${event.tokenNumber}")
                        Log.d("DQMP_AUDIO", "MainActivity routing event to ProfessionalAudioManager: ${event.eventType}")
                        
                        // Update audio manager with current base URL
                        (state as? DisplayState.Success)?.baseUrl?.let { url ->
                            audioManager?.setBaseUrl(url)
                        }

                        when (event.eventType) {
                            "RECALL" -> {
                                audioManager?.announceTokenRecall(
                                    tokenNumber = event.tokenNumber,
                                    counterNumber = event.counterNumber,
                                    customerName = event.customerName,
                                    language = event.preferredLanguage ?: "en",
                                    chimeVolume = event.chimeVolume?.toFloat(),
                                    voiceVolume = event.voiceVolume?.toFloat()
                                )
                            }
                            "TEST_CHIME" -> {
                                audioManager?.announce(
                                    com.dqmp.app.display.audio.AnnouncementRequest(
                                        type = com.dqmp.app.display.audio.AnnouncementType.AUDIO_TEST,
                                        tokenNumber = "TEST",
                                        counterNumber = null,
                                        preferredLanguage = event.preferredLanguage ?: "en",
                                        priority = com.dqmp.app.display.audio.AnnouncementPriority.HIGH,
                                        chimeVolume = event.chimeVolume?.toFloat(),
                                        voiceVolume = event.voiceVolume?.toFloat()
                                    )
                                )
                            }
                            "TEST_VOICE" -> {
                                audioManager?.announce(
                                    com.dqmp.app.display.audio.AnnouncementRequest(
                                        type = com.dqmp.app.display.audio.AnnouncementType.CUSTOM_MESSAGE,
                                        tokenNumber = "TEST",
                                        counterNumber = null,
                                        customText = event.customText,
                                        preferredLanguage = event.preferredLanguage ?: "en",
                                        priority = com.dqmp.app.display.audio.AnnouncementPriority.HIGH,
                                        chimeVolume = event.chimeVolume?.toFloat(),
                                        voiceVolume = event.voiceVolume?.toFloat()
                                    )
                                )
                            }
                            "CONFIG_SUCCESS" -> {
                                audioManager?.announce(
                                    com.dqmp.app.display.audio.AnnouncementRequest(
                                        type = com.dqmp.app.display.audio.AnnouncementType.CUSTOM_MESSAGE,
                                        tokenNumber = "CONFIG",
                                        counterNumber = null,
                                        customText = "Configuration successful",
                                        preferredLanguage = "en",
                                        priority = com.dqmp.app.display.audio.AnnouncementPriority.NORMAL
                                    )
                                )
                            }
                            else -> {
                                // Default TOKEN_CALL
                                audioManager?.announceTokenCall(
                                    tokenNumber = event.tokenNumber,
                                    counterNumber = event.counterNumber,
                                    customerName = event.customerName,
                                    language = event.preferredLanguage ?: "en",
                                    chimeVolume = event.chimeVolume?.toFloat(),
                                    voiceVolume = event.voiceVolume?.toFloat()
                                )
                            }
                        }
                    }
                }
            }
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
