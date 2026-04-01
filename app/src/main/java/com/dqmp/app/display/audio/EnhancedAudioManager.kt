package com.dqmp.app.display.audio

import android.content.Context
import android.content.res.Resources
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.dqmp.app.display.R
import com.dqmp.app.display.data.Token
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.LinkedHashSet

/**
 * Enhanced Audio Manager that matches the web dashboard announcement system exactly.
 * Supports multi-language TTS with fallback mechanisms and audio management.
 */
class EnhancedAudioManager private constructor(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner?,
    private var baseUrl: String
) {
    
    companion object {
        private const val TAG = "EnhancedAudioManager"
        private const val CHIME_VOLUME = 1.0f
        private const val TTS_VOLUME_BASE = 1.0f
        private const val ANNOUNCEMENT_TIMEOUT = 15000L // 15 seconds
        private const val DEDUPLICATION_WINDOW = 3000L // 3 seconds
        
        // Volume scaling constants for backend volume values (0-100)
        private const val MIN_VOLUME = 0.1f
        private const val MAX_VOLUME = 1.0f
        private const val DEFAULT_VOLUME = 80 // Backend volume scale 0-100
        
        @Volatile
        private var INSTANCE: EnhancedAudioManager? = null
        
        fun getInstance(context: Context, lifecycleOwner: LifecycleOwner, baseUrl: String): EnhancedAudioManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: EnhancedAudioManager(context.applicationContext, lifecycleOwner, baseUrl).also { INSTANCE = it }
            }
        }
        
        // Simplified constructor for ViewModels (without lifecycle owner)
        fun createInstance(context: Context, baseUrl: String = ""): EnhancedAudioManager {
            return EnhancedAudioManager(context.applicationContext, null, baseUrl)
        }
    }
    
    // Audio Management
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    
    // TTS Engine
    private var textToSpeech: TextToSpeech? = null
    private var ttsInitialized = false
    
    // HTTP Client for TTS API
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    // State Management
    private var isVoiceEnabled = true
    private var isSpeaking = false
    private var audioFocusRequest: AudioFocusRequest? = null
    
    // Announcement Queue and Deduplication
    private val announcementQueue = mutableListOf<AnnouncementData>()
    private val recentAnnouncements = LinkedHashSet<String>()
    private val queueLock = Any()
    
    // Audio Files Cache
    private val audioCache = mutableMapOf<String, File>()
    
    data class AnnouncementData(
        val tokenNumber: Int,
        val counterNumber: Int,
        val eventType: String, // 'TOKEN_CALLED', 'TOKEN_RECALLED', 'TEST_SOUND'
        val firstName: String,
        val lang: String = "en",
        val volume: Int = DEFAULT_VOLUME, // Backend volume scale 0-100
        val customText: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
        val priority: Int = 0 // Higher numbers = higher priority (TEST_SOUND = 100)
    ) {
        // Generate a deduplication key for identical announcements
        fun getDeduplicationKey(): String {
            return when (eventType) {
                "TOKEN_CALLED", "TOKEN_RECALLED" -> "$eventType:$tokenNumber:$counterNumber"
                "TEST_SOUND" -> "$eventType:$timestamp" // Don't deduplicate test sounds by content
                else -> "$eventType:${customText?.hashCode()}:$timestamp"
            }
        }
        
        // Get scaled volume for MediaPlayer (0.0f to 1.0f)
        fun getScaledVolume(): Float {
            val clampedVolume = volume.coerceIn(0, 100)
            return MIN_VOLUME + (clampedVolume / 100f) * (MAX_VOLUME - MIN_VOLUME)
        }
        
        // Check if this announcement should be deduplicated with another
        fun isDuplicateOf(other: AnnouncementData): Boolean {
            if (eventType == "TEST_SOUND") return false // Never deduplicate test sounds
            
            return eventType == other.eventType &&
                   tokenNumber == other.tokenNumber &&
                   counterNumber == other.counterNumber &&
                   (timestamp - other.timestamp) < DEDUPLICATION_WINDOW
        }
    }
    
    // Coroutine scope for async operations
    private val coroutineScope = lifecycleOwner?.lifecycleScope ?: CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    init {
        initializeTextToSpeech()
        setupAudioFocus()
        startAnnouncementProcessor()
    }
    
    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsInitialized = true
                Log.d(TAG, "TextToSpeech initialized successfully")
                
                // Set language preferences
                val supportedLanguages = listOf(
                    Locale("si", "LK"),  // Sinhala
                    Locale("ta", "LK"),  // Tamil  
                    Locale.US            // English
                )
                
                supportedLanguages.forEach { locale ->
                    val result = textToSpeech?.setLanguage(locale)
                    Log.d(TAG, "TTS Language ${locale.language}: ${if (result == TextToSpeech.LANG_AVAILABLE) "Available" else "Not Available"}")
                }
            } else {
                Log.e(TAG, "TextToSpeech initialization failed")
                ttsInitialized = false
            }
        }
        
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                Log.d(TAG, "TTS Utterance started: $utteranceId")
            }
            
            override fun onDone(utteranceId: String) {
                Log.d(TAG, "TTS Utterance completed: $utteranceId")
                isSpeaking = false
                releaseAudioFocus()
                processNextAnnouncement()
            }
            
            override fun onError(utteranceId: String) {
                Log.e(TAG, "TTS Utterance error: $utteranceId")
                isSpeaking = false
                releaseAudioFocus()
                processNextAnnouncement()
            }
        })
    }
    
    private fun setupAudioFocus() {
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        Log.d(TAG, "Audio focus lost - pausing announcements")
                        pauseAnnouncements()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Log.d(TAG, "Audio focus gained - resuming announcements")
                        resumeAnnouncements()
                    }
                }
            }
            .build()
    }
    
    /**
     * Announces a token call with exact patterns matching the web dashboard
     */
    fun announceTokenCall(token: Token, eventType: String = "TOKEN_CALLED", volume: Int = 100) {
        if (!isVoiceEnabled) return
        
        val firstName = token.customer?.name?.split(" ")?.firstOrNull() ?: ""
        val lang = token.customer?.preferredLanguage ?: "en"
        
        val announcement = AnnouncementData(
            tokenNumber = token.tokenNumber,
            counterNumber = token.counterNumber ?: 1,
            eventType = eventType,
            firstName = firstName,
            lang = lang,
            volume = volume
        )
        
        queueAnnouncement(announcement)
    }
    
    /**
     * Queue announcement with enhanced deduplication and priority handling
     */
    private fun queueAnnouncement(announcement: AnnouncementData) {
        synchronized(queueLock) {
            val now = System.currentTimeMillis()
            
            // Clean up old announcements from recent list
            recentAnnouncements.removeAll { recentKey ->
                val timestamp = recentKey.substringAfterLast("_").toLongOrNull()
                timestamp != null && (now - timestamp) > DEDUPLICATION_WINDOW
            }
            
            // Check for duplicates using new method
            val isDuplicate = announcementQueue.any { existing ->
                announcement.isDuplicateOf(existing)
            }
            
            if (isDuplicate && announcement.eventType != "TEST_SOUND") {
                Log.d(TAG, "🔇 Duplicate announcement ignored: ${announcement.eventType} T${announcement.tokenNumber}")
                return
            }
            
            // For TEST_SOUND events, clear queue and add with high priority
            if (announcement.eventType == "TEST_SOUND") {
                if (isSpeaking) {
                    // Stop current TTS if it's not a test sound
                    textToSpeech?.stop()
                }
                // Clear non-test announcements from queue
                announcementQueue.removeAll { it.eventType != "TEST_SOUND" }
                Log.i(TAG, "🔊 Test sound cleared queue, adding with priority")
            }
            
            // Add to queue with priority ordering
            val updatedAnnouncement = announcement.copy(priority = if (announcement.eventType == "TEST_SOUND") 100 else 0)
            announcementQueue.add(updatedAnnouncement)
            
            // Sort by priority (higher priority first)
            announcementQueue.sortByDescending { it.priority }
            
            // Track in recent announcements
            val deduplicationKey = "${announcement.getDeduplicationKey()}_$now"
            recentAnnouncements.add(deduplicationKey)
            
            Log.i(TAG, "📢 Queued: ${announcement.eventType} T${announcement.tokenNumber} C${announcement.counterNumber} Vol:${announcement.volume} Lang:${announcement.lang} Queue:${announcementQueue.size}")
            
            // Start processing if not already speaking
            if (!isSpeaking) {
                processNextAnnouncement()
            }
        }
    }
    
    private fun startAnnouncementProcessor() {
        coroutineScope.launch {
            while (isActive) {
                delay(100) // Check every 100ms
                if (!isSpeaking && announcementQueue.isNotEmpty()) {
                    processNextAnnouncement()
                }
            }
        }
    }
    
    private fun processNextAnnouncement() {
        synchronized(queueLock) {
            if (announcementQueue.isEmpty() || isSpeaking) return
            
            val announcement = announcementQueue.removeAt(0)
            isSpeaking = true
            
            coroutineScope.launch {
                try {
                    // For test sounds that are chime-only, just play the chime
                    if (announcement.eventType == "TEST_SOUND" && announcement.customText.isNullOrBlank()) {
                        Log.i(TAG, "🔊 Playing test chime only")
                        playChime(announcement.getScaledVolume() * 100f)
                    } else {
                        // Play chime first for announcements that have voice
                        playChime(announcement.getScaledVolume() * 100f)
                        delay(500) // Brief pause after chime
                        
                        // Generate and speak announcement
                        speakAnnouncement(announcement)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "💥 Error processing announcement", e)
                    isSpeaking = false
                    processNextAnnouncement()
                }
            }
        }
    }
    
    private suspend fun playChime(volume: Float) = withContext(Dispatchers.IO) {
        try {
            val mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                setDataSource(context, android.net.Uri.parse("android.resource://${context.packageName}/${R.raw.announcement}"))
                setVolume(minOf(volume / 100f, CHIME_VOLUME), minOf(volume / 100f, CHIME_VOLUME))
                prepareAsync()
            }
            
            val job = CompletableDeferred<Unit>()
            
            mediaPlayer.setOnPreparedListener { 
                mediaPlayer.start()
            }
            
            mediaPlayer.setOnCompletionListener {
                mediaPlayer.release()
                job.complete(Unit)
            }
            
            mediaPlayer.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Chime playback error: $what, $extra")
                mediaPlayer.release()
                job.complete(Unit)
                true
            }
            
            withTimeout(5000) { job.await() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play chime", e)
        }
    }
    
    private suspend fun speakAnnouncement(announcement: AnnouncementData) {
        requestAudioFocus()
        
        val text = generateAnnouncementText(announcement)
        Log.d(TAG, "Speaking announcement: $text (${announcement.lang})")
        
        // Try backend TTS first for Sinhala and Tamil
        if (announcement.lang in listOf("si", "ta") && baseUrl.isNotEmpty()) {
            val success = tryBackendTts(text, announcement.lang, announcement.volume)
            if (success) return
        }
        
        // Fallback to device TTS
        fallbackToDeviceTts(text, announcement.lang, announcement.volume)
    }
    
    private fun generateAnnouncementText(announcement: AnnouncementData): String {
        if (announcement.customText != null) {
            return announcement.customText
        }
        
        val resources = context.resources
        return when (announcement.eventType) {
            "TOKEN_CALLED" -> {
                when (announcement.lang) {
                    "si" -> resources.getString(R.string.announcement_token_called, 
                        announcement.firstName, announcement.tokenNumber, announcement.counterNumber)
                    "ta" -> resources.getString(R.string.announcement_token_called,
                        announcement.firstName, announcement.tokenNumber, announcement.counterNumber)  
                    else -> resources.getString(R.string.announcement_token_called,
                        announcement.firstName, announcement.tokenNumber, announcement.counterNumber)
                }
            }
            "TOKEN_RECALLED" -> {
                when (announcement.lang) {
                    "si" -> resources.getString(R.string.announcement_token_recalled,
                        announcement.firstName, announcement.tokenNumber, announcement.counterNumber)
                    "ta" -> resources.getString(R.string.announcement_token_recalled,
                        announcement.firstName, announcement.tokenNumber, announcement.counterNumber)
                    else -> resources.getString(R.string.announcement_token_recalled,
                        announcement.firstName, announcement.tokenNumber, announcement.counterNumber)
                }
            }
            "TEST_SOUND" -> {
                when (announcement.lang) {
                    "si" -> resources.getString(R.string.announcement_test)
                    "ta" -> resources.getString(R.string.announcement_test)
                    else -> resources.getString(R.string.announcement_test)
                }
            }
            "CUSTOM" -> announcement.customText ?: ""
            else -> announcement.customText ?: ""
        }
    }
    
    private suspend fun tryBackendTts(text: String, lang: String, volume: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/api/tts/speak?text=${java.net.URLEncoder.encode(text, "UTF-8")}&lang=$lang&gender=female"
            val request = Request.Builder().url(url).build()
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Backend TTS failed with code: ${response.code}")
                    return@withContext false
                }
                
                val audioData = response.body?.bytes()
                if (audioData == null) {
                    Log.w(TAG, "No audio data received from backend TTS")
                    return@withContext false
                }
                
                // Cache and play audio
                val cacheKey = "$lang:$text"
                val audioFile = File(context.cacheDir, "tts_${cacheKey.hashCode()}.mp3")
                audioFile.writeBytes(audioData)
                audioCache[cacheKey] = audioFile
                
                playAudioFile(audioFile, volume.toFloat())
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Backend TTS request failed", e)
            false
        }
    }
    
    private suspend fun playAudioFile(file: File, volume: Float) = withContext(Dispatchers.Main) {
        try {
            val mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                setDataSource(file.absolutePath)
                setVolume(volume / 100f * TTS_VOLUME_BASE, volume / 100f * TTS_VOLUME_BASE)
                prepareAsync()
            }
            
            val job = CompletableDeferred<Unit>()
            
            mediaPlayer.setOnPreparedListener { 
                mediaPlayer.start()
            }
            
            mediaPlayer.setOnCompletionListener {
                mediaPlayer.release()
                isSpeaking = false
                releaseAudioFocus()
                job.complete(Unit)
            }
            
            mediaPlayer.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Audio playback error: $what, $extra")
                mediaPlayer.release()
                isSpeaking = false
                releaseAudioFocus()
                job.complete(Unit)
                true
            }
            
            withTimeout(ANNOUNCEMENT_TIMEOUT) { job.await() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio file", e)
            isSpeaking = false
            releaseAudioFocus()
        }
    }
    
    private fun fallbackToDeviceTts(text: String, lang: String, volume: Int) {
        if (!ttsInitialized) {
            Log.w(TAG, "TTS not initialized, cannot speak")
            isSpeaking = false
            releaseAudioFocus()
            return
        }
        
        val locale = when (lang) {
            "si" -> Locale("si", "LK")
            "ta" -> Locale("ta", "LK") 
            else -> Locale.US
        }
        
        textToSpeech?.setLanguage(locale)
        
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "announcement_${System.currentTimeMillis()}")
            // Set volume using the scaled volume (0.0 to 1.0)
            val scaledVolume = MIN_VOLUME + (volume.coerceIn(0, 100) / 100f) * (MAX_VOLUME - MIN_VOLUME)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, scaledVolume)
        }
        
        val result = textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, params, params.getString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID))
        
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak failed")
            isSpeaking = false
            releaseAudioFocus()
        }
    }
    
    private fun requestAudioFocus() {
        audioFocusRequest?.let { request ->
            val result = audioManager.requestAudioFocus(request)
            Log.d(TAG, "Audio focus request result: $result")
        }
    }
    
    private fun releaseAudioFocus() {
        audioFocusRequest?.let { request ->
            audioManager.abandonAudioFocusRequest(request)
            Log.d(TAG, "Audio focus released")
        }
    }
    
    fun setVoiceEnabled(enabled: Boolean) {
        isVoiceEnabled = enabled
        Log.d(TAG, "Voice announcements ${if (enabled) "enabled" else "disabled"}")
        
        if (!enabled && isSpeaking) {
            textToSpeech?.stop()
            isSpeaking = false
            releaseAudioFocus()
        }
    }
    
    fun updateBaseUrl(newBaseUrl: String) {
        baseUrl = newBaseUrl
        Log.d(TAG, "Base URL updated: $baseUrl")
    }
    
    private fun pauseAnnouncements() {
        textToSpeech?.stop()
        isSpeaking = false
    }
    
    private fun resumeAnnouncements() {
        if (announcementQueue.isNotEmpty() && !isSpeaking) {
            processNextAnnouncement()
        }
    }
    
    fun cleanup() {
        synchronized(queueLock) {
            announcementQueue.clear()
            recentAnnouncements.clear()
        }
        
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        
        // Clean up cached files
        audioCache.values.forEach { file ->
            try {
                file.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete cached audio file", e)
            }
        }
        audioCache.clear()
        
        httpClient.dispatcher.executorService.shutdown()
        INSTANCE = null
        
        Log.d(TAG, "EnhancedAudioManager cleaned up")
    }
    
    // Additional methods for ViewModel integration
    fun updateSettings(settings: com.dqmp.app.display.model.DisplaySettings) {
        setVoiceEnabled(settings.enableAnnouncements)
        updateBaseUrl(settings.serverUrl)
        // Update language if needed
    }
    
    /**
     * New methods for production WebSocket integration
     */
    
    /**
     * Announce token called with full context from WebSocket
     */
    fun announceTokenCalled(
        tokenNumber: Int,
        counterNumber: Int,
        customerName: String,
        language: String,
        volume: Int = DEFAULT_VOLUME
    ) {
        val announcement = AnnouncementData(
            tokenNumber = tokenNumber,
            counterNumber = counterNumber,
            eventType = "TOKEN_CALLED",
            firstName = customerName,
            lang = language,
            volume = volume,
            priority = 0
        )
        queueAnnouncement(announcement)
    }
    
    /**
     * Announce token recalled with full context
     */
    fun announceTokenRecalled(
        tokenNumber: Int,
        counterNumber: Int,
        customerName: String,
        language: String,
        volume: Int = DEFAULT_VOLUME
    ) {
        val announcement = AnnouncementData(
            tokenNumber = tokenNumber,
            counterNumber = counterNumber,
            eventType = "TOKEN_RECALLED",
            firstName = customerName,
            lang = language,
            volume = volume,
            priority = 0
        )
        queueAnnouncement(announcement)
    }
    
    /**
     * Play test chime only
     */
    fun playTestChime(volume: Int = 100) {
        coroutineScope.launch {
            try {
                val scaledVolume = MIN_VOLUME + (volume.coerceIn(0, 100) / 100f) * (MAX_VOLUME - MIN_VOLUME)
                playChime(scaledVolume * 100f) // Convert back to 0-100 scale for chime method
            } catch (e: Exception) {
                Log.e(TAG, "Error playing test chime", e)
            }
        }
    }
    
    /**
     * Announce test message with specific language and volume
     */
    fun announceTestMessage(message: String, language: String, volume: Int = DEFAULT_VOLUME) {
        val announcement = AnnouncementData(
            tokenNumber = 0,
            counterNumber = 0,
            eventType = "TEST_SOUND",
            firstName = "",
            lang = language,
            volume = volume,
            customText = message,
            priority = 100  // High priority for test sounds
        )
        queueAnnouncement(announcement)
    }
    
    /**
     * Get current queue status for debugging
     */
    fun getQueueStatus(): Map<String, Any> {
        return mapOf(
            "queueSize" to announcementQueue.size,
            "isSpeaking" to isSpeaking,
            "isVoiceEnabled" to isVoiceEnabled,
            "ttsInitialized" to ttsInitialized,
            "recentAnnouncementsCount" to recentAnnouncements.size
        )
    }
    
    // Legacy methods for backward compatibility
    fun announceToken(tokenNumber: String, counterName: String) {
        val announcement = AnnouncementData(
            tokenNumber = tokenNumber.toIntOrNull() ?: 0,
            counterNumber = counterName.replace("[^0-9]".toRegex(), "").toIntOrNull() ?: 1,
            eventType = "TOKEN_CALLED",
            firstName = "", // Empty for now, can be enhanced later
            lang = "en", // Will be updated based on settings
            volume = 80
        )
        queueAnnouncement(announcement)
    }
    
    fun announceCustomMessage(message: String) {
        val announcement = AnnouncementData(
            tokenNumber = 0,
            counterNumber = 0,
            eventType = "CUSTOM",
            firstName = "",
            lang = "en", // Will be updated based on settings
            volume = 80,
            customText = message
        )
        queueAnnouncement(announcement)
    }
    
    fun release() {
        cleanup()
    }
}