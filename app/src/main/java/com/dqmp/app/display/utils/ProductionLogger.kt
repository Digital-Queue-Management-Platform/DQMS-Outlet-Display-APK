package com.dqmp.app.display.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Production Logging System for Android TV APK
 * 
 * Provides structured logging with local file storage and future backend integration.
 * Designed for real-world teleshop deployment monitoring and debugging.
 */

@Serializable
data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: String,
    val tag: String,
    val message: String,
    val category: String,
    val deviceId: String? = null,
    val outletId: String? = null,
    val sessionId: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

enum class LogLevel(val value: String) {
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARN("WARN"),
    ERROR("ERROR"),
    CRITICAL("CRITICAL")
}

enum class LogCategory(val value: String) {
    WEBSOCKET("websocket"),
    AUDIO("audio"),
    DISPLAY("display"),
    DEVICE("device"),
    NETWORK("network"),
    LIFECYCLE("lifecycle"),
    ERROR_RECOVERY("error_recovery"),
    PERFORMANCE("performance"),
    SECURITY("security")
}

class ProductionLogger private constructor(private val context: Context) {
    
    private val json = Json { ignoreUnknownKeys = true }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Configuration
    private var deviceId: String? = null
    private var outletId: String? = null
    private var sessionId: String? = null
    
    // File management
    private val logDir = File(context.filesDir, "logs")
    private val currentLogFile: File
        get() = File(logDir, "dqmp_${dateFormat.format(Date())}.log")
    
