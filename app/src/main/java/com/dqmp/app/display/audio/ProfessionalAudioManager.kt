package com.dqmp.app.display.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.dqmp.app.display.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.coroutines.resume

/**
 * Professional Audio Announcement Manager
 * 
 * Provides complete voice announcement functionality matching the web dashboard.
 * Handles multi-language TTS, audio focus management, queue announcements,
 * and professional audio controls suitable for retail environments.
 */

data class AnnouncementRequest(
    val id: String = UUID.randomUUID().toString(),
    val type: AnnouncementType,
    val tokenNumber: String,
    val counterNumber: Int?,
    val customerName: String? = null,
    val preferredLanguage: String = "en",
    val priority: AnnouncementPriority = AnnouncementPriority.NORMAL,
    val customText: String? = null,
    val chimeVolume: Float? = null,
    val voiceVolume: Float? = null,
    val timestamp: Long = System.currentTimeMillis()
)

enum class AnnouncementType {
    TOKEN_CALL,           // Now serving announcement
    TOKEN_RECALL,         // Customer missed call - recall
    COUNTER_OPENING,      // Counter now open for service
    COUNTER_CLOSING,      // Counter temporarily closed  
    BREAK_ANNOUNCEMENT,   // Lunch/tea break announcement
    SYSTEM_MESSAGE,       // System maintenance/updates
    CUSTOM_MESSAGE,       // Custom text announcement
    AUDIO_TEST           // Audio system test
}

enum class AnnouncementPriority {
    LOW,       // Background info
    NORMAL,    // Standard token calls
    HIGH,      // Important announcements
    URGENT     // Emergency messages
}

data class AnnouncementSettings(
    val enabled: Boolean = true,
    val volume: Float = 0.8f,
    val ttsLanguage: String = "en",
    val ttsSpeed: Float = 1.0f,
    val playTone: Boolean = true,
    val announceCustomerName: Boolean = true,
    val announceCounterNumber: Boolean = true,
    val announceEstimatedTime: Boolean = false,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStart: String = "12:00",
    val quietHoursEnd: String = "13:00",
    val maxConcurrentAnnouncements: Int = 3
)

