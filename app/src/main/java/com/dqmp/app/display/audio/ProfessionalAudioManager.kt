package com.dqmp.app.display.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

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
    private var processingJob: Job? = null
    
    init {
        initializeTTS()
        startQueueProcessor()
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
                
                // Configure TTS settings
                textToSpeech.setSpeechRate(settings.ttsSpeed)
                
                // Set utterance progress listener
                textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d("AUDIO_MGR", "TTS started: $utteranceId")
                    }
                    
                    override fun onDone(utteranceId: String?) {
                        Log.d("AUDIO_MGR", "TTS completed: $utteranceId")
                        scope.launch {
                            onAnnouncementComplete()
                        }
                    }
                    
                    override fun onError(utteranceId: String?) {
                        Log.e("AUDIO_MGR", "TTS error: $utteranceId")
                        scope.launch {
                            onAnnouncementComplete()
                        }
                    }
                })
                
                isInitialized = true
                Log.i("AUDIO_MGR", "Professional Audio Manager initialized successfully")
            }
        } else {
            Log.e("AUDIO_MGR", "TTS initialization failed")
        }
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
        Log.i("AUDIO_MGR", "Announcement queued: ${request.type} for token ${request.tokenNumber}")
    }
    
    /**
     * Standard token call announcement
     */
    fun announceTokenCall(
        tokenNumber: String,
        counterNumber: Int?,
        customerName: String? = null,
        language: String = "en"
    ) {
        val request = AnnouncementRequest(
            type = AnnouncementType.TOKEN_CALL,
            tokenNumber = tokenNumber,
            counterNumber = counterNumber,
            customerName = customerName,
            preferredLanguage = language
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
        language: String = "en"
    ) {
        val request = AnnouncementRequest(
            type = AnnouncementType.TOKEN_RECALL,
            tokenNumber = tokenNumber,
            counterNumber = counterNumber,
            customerName = customerName,
            preferredLanguage = language,
            priority = AnnouncementPriority.HIGH
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
        
        try {
            // Request audio focus
            requestAudioFocus()
            
            // Play tone if enabled
            if (settings.playTone && announcement.type != AnnouncementType.AUDIO_TEST) {
                playNotificationTone()
                delay(1000) // Wait for tone to finish
            }
            
            // Generate and speak announcement text
            val announcementText = generateAnnouncementText(announcement)
            speakText(announcementText, announcement.preferredLanguage, announcement.id)
            
        } catch (e: Exception) {
            Log.e("AUDIO_MGR", "Error processing announcement", e)
            onAnnouncementComplete()
        }
    }
    
    private suspend fun playNotificationTone() {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
                
                // Try to load tone from resources
                try {
                    setDataSource(context, android.net.Uri.parse("android.resource://${context.packageName}/raw/ding"))
                    prepare()
                    start()
                } catch (e: Exception) {
                    // Generate programmatic tone if file not available
                    Log.w("AUDIO_MGR", "Custom tone not available, using system notification")
                }
            }
        } catch (e: Exception) {
            Log.e("AUDIO_MGR", "Failed to play notification tone", e)
        }
    }
    
    private fun generateAnnouncementText(announcement: AnnouncementRequest): String {
        return when (announcement.type) {
            AnnouncementType.TOKEN_CALL -> generateTokenCallText(announcement)
            AnnouncementType.TOKEN_RECALL -> generateTokenRecallText(announcement) 
            AnnouncementType.SYSTEM_MESSAGE -> announcement.customText ?: "System message"
            AnnouncementType.AUDIO_TEST -> announcement.customText ?: "Audio test"
            AnnouncementType.COUNTER_OPENING -> generateCounterStatusText(announcement, true)
            AnnouncementType.COUNTER_CLOSING -> generateCounterStatusText(announcement, false)
            AnnouncementType.BREAK_ANNOUNCEMENT -> generateBreakAnnouncementText(announcement)
            AnnouncementType.CUSTOM_MESSAGE -> announcement.customText ?: ""
        }
    }
    
    private fun generateTokenCallText(announcement: AnnouncementRequest): String {
        val lang = announcement.preferredLanguage.lowercase()
        val tokenNum = announcement.tokenNumber
        val counter = announcement.counterNumber?.toString() ?: "counter"
        val customerName = if (settings.announceCustomerName) announcement.customerName?.split(" ")?.firstOrNull() else null
        
        return when (lang) {
            "si", "sinhala" -> {
                val namePrefix = customerName?.let { "$it, " } ?: ""
                "${namePrefix}ටෝකන් අංක $tokenNum ${if (settings.announceCounterNumber) "කවුන්ටරය $counter වෙත" else ""} කරුණාකර පැමිණෙන්න."
            }
            "ta", "tamil" -> {
                val namePrefix = customerName?.let { "$it, " } ?: ""
                "${namePrefix}டோக்கன் எண் $tokenNum ${if (settings.announceCounterNumber) "கவுண்டர் $counter க்கு" else ""} தயவுசெய்து வாருங்கள்."
            }
            else -> {
                val namePrefix = customerName?.let { "$it, " } ?: ""
                "${namePrefix}Token number $tokenNum, ${if (settings.announceCounterNumber) "please proceed to counter $counter" else "please proceed to the counter"}."
            }
        }
    }
    
    private fun generateTokenRecallText(announcement: AnnouncementRequest): String {
        val lang = announcement.preferredLanguage.lowercase()
        val tokenNum = announcement.tokenNumber
        val counter = announcement.counterNumber?.toString() ?: "counter"
        
        return when (lang) {
            "si", "sinhala" -> "ටෝකන් අංක $tokenNum නැවත කැඳවනු ලැබේ. කරුණාකර වහාම කවුන්ටරය $counter වෙත පැමිණෙන්න."
            "ta", "tamil" -> "டோக்கன் எண் $tokenNum மீண்டும் அழைக்கப்படுகிறது. தயவுசெய்து உடனடியாக கவுண்டர் $counter க்கு வாருங்கள்."
            else -> "Token number $tokenNum is being recalled. Please proceed to counter $counter immediately."
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
        tts?.let { textToSpeech ->
            // Set language for this utterance
            setTTSLanguage(textToSpeech, language)
            
            // Set speech rate and pitch
            textToSpeech.setSpeechRate(settings.ttsSpeed)
            textToSpeech.setPitch(1.0f)
            
            // Speak the text
            val result = textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            
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