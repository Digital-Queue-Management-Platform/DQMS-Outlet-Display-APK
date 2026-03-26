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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
                         is DisplayState.Success -> {
                            val fullUrl = "${s.baseUrl}display/outlet/${s.outletId}?apk=true"
                            var audioUnlockedNatively by remember { mutableStateOf(false) }
                            val focusRequester = remember { FocusRequester() }
                            
                            Box(modifier = Modifier.fillMaxSize()) {
                                WebViewScreen(url = fullUrl, triggerUnlock = audioUnlockedNatively) { error ->
                                    Log.e("DQMP_WEB", "WebView Encountered Error: $error")
                                }

                                // Native Stale Overlay
                                if (s.isStale) {
                                    Surface(
                                        modifier = Modifier.padding(16.dp).align(Alignment.TopCenter),
                                        color = Color.Red.copy(alpha = 0.8f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("RECONNECTING...", color = Color.White, modifier = Modifier.padding(8.dp), fontWeight = FontWeight.Bold)
                                    }
                                }

                                // FOOLPROOF NATIVE AUDIO UNLOCK OVERLAY
                                if (!audioUnlockedNatively) {
                                    Box(
                                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                Icons.Default.VolumeUp,
                                                null,
                                                tint = Emerald500,
                                                modifier = Modifier.size(80.dp)
                                            )
                                            Spacer(Modifier.height(24.dp))
                                            Text(
                                                "AUDIO IS READY",
                                                style = MaterialTheme.typography.headlineLarge,
                                                color = Color.White
                                            )
                                            Text(
                                                "Press the center button on your remote to start.",
                                                color = Color.White.copy(alpha = 0.7f),
                                                modifier = Modifier.padding(vertical = 12.dp)
                                            )
                                            Spacer(Modifier.height(32.dp))
                                            
                                            // The Native Button: 100% focusable by D-pad
                                            Button(
                                                onClick = {
                                                    audioUnlockedNatively = true
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Emerald500),
                                                shape = RoundedCornerShape(16.dp),
                                                modifier = Modifier.height(64.dp).width(240.dp).focusRequester(focusRequester)
                                            ) {
                                                Text("START DISPLAY", fontWeight = FontWeight.Black, fontSize = 20.sp)
                                            }
                                            
                                            LaunchedEffect(Unit) {
                                                focusRequester.requestFocus()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        is DisplayState.Error -> ErrorUI(s.message, { viewModel.retry() }, { viewModel.resetApp() })
                    }
                }
                
                // --- Reset Trigger Logging ---
                LaunchedEffect(state) {
                    if (state is DisplayState.Setup) {
                        Log.d("DQMP_APP", "Application is in SETUP state - waiting for user input.")
                    }
                }
                
                // --- Audio Announcements Handled by Web Dashboard ---
                // We keep the logic commented out so it can be re-enabled if needed later.
                /*
                LaunchedEffect(Unit) {
                    viewModel.announcementEvent.collect { (token, counter, name) ->
                        // (Internal native TTS logic...)
                    }
                }
                */
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