    companion object {
        private const val TAG = "ProductionLogger"
        private const val MAX_LOG_FILES = 7 // Keep 7 days of logs
        private const val MAX_LOG_FILE_SIZE = 10 * 1024 * 1024 // 10MB per file
        
        @Volatile
        private var INSTANCE: ProductionLogger? = null
        
        fun getInstance(context: Context): ProductionLogger {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProductionLogger(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        // Static logging methods for easy use
        fun d(tag: String, message: String, category: LogCategory = LogCategory.DISPLAY, metadata: Map<String, String> = emptyMap()) {
            INSTANCE?.log(LogLevel.DEBUG, tag, message, category, metadata)
        }
        
        fun i(tag: String, message: String, category: LogCategory = LogCategory.DISPLAY, metadata: Map<String, String> = emptyMap()) {
            INSTANCE?.log(LogLevel.INFO, tag, message, category, metadata)
        }
        
        fun w(tag: String, message: String, category: LogCategory = LogCategory.ERROR_RECOVERY, metadata: Map<String, String> = emptyMap()) {
            INSTANCE?.log(LogLevel.WARN, tag, message, category, metadata)
        }
        
        fun e(tag: String, message: String, throwable: Throwable? = null, category: LogCategory = LogCategory.ERROR_RECOVERY, metadata: Map<String, String> = emptyMap()) {
            val fullMessage = if (throwable != null) "$message: ${throwable.message}" else message
            val fullMetadata = if (throwable != null) {
                metadata + mapOf("exception" to throwable.javaClass.simpleName, "stackTrace" to throwable.stackTraceToString())
            } else metadata
            INSTANCE?.log(LogLevel.ERROR, tag, fullMessage, category, fullMetadata)
        }
        
        fun critical(tag: String, message: String, throwable: Throwable? = null, metadata: Map<String, String> = emptyMap()) {
            val fullMessage = if (throwable != null) "$message: ${throwable.message}" else message
            val fullMetadata = if (throwable != null) {
                metadata + mapOf("exception" to throwable.javaClass.simpleName, "stackTrace" to throwable.stackTraceToString())
            } else metadata
            INSTANCE?.log(LogLevel.CRITICAL, tag, fullMessage, LogCategory.ERROR_RECOVERY, fullMetadata)
        }
    }
    
    init {
        // Ensure log directory exists
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        
        // Generate session ID
        sessionId = UUID.randomUUID().toString().take(8)
        
        // Clean up old log files
        cleanupOldLogs()
        
        Log.i(TAG, "ProductionLogger initialized with session: $sessionId")
    }
    
    /**
     * Configure logger with device and outlet context
     */
    fun configure(deviceId: String?, outletId: String?) {
        this.deviceId = deviceId
        this.outletId = outletId
        
        log(LogLevel.INFO, TAG, "Logger configured", LogCategory.LIFECYCLE, mapOf(
            "deviceId" to (deviceId ?: "none"),
            "outletId" to (outletId ?: "none")
        ))
    }
    
    /**
     * Log an entry
     */
    fun log(level: LogLevel, tag: String, message: String, category: LogCategory, metadata: Map<String, String> = emptyMap()) {
        scope.launch {
            try {
                val logEntry = LogEntry(
                    level = level.value,
                    tag = tag,
                    message = message,
                    category = category.value,
                    deviceId = deviceId,
                    outletId = outletId,
                    sessionId = sessionId,
                    metadata = metadata
                )
                
                // Write to Android Log
                when (level) {
                    LogLevel.DEBUG -> Log.d(tag, "[$category] $message")
                    LogLevel.INFO -> Log.i(tag, "[$category] $message")
                    LogLevel.WARN -> Log.w(tag, "[$category] $message")
                    LogLevel.ERROR -> Log.e(tag, "[$category] $message")
                    LogLevel.CRITICAL -> Log.e(tag, "🚨 CRITICAL [$category] $message")
                }
                
                // Write to file
                writeToFile(logEntry)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log entry", e)
            }
        }
    }
    
    /**
     * Write log entry to file
     */
    private suspend fun writeToFile(logEntry: LogEntry) = withContext(Dispatchers.IO) {
        try {
            // Check if current log file is too large
            if (currentLogFile.exists() && currentLogFile.length() > MAX_LOG_FILE_SIZE) {
                // Start a new log file
                val newFile = File(logDir, "dqmp_${dateFormat.format(Date())}_${System.currentTimeMillis()}.log")
                FileWriter(newFile, true).use { writer ->
                    writer.appendLine(json.encodeToString(logEntry))
                }
            } else {
                FileWriter(currentLogFile, true).use { writer ->
                    writer.appendLine(json.encodeToString(logEntry))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log to file", e)
        }
    }
    
    /**
     * Clean up old log files
     */
    private fun cleanupOldLogs() {
        scope.launch {
            try {
                val logFiles = logDir.listFiles { _, name -> name.startsWith("dqmp_") && name.endsWith(".log") }
                    ?.sortedByDescending { it.lastModified() }
                
                logFiles?.drop(MAX_LOG_FILES)?.forEach { file ->
                    if (file.delete()) {
                        Log.d(TAG, "Deleted old log file: ${file.name}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cleanup old logs", e)
            }
        }
    }
    
    /**
     * Get recent log entries for debugging
     */
    suspend fun getRecentLogs(maxEntries: Int = 100): List<LogEntry> = withContext(Dispatchers.IO) {
        try {
            val entries = mutableListOf<LogEntry>()
            
            currentLogFile.takeIf { it.exists() }?.readLines()?.takeLast(maxEntries)?.forEach { line ->
                try {
                    val entry = json.decodeFromString<LogEntry>(line)
                    entries.add(entry)
                } catch (e: Exception) {
                    // Skip malformed entries
                }
            }
            
            entries
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read recent logs", e)
            emptyList()
        }
    }
    
    /**
     * Export logs for debugging/support
     */
    suspend fun exportLogs(): String? = withContext(Dispatchers.IO) {
        try {
            val allLogs = mutableListOf<String>()
            
            logDir.listFiles { _, name -> name.startsWith("dqmp_") && name.endsWith(".log") }
                ?.sortedBy { it.lastModified() }
                ?.forEach { file ->
                    allLogs.addAll(file.readLines())
                }
            
            if (allLogs.isNotEmpty()) {
                val exportFile = File(context.cacheDir, "dqmp_logs_export_${System.currentTimeMillis()}.log")
                exportFile.writeText(allLogs.joinToString("\n"))
                exportFile.absolutePath
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export logs", e)
            null
        }
    }
    
    /**
     * Log WebSocket events
     */
    fun logWebSocketEvent(event: String, details: Map<String, String> = emptyMap()) {
        log(LogLevel.INFO, "WebSocket", event, LogCategory.WEBSOCKET, details)
    }
    
    /**
     * Log audio events
     */
    fun logAudioEvent(event: String, details: Map<String, String> = emptyMap()) {
        log(LogLevel.INFO, "Audio", event, LogCategory.AUDIO, details)
    }
    
    /**
     * Log display events
     */
    fun logDisplayEvent(event: String, details: Map<String, String> = emptyMap()) {
        log(LogLevel.INFO, "Display", event, LogCategory.DISPLAY, details)
    }
    
    /**
     * Log performance metrics
     */
    fun logPerformance(metric: String, value: String, details: Map<String, String> = emptyMap()) {
        log(LogLevel.INFO, "Performance", "$metric: $value", LogCategory.PERFORMANCE, details)
    }
    
    /**
     * Log error recovery attempts
     */
    fun logErrorRecovery(error: String, action: String, success: Boolean, details: Map<String, String> = emptyMap()) {
        val level = if (success) LogLevel.INFO else LogLevel.WARN
        val message = "Recovery from '$error' -> '$action': ${if (success) "SUCCESS" else "FAILED"}"
        log(level, "ErrorRecovery", message, LogCategory.ERROR_RECOVERY, details)
    }
    
    /**
     * Get log statistics for monitoring
     */
    suspend fun getLogStats(): Map<String, Any> = withContext(Dispatchers.IO) {
        try {
            val recentLogs = getRecentLogs(1000)
            val now = System.currentTimeMillis()
            val oneHourAgo = now - 3600000 // 1 hour
            
            val recentEntries = recentLogs.filter { it.timestamp > oneHourAgo }
            val errorCount = recentEntries.count { it.level == LogLevel.ERROR.value || it.level == LogLevel.CRITICAL.value }
            val warningCount = recentEntries.count { it.level == LogLevel.WARN.value }
            
            mapOf<String, Any>(
                "totalLogFiles" to (logDir.listFiles()?.size ?: 0),
                "currentLogSize" to currentLogFile.length(),
                "recentEntries" to recentEntries.size,
                "recentErrors" to errorCount,
                "recentWarnings" to warningCount,
                "sessionId" to (sessionId ?: "unknown"),
                "lastLogTime" to (recentLogs.lastOrNull()?.timestamp ?: 0L)
            )
        } catch (e: Exception) {
            mapOf<String, Any>("error" to (e.message ?: "Unknown error"))
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        scope.cancel()
        Log.i(TAG, "ProductionLogger cleaned up")
    }
}

/**
 * Extension functions for easy logging
 */
fun Any.logDebug(message: String, category: LogCategory = LogCategory.DISPLAY, metadata: Map<String, String> = emptyMap()) {
    ProductionLogger.d(this::class.simpleName ?: "Unknown", message, category, metadata)
}

fun Any.logInfo(message: String, category: LogCategory = LogCategory.DISPLAY, metadata: Map<String, String> = emptyMap()) {
    ProductionLogger.i(this::class.simpleName ?: "Unknown", message, category, metadata)
}

fun Any.logWarn(message: String, category: LogCategory = LogCategory.ERROR_RECOVERY, metadata: Map<String, String> = emptyMap()) {
    ProductionLogger.w(this::class.simpleName ?: "Unknown", message, category, metadata)
}

fun Any.logError(message: String, throwable: Throwable? = null, category: LogCategory = LogCategory.ERROR_RECOVERY, metadata: Map<String, String> = emptyMap()) {
    ProductionLogger.e(this::class.simpleName ?: "Unknown", message, throwable, category, metadata)
}

fun Any.logCritical(message: String, throwable: Throwable? = null, metadata: Map<String, String> = emptyMap()) {
    ProductionLogger.critical(this::class.simpleName ?: "Unknown", message, throwable, metadata)
}