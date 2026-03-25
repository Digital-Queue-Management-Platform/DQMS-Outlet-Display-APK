package com.dqmp.app.display

import kotlinx.coroutines.delay
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
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dqmp.app.display.data.SettingsRepository
import com.dqmp.app.display.ui.*
import com.dqmp.app.display.ui.theme.DqmpNativeTheme
import com.dqmp.app.display.ui.theme.Emerald500
import com.dqmp.app.display.ui.theme.Slate900
import com.dqmp.app.display.viewmodel.DisplayState
import com.dqmp.app.display.viewmodel.DisplayViewModel

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var backPressCount = 0
    private var lastBackPressTime = 0L

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize TTS
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(java.util.Locale.US)
            }
        }
        
        // --- Immersive Mode & App Shell Hardware setup ---
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // --- Core Application Dependencies ---
        val repository = SettingsRepository(applicationContext)
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return DisplayViewModel(repository) as T
            }
        }

        setContent {
            DqmpNativeTheme {
                val viewModel: DisplayViewModel = viewModel(factory = factory)
                val state by viewModel.state.collectAsState()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (val s = state) {
                        is DisplayState.Initial -> LoadingUI("Starting DQMP...")
                        is DisplayState.Setup -> {
                            // Fetch existing URL snapshot if any
                            SetupScreen(SettingsRepository.DEFAULT_URL) { id, url ->
                                viewModel.saveSettings(id, url)
                            }
                        }
                        is DisplayState.Loading -> LoadingUI("Connecting to SLT Cloud...")
                        is DisplayState.Success -> DisplayScreen(
                            s.data, 
                            s.counters, 
                            s.branchStatus, 
                            s.isStale,
                            s.lastSync
                        )
                        is DisplayState.Error -> ErrorUI(s.message, { viewModel.retry() }, { viewModel.resetApp() })
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
                    viewModel.announcementEvent.collect { (token, counter, name) ->
                        Log.d("DQMP_AUDIO", "MainActivity caught announcement event. Starting Ding/TTS sequence.")
                        // 1. Play Ding
                        try {
                            val mp = MediaPlayer.create(this@MainActivity, R.raw.ding)
                            mp.setOnCompletionListener { it.release() }
                            mp.start()
                        } catch (e: Exception) { Log.e("DQMP_AUDIO", "Ding failed", e) }
                        
                        // 2. Build Phrase
                        val phrase = buildString {
                            if (!name.isNullOrBlank()) append("Now serving $name. ")
                            append("Token $token. ")
                            if (counter != null) append("Please proceed to counter $counter.")
                        }
                        
                        delay(1200) // Brief pause after ding
                        tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, null)
                    }
                }
            }
        }
    }

    @Composable
    private fun LoadingUI(msg: String) {
        Box(modifier = Modifier.fillMaxSize().background(Slate900), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Emerald500, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(24.dp))
                Text(msg, color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
        }
    }

    @Composable
    private fun ErrorUI(msg: String, onRetry: () -> Unit, onReset: () -> Unit) {
        Box(modifier = Modifier.fillMaxSize().background(Slate900), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(48.dp)
            ) {
                Text("CONNECTION LOST", style = MaterialTheme.typography.displayMedium, color = Color.Red)
                Spacer(Modifier.height(16.dp))
                Text(msg, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(48.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Emerald500)) {
                        Text("TRY AGAIN")
                    }
                    OutlinedButton(onClick = onReset, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White)) {
                        Text("RESET APP", color = Color.White)
                    }
                }
                
                Spacer(Modifier.height(32.dp))
                Text("Support Tip: Press BACK 5 times rapidly to clear config.", color = Color.DarkGray, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    /**
     * Secret Admin Mechanism for Field Engineers.
     * Tapping BACK 5 times in 2 seconds resets the display.
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
                Log.w("DQMP_RESET", "Admin Reset sequence detected. Clearing DataStore.")
                // Global restart via ViewModel/Repository triggered by re-rendering 
                // is cleaner than an Intent restart in this architecture.
                val repository = SettingsRepository(applicationContext)
                val factory = object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return DisplayViewModel(repository) as T
                    }
                }
                val vm = ViewModelProvider(this, factory)[DisplayViewModel::class.java]
                vm.resetApp()
                backPressCount = 0
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }
}