class ProfessionalAudioManager(
    private val context: Context,
    private val scope: CoroutineScope
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var mediaPlayer: MediaPlayer? = null
    
    private val announcementQueue = ConcurrentLinkedQueue<AnnouncementRequest>()
    private val _isProcessing = mutableStateOf(false)
    private val _currentAnnouncement = MutableStateFlow<AnnouncementRequest?>(null)
    private val _queueSize = MutableStateFlow(0)
    
    val isProcessing = _isProcessing
    val currentAnnouncement = _currentAnnouncement.asStateFlow()
    val queueSize = _queueSize.asStateFlow()
    
    private var settings = AnnouncementSettings()
    private var isInitialized = false
    private var isTtsReady = false
    private var processingJob: Job? = null
    private var activeBaseUrl: String = "https://sltsecmanage.slt.lk:7443/"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    
    init {
        initializeTTS()
        startQueueProcessor()
    }

    /**
     * Update the base URL for backend TTS requests
     */
    fun setBaseUrl(url: String) {
        activeBaseUrl = if (url.endsWith("/")) url else "$url/"
        Log.d("AUDIO_MGR", "Base URL updated for TTS: $activeBaseUrl")
    }
    
    private fun initializeTTS() {
        tts = TextToSpeech(context, this)
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.let { textToSpeech ->
                // Set default language
                val result = textToSpeech.setLanguage(Locale.ENGLISH)
                
                if (result == TextToSpeech.LANG_MISSING_DATA || 
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w("AUDIO_MGR", "English TTS not supported, using default")
                }

                textToSpeech.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                
                // Configure TTS settings
                textToSpeech.setSpeechRate(settings.ttsSpeed)
                
                // Set utterance progress listener
                textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d("AUDIO_MGR", "TTS started: $utteranceId")
                    }
                    
                    override fun onDone(utteranceId: String?) {
                        Log.d("AUDIO_MGR", "TTS completed: $utteranceId")
                    }
                    
                    override fun onError(utteranceId: String?) {
                        Log.e("AUDIO_MGR", "TTS error: $utteranceId")
                    }
                })
                
                isTtsReady = true
                Log.i("AUDIO_MGR", "TTS engine initialized successfully")
            }
        } else {
            Log.e("AUDIO_MGR", "TTS initialization failed - local voice announcements will be unavailable")
        }
        
        // Always set initialized to true so the queue processor can start
        // This allows chimes and backend TTS to work even if device TTS fails
        isInitialized = true
        Log.i("AUDIO_MGR", "Professional Audio Manager ready (Queue Processing: ENABLED)")
    }
    
    /**
     * Queue announcement for processing
     */
    fun announce(request: AnnouncementRequest) {
        if (!settings.enabled) {
            Log.d("AUDIO_MGR", "Audio announcements disabled")
            return
        }
        
        if (isQuietHours()) {
            Log.d("AUDIO_MGR", "Quiet hours active - skipping announcement")
            return
        }
        
        // Add to queue based on priority
        if (request.priority == AnnouncementPriority.URGENT) {
            // Insert at front for urgent messages
            val tempQueue = mutableListOf<AnnouncementRequest>()
            while (announcementQueue.isNotEmpty()) {
                tempQueue.add(announcementQueue.poll())
            }
            announcementQueue.offer(request)
            tempQueue.forEach { announcementQueue.offer(it) }
        } else {
            announcementQueue.offer(request)
        }
        
        updateQueueSize()
        Log.i("AUDIO_MGR", "Announcement queued: ${request.type} for token ${request.tokenNumber}. Total in queue: ${_queueSize.value}")
    }
    
    /**
     * Standard token call announcement
     */
    fun announceTokenCall(
        tokenNumber: String,
        counterNumber: Int?,
        customerName: String? = null,
        language: String = "en",
        chimeVolume: Float? = null,
        voiceVolume: Float? = null
    ) {
        val request = AnnouncementRequest(
            type = AnnouncementType.TOKEN_CALL,
            tokenNumber = tokenNumber,
            counterNumber = counterNumber,
            customerName = customerName,
            preferredLanguage = language,
            chimeVolume = chimeVolume,
            voiceVolume = voiceVolume
        )
        announce(request)
    }
    
    /**
     * Token recall announcement (customer missed)
     */
    fun announceTokenRecall(
        tokenNumber: String,
        counterNumber: Int?,
        customerName: String? = null,
        language: String = "en",
        chimeVolume: Float? = null,
        voiceVolume: Float? = null
    ) {
        val request = AnnouncementRequest(
            type = AnnouncementType.TOKEN_RECALL,
            tokenNumber = tokenNumber,
            counterNumber = counterNumber,
            customerName = customerName,
            preferredLanguage = language,
            priority = AnnouncementPriority.HIGH,
            chimeVolume = chimeVolume,
            voiceVolume = voiceVolume
        )
        announce(request)
    }
    
    /**
     * System message announcement
     */
    fun announceSystemMessage(
        message: String,
        language: String = "en",
        priority: AnnouncementPriority = AnnouncementPriority.NORMAL
    ) {
        val request = AnnouncementRequest(
            type = AnnouncementType.SYSTEM_MESSAGE,
            tokenNumber = "",
            counterNumber = null,
            customText = message,
            preferredLanguage = language,
            priority = priority
        )
        announce(request)
    }
    
    /**
     * Audio test announcement
     */
    fun testAudio(language: String = "en") {
        val testMessage = when (language.lowercase()) {
            "si", "sinhala" -> "ශ්‍රවණ පරීක්ෂණය. ශබ්ද පද්ධතිය සාමාන්‍ය ලෙස ක්‍රියා කරයි."
            "ta", "tamil" -> "ஆடியோ சோதனை. ஒலி அமைப்பு சரியாக வேலை செய்கிறது."
            else -> "Audio test. The sound system is working correctly."
        }
        
        val request = AnnouncementRequest(
            type = AnnouncementType.AUDIO_TEST,
            tokenNumber = "TEST",
            counterNumber = null,
            customText = testMessage,
            preferredLanguage = language,
            priority = AnnouncementPriority.HIGH
        )
        announce(request)
    }
    
    private fun startQueueProcessor() {
        processingJob = scope.launch {
            while (isActive) {
                if (!_isProcessing.value && announcementQueue.isNotEmpty() && isInitialized) {
                    processNextAnnouncement()
                } else if (!_isProcessing.value && announcementQueue.isEmpty()) {
                    // Log occasionally to show it's alive
                    if (System.currentTimeMillis() % 10000 < 100) {
                        Log.d("AUDIO_MGR", "Queue processor heartbeat - idle")
                    }
                }
                delay(100) // Check queue every 100ms
            }
        }
    }
    
    private suspend fun processNextAnnouncement() {
        val announcement = announcementQueue.poll() ?: return
        
        _isProcessing.value = true
        _currentAnnouncement.value = announcement
        updateQueueSize()
        
        Log.i("AUDIO_MGR", "📢 STARTING SEQUENTIAL ANNOUNCEMENT: ${announcement.type} for token ${announcement.tokenNumber}")
        
        try {
            // 1. Request audio focus
            requestAudioFocus()
            
            // 2. Play tone if enabled, OR if this is specifically an audio test (chime test)
            if ((settings.playTone || announcement.type == AnnouncementType.AUDIO_TEST) && 
                announcement.type != AnnouncementType.SYSTEM_MESSAGE) {
                
                val chimePlayed = withTimeoutOrNull(5000L) {
                    playNotificationTone(announcement.chimeVolume)
                } ?: false
                if (!chimePlayed) Log.w("AUDIO_MGR", "⚠️ Chime timed out or failed")
                delay(600L) // Gap between chime and voice
            }
            
            // 3. Generate and speak announcement text
            val phrase = generateAnnouncementText(announcement)
            val lang = normalizeLanguage(announcement.preferredLanguage)
            
            if (phrase.isNotBlank()) {
                Log.d("AUDIO_MGR", "Speaking phrase: $phrase (Lang: $lang)")
                
                // Try Backend TTS first for better quality
                val backendPlayed = withTimeoutOrNull(15000L) {
                    speakBackend(phrase, lang, announcement.voiceVolume ?: 100f)
                } ?: false
                
                if (!backendPlayed) {
                    Log.w("AUDIO_MGR", "⚠️ Backend TTS failed or timed out, falling back to device TTS")
                    // Fallback to device TTS (asynchronously, but we wait for estimated duration)
                    speakText(phrase, lang, announcement.id)
                    val estimatedDuration = (phrase.length * 120L) + 1500L
                    delay(estimatedDuration)
                }
            }
            
            Log.i("AUDIO_MGR", "✅ ANNOUNCEMENT COMPLETED: Token ${announcement.tokenNumber}")
            
        } catch (e: Exception) {
            Log.e("AUDIO_MGR", "❌ ERROR in sequential processor: ${e.message}", e)
        } finally {
            _isProcessing.value = false
            _currentAnnouncement.value = null
            updateQueueSize()
            releaseAudioFocus()
        }
    }

    private suspend fun playNotificationTone(customVolume: Float? = null): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                
                // Use R.raw.announcement as in MainActivity for consistency
                setDataSource(context, Uri.parse("android.resource://${context.packageName}/${R.raw.announcement}"))
                
                // Convert percentage to 0.0-1.0
                val volumePercent = customVolume ?: (settings.volume * 100f)
                val volume = volumePercent / 100f
                val clampedVolume = volume.coerceIn(0.0f, 1.0f)
                
                Log.d("AUDIO_MGR", "Setting chime volume: $clampedVolume (requested: $volumePercent%)")
                setVolume(clampedVolume, clampedVolume)

                // If volume is > 100%, use LoudnessEnhancer for amplification
                if (volumePercent > 100f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    try {
                        val gainMb = (2000 * kotlin.math.log10(volumePercent / 100.0)).toInt()
                        val enhancer = android.media.audiofx.LoudnessEnhancer(audioSessionId)
                        enhancer.setTargetGain(gainMb)
                        enhancer.enabled = true
                        Log.d("AUDIO_MGR", "Chime amplified with LoudnessEnhancer: ${gainMb}mB")
                    } catch (e: Exception) {
                        Log.w("AUDIO_MGR", "LoudnessEnhancer failed for chime: ${e.message}")
                    }
                }

                setOnPreparedListener { 
                    it.start() 
                    Log.d("AUDIO_MGR", "Chime started")
                }
                
                setOnCompletionListener {
                    it.release()
                    mediaPlayer = null
                    Log.d("AUDIO_MGR", "Chime completed")
                    if (continuation.isActive) continuation.resume(true)
                }
                
                setOnErrorListener { mp, what, extra ->
                    Log.e("AUDIO_MGR", "Chime error: $what, $extra")
                    mp.release()
                    mediaPlayer = null
                    if (continuation.isActive) continuation.resume(false)
                    true
                }
                
                prepareAsync()
            }
            
            continuation.invokeOnCancellation {
                mediaPlayer?.release()
                mediaPlayer = null
            }
        } catch (e: Exception) {
            Log.e("AUDIO_MGR", "Failed to setup chime", e)
            if (continuation.isActive) continuation.resume(false)
        }
    }

    private suspend fun speakBackend(text: String, lang: String, voiceVolume: Float): Boolean = withContext(Dispatchers.IO) {
        try {
            val sanitizedUrl = activeBaseUrl.removeSuffix("/")
            val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
            val ttsBase = if (sanitizedUrl.endsWith("/api")) sanitizedUrl else "$sanitizedUrl/api"
            val ttsUrl = "$ttsBase/tts/speak?text=$encodedText&lang=$lang&gender=female"

            Log.d("AUDIO_MGR", "Fetching backend TTS: $ttsUrl")
            
            val request = Request.Builder().url(ttsUrl).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                
                val audioBytes = response.body?.bytes() ?: return@withContext false
                val tempFile = File.createTempFile("dqmp_audio_", ".mp3", context.cacheDir)
                tempFile.writeBytes(audioBytes)
                
                val played = withContext(Dispatchers.Main) {
                    playAudioFile(tempFile, lang, voiceVolume = voiceVolume)
                }
                
                tempFile.delete()
                return@withContext played
            }
        } catch (e: Exception) {
            Log.e("AUDIO_MGR", "Backend TTS request failed: ${e.message}")
            return@withContext false
        }
    }

    private suspend fun playAudioFile(file: File, lang: String = "en", voiceVolume: Float = 100f): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(file.absolutePath)
                
                // Set base volume to max
                setVolume(1.0f, 1.0f)
                
                // Match web dashboard: 300% max volume + 50% boost for SI/TA
                var volumeMultiplier = voiceVolume / 100f
                if (lang == "si" || lang == "ta") {
                    volumeMultiplier *= 1.5f
                    Log.d("AUDIO_MGR", "Applying extra 50% boost for $lang language")
                }
                
                // If effective volume is > 100%, use LoudnessEnhancer
                if (volumeMultiplier > 1.0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    try {
                        // dB = 20 * log10(multiplier), mB = 100 * dB
                        val gainMb = (2000 * kotlin.math.log10(volumeMultiplier.toDouble())).toInt()
                        val enhancer = android.media.audiofx.LoudnessEnhancer(audioSessionId)
                        enhancer.setTargetGain(gainMb)
                        enhancer.enabled = true
                        Log.d("AUDIO_MGR", "Voice amplified with LoudnessEnhancer: ${gainMb}mB (Multiplier: $volumeMultiplier)")
                    } catch (e: Exception) {
                        Log.w("AUDIO_MGR", "LoudnessEnhancer failed for voice: ${e.message}")
                    }
                }
                
                setOnPreparedListener { 
                    it.start() 
                    Log.d("AUDIO_MGR", "Voice playback started")
                }
                
                setOnCompletionListener {
                    it.release()
                    Log.d("AUDIO_MGR", "Voice playback completed")
                    if (continuation.isActive) continuation.resume(true)
                }
                
                setOnErrorListener { player, what, extra ->
                    Log.e("AUDIO_MGR", "Voice playback error: $what, $extra")
                    player.release()
                    if (continuation.isActive) continuation.resume(false)
                    true
                }
                
                prepareAsync()
            }
            
            continuation.invokeOnCancellation {
                mp.release()
            }
        } catch (e: Exception) {
            Log.e("AUDIO_MGR", "Failed to play audio file", e)
            if (continuation.isActive) continuation.resume(false)
        }
    }

    private fun normalizeLanguage(raw: String?): String {
        return when (raw?.trim()?.lowercase()) {
            "si", "sinhala", "sinhalese" -> "si"
            "ta", "tamil" -> "ta"
            "en", "english" -> "en"
            else -> "en"
        }
    }
    
    private fun generateAnnouncementText(announcement: AnnouncementRequest): String {
        return when (announcement.type) {
            AnnouncementType.TOKEN_CALL -> generateTokenCallText(announcement)
            AnnouncementType.TOKEN_RECALL -> generateTokenRecallText(announcement) 
            AnnouncementType.SYSTEM_MESSAGE -> announcement.customText ?: "System message"
            AnnouncementType.AUDIO_TEST -> announcement.customText ?: "" // Return empty for chime-only test
            AnnouncementType.COUNTER_OPENING -> generateCounterStatusText(announcement, true)
            AnnouncementType.COUNTER_CLOSING -> generateCounterStatusText(announcement, false)
            AnnouncementType.BREAK_ANNOUNCEMENT -> generateBreakAnnouncementText(announcement)
            AnnouncementType.CUSTOM_MESSAGE -> {
                if (!announcement.customText.isNullOrBlank()) {
                    announcement.customText
                } else if (announcement.tokenNumber == "TEST") {
                    // Fallback for voice test button if no manual text provided
                    when (announcement.preferredLanguage.lowercase()) {
                        "si", "sinhala" -> "ශබ්ද විකාශන යන්ත්‍ර පරීක්ෂා කිරීම. එය සාර්ථකව ක්‍රියා කරයි."
                        "ta", "tamil" -> "ஒலிபெருக்கி சோதனை. இது சரியாக வேலை செய்கிறது."
                        else -> "Testing the speakers. It is working fine."
                    }
                } else {
                    ""
                }
            }
        }
    }
    
    private fun generateTokenCallText(announcement: AnnouncementRequest): String {
        val lang = announcement.preferredLanguage.lowercase()
        val tokenNum = announcement.tokenNumber
        val counter = announcement.counterNumber?.toString() ?: "counter"
        val customerName = if (settings.announceCustomerName) announcement.customerName?.split(" ")?.firstOrNull() else null
        
        return when (lang) {
            "si", "sinhala" -> {
                "ටෝකන් අංක $tokenNum, කරුණාකර කවුන්ටර අංක $counter වෙත පැමිණෙන්න."
            }
            "ta", "tamil" -> {
                "அடையாள எண் $tokenNum, தயவுசெய்து கவுண்டர் எண் $counter க்கு செல்லவும்."
            }
            else -> {
                "Token number $tokenNum, please proceed to counter number $counter."
            }
        }
    }
    
    private fun generateTokenRecallText(announcement: AnnouncementRequest): String {
        val lang = announcement.preferredLanguage.lowercase()
        val tokenNum = announcement.tokenNumber
        val counter = announcement.counterNumber?.toString() ?: "counter"
        
        return when (lang) {
            "si", "sinhala" -> "ටෝකන් අංක $tokenNum නැවත කැඳවනු ලැබේ. කරුණාකර වහාම කවුන්ටර අංක $counter වෙත පැමිණෙන්න."
            "ta", "tamil" -> "அடையாள எண் $tokenNum மீண்டும் அழைக்கப்படுகிறது. உடனடியாக கவுண்டர் $counter க்கு வரவும்."
            else -> "Token number $tokenNum is being recalled. Please proceed to counter number $counter immediately."
        }
    }
    
    private fun generateCounterStatusText(announcement: AnnouncementRequest, isOpening: Boolean): String {
        val lang = announcement.preferredLanguage.lowercase()
        val counter = announcement.counterNumber?.toString() ?: "counter"
        
        return when (lang) {
            "si", "sinhala" -> if (isOpening) "කවුන්ටරය $counter දැන් සේවාව සඳහා විවෘතයි." else "කවුන්ටරය $counter තාවකාලිකව වසා ඇත."
            "ta", "tamil" -> if (isOpening) "கவுண்டர் $counter இப்போது சேவைக்கு திறந்துள்ளது." else "கவுண்டர் $counter தற்காலிகமாக மூடப்பட்டுள்ளது."
            else -> if (isOpening) "Counter $counter is now open for service." else "Counter $counter is temporarily closed."
        }
    }
    
    private fun generateBreakAnnouncementText(announcement: AnnouncementRequest): String {
        return announcement.customText ?: "Service will resume shortly. Thank you for your patience."
    }
    
    private fun speakText(text: String, language: String, utteranceId: String) {
        if (!isTtsReady) {
            Log.w("AUDIO_MGR", "TTS not ready, skipping local speech for: $text")
            scope.launch { onAnnouncementComplete() }
            return
        }

        tts?.let { textToSpeech ->
            // Set language for this utterance
            setTTSLanguage(textToSpeech, language)
            
            // Set speech rate and pitch
            textToSpeech.setSpeechRate(settings.ttsSpeed)
            textToSpeech.setPitch(1.0f)

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, settings.volume.coerceIn(0f, 1f))
            }
            
            // Speak the text
            val result = textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            
            if (result == TextToSpeech.ERROR) {
                Log.e("AUDIO_MGR", "TTS speak failed for text: $text")
                scope.launch { onAnnouncementComplete() }
            }
        }
    }
    
    private fun setTTSLanguage(tts: TextToSpeech, language: String) {
        val locale = when (language.lowercase()) {
            "si", "sinhala" -> Locale("si", "LK")
            "ta", "tamil" -> Locale("ta", "LK")  
            else -> Locale.ENGLISH
        }
        
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w("AUDIO_MGR", "Language $language not supported, using English")
            tts.setLanguage(Locale.ENGLISH)
        }
    }
    
    private fun onAnnouncementComplete() {
        _isProcessing.value = false
        _currentAnnouncement.value = null
        updateQueueSize()
        releaseAudioFocus()
    }
    
    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
                
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .build()
                
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }
    
    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Focus will be automatically released
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
    
    private fun updateQueueSize() {
        _queueSize.value = announcementQueue.size
    }
    
    private fun isQuietHours(): Boolean {
        if (!settings.quietHoursEnabled) return false
        
        // Implementation for quiet hours check
        // This would check current time against configured quiet hours
        return false
    }
    
    /**
     * Update announcement settings
     */
    fun updateSettings(newSettings: AnnouncementSettings) {
        settings = newSettings
        
        // Update TTS settings immediately
        tts?.setSpeechRate(settings.ttsSpeed)
        
        Log.i("AUDIO_MGR", "Audio settings updated")
    }
    
    /**
     * Clear all pending announcements
     */
    fun clearQueue() {
        announcementQueue.clear()
        updateQueueSize()
        Log.i("AUDIO_MGR", "Announcement queue cleared")
    }
    
    /**
     * Stop current announcement
     */
    fun stopCurrent() {
        tts?.stop()
        mediaPlayer?.stop()
        onAnnouncementComplete()
    }
    
    /**
     * Cleanup resources
     */
    fun shutdown() {
        processingJob?.cancel()
        tts?.shutdown()
        mediaPlayer?.release()
        clearQueue()
        Log.i("AUDIO_MGR", "Professional Audio Manager shutdown")
    }
}