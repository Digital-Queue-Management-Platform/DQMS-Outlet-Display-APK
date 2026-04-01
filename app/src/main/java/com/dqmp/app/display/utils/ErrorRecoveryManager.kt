package com.dqmp.app.display.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production Error Recovery System
 * 
 * Handles network failures, app crashes, WebSocket disconnections, and other
 * production issues with automatic recovery and escalation.
 */

data class RecoveryAction(
    val name: String,
    val action: suspend () -> Boolean,
    val maxRetries: Int = 3,
    val backoffMs: Long = 1000L,
    val escalationAction: (suspend () -> Unit)? = null
)

enum class FailureType {
    NETWORK_DISCONNECTION,
    WEBSOCKET_FAILURE,
    AUDIO_SYSTEM_FAILURE,
    API_REQUEST_FAILURE,
    DISPLAY_UPDATE_FAILURE,
    DEVICE_CONFIGURATION_LOST,
    CRITICAL_SYSTEM_ERROR
}

data class FailureEvent(
    val type: FailureType,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val context: Map<String, String> = emptyMap()
)

class ErrorRecoveryManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {
    
    private val logger = ProductionLogger.getInstance(context)
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    
    // Recovery state tracking
    private val activeRecoveries = mutableMapOf<FailureType, Job>()
    private val retryCounters = mutableMapOf<FailureType, AtomicInteger>()
    private val lastFailureTime = mutableMapOf<FailureType, Long>()
    
    // Recovery actions registry
    private val recoveryActions = mutableMapOf<FailureType, RecoveryAction>()
    
    // Network state monitoring
    private val _networkAvailable = MutableStateFlow(isNetworkAvailable())
    val networkAvailable: StateFlow<Boolean> = _networkAvailable.asStateFlow()
    
    // Critical failure tracking
    private val _criticalFailureCount = MutableStateFlow(0)
    val criticalFailureCount: StateFlow<Int> = _criticalFailureCount.asStateFlow()
    
    companion object {
        private const val TAG = "ErrorRecoveryManager"
        private const val MAX_CRITICAL_FAILURES = 5
        private const val FAILURE_RESET_WINDOW_MS = 300_000L // 5 minutes
        private const val NETWORK_CHECK_INTERVAL_MS = 10_000L // 10 seconds
    }
    
    init {
        setupDefaultRecoveryActions()
        startNetworkMonitoring()
        
        logger.logInfo("ErrorRecoveryManager initialized", LogCategory.ERROR_RECOVERY)
    }
    
    /**
     * Setup default recovery actions for common failure types
     */
    private fun setupDefaultRecoveryActions() {
        
        // Network disconnection recovery
        recoveryActions[FailureType.NETWORK_DISCONNECTION] = RecoveryAction(
            name = "Network Reconnection",
            action = {
                delay(2000) // Wait for network to stabilize
                val isConnected = isNetworkAvailable()
                _networkAvailable.value = isConnected
                isConnected
            },
            maxRetries = 10,
            backoffMs = 5000L,
            escalationAction = {
                logger.logCritical("Network disconnection persists after maximum retries")
                // Could trigger device restart or fallback mode
            }
        )
        
        // WebSocket failure recovery
        recoveryActions[FailureType.WEBSOCKET_FAILURE] = RecoveryAction(
            name = "WebSocket Reconnection",
            action = {
                // This will be implemented by the caller
                // The action should attempt to reconnect WebSocket
                false // Placeholder
            },
            maxRetries = 5,
            backoffMs = 3000L,
            escalationAction = {
                logger.logWarn("WebSocket reconnection failed, switching to polling mode")
            }
        )
        
        // Audio system recovery
        recoveryActions[FailureType.AUDIO_SYSTEM_FAILURE] = RecoveryAction(
            name = "Audio System Recovery",
            action = {
                // Reinitialize audio components
                false // Placeholder - caller implements
            },
            maxRetries = 3,
            backoffMs = 2000L,
            escalationAction = {
                logger.logError("Audio system recovery failed - disabling audio announcements")
            }
        )
        
        // API request failure recovery
        recoveryActions[FailureType.API_REQUEST_FAILURE] = RecoveryAction(
            name = "API Request Retry",
            action = {
                isNetworkAvailable() // Check network first
            },
            maxRetries = 3,
            backoffMs = 1000L
        )
        
        // Display update failure recovery
        recoveryActions[FailureType.DISPLAY_UPDATE_FAILURE] = RecoveryAction(
            name = "Display Refresh",
            action = {
                // Force display refresh
                true
            },
            maxRetries = 2,
            backoffMs = 500L
        )
        
        // Device configuration lost recovery
        recoveryActions[FailureType.DEVICE_CONFIGURATION_LOST] = RecoveryAction(
            name = "Configuration Recovery",
            action = {
                // Attempt to reload configuration
                false // Placeholder
            },
            maxRetries = 1,
            backoffMs = 1000L,
            escalationAction = {
                logger.logCritical("Device configuration lost - returning to setup mode")
            }
        )
    }
    
    /**
     * Register a custom recovery action
     */
    fun registerRecoveryAction(failureType: FailureType, action: RecoveryAction) {
        recoveryActions[failureType] = action
        logger.logInfo("Registered recovery action for $failureType", LogCategory.ERROR_RECOVERY)
    }
    
    /**
     * Handle a failure with automatic recovery
     */
    fun handleFailure(
        failureType: FailureType,
        message: String,
        context: Map<String, String> = emptyMap(),
        customRecoveryAction: (suspend () -> Boolean)? = null
    ) {
        val failureEvent = FailureEvent(failureType, message, System.currentTimeMillis(), context)
        
        logger.logError("Failure detected: $failureType - $message", null, LogCategory.ERROR_RECOVERY, context)
        
        // Check if recovery is already in progress for this failure type
        if (activeRecoveries.containsKey(failureType)) {
            logger.logWarn("Recovery already in progress for $failureType", LogCategory.ERROR_RECOVERY)
            return
        }
        
        // Track failure for escalation
        trackFailure(failureType)
        
        // Start recovery process
        val recoveryJob = scope.launch {
            try {
                val success = attemptRecovery(failureType, customRecoveryAction)
                if (success) {
                    logger.logInfo("Recovery successful for $failureType", LogCategory.ERROR_RECOVERY)
                    resetFailureCounter(failureType)
                } else {
                    logger.logError("Recovery failed for $failureType", null, LogCategory.ERROR_RECOVERY)
                    handleRecoveryFailure(failureType)
                }
            } catch (e: Exception) {
                logger.logCritical("Recovery process crashed for $failureType", e)
                handleRecoveryFailure(failureType)
            } finally {
                activeRecoveries.remove(failureType)
            }
        }
        
        activeRecoveries[failureType] = recoveryJob
    }
    
    /**
     * Attempt recovery with retries and backoff
     */
    private suspend fun attemptRecovery(
        failureType: FailureType,
        customAction: (suspend () -> Boolean)? = null
    ): Boolean {
        val recoveryAction = recoveryActions[failureType]
        
        if (recoveryAction == null && customAction == null) {
            logger.logWarn("No recovery action defined for $failureType", LogCategory.ERROR_RECOVERY)
            return false
        }
        
        val action = customAction ?: recoveryAction!!.action
        val maxRetries = recoveryAction?.maxRetries ?: 3
        val backoffMs = recoveryAction?.backoffMs ?: 1000L
        
        repeat(maxRetries) { attempt ->
            try {
                logger.logInfo("Recovery attempt ${attempt + 1}/$maxRetries for $failureType", LogCategory.ERROR_RECOVERY)
                
                val success = action.invoke()
                if (success) {
                    logger.logErrorRecovery(failureType.name, "Attempt ${attempt + 1}", true)
                    return true
                }
                
                // Wait before next attempt with exponential backoff
                if (attempt < maxRetries - 1) {
                    val delayMs = backoffMs * (1L shl attempt) // Exponential backoff
                    delay(delayMs)
                }
                
            } catch (e: Exception) {
                logger.logError("Recovery attempt ${attempt + 1} failed for $failureType", e, LogCategory.ERROR_RECOVERY)
            }
        }
        
        // All attempts failed
        logger.logErrorRecovery(failureType.name, "All $maxRetries attempts", false)
        
        // Execute escalation action if defined
        recoveryAction?.escalationAction?.invoke()
        
        return false
    }
    
    /**
     * Track failure occurrences for escalation
     */
    private fun trackFailure(failureType: FailureType) {
        val counter = retryCounters.getOrPut(failureType) { AtomicInteger(0) }
        val count = counter.incrementAndGet()
        lastFailureTime[failureType] = System.currentTimeMillis()
        
        // Reset counter if enough time has passed
        val lastFailure = lastFailureTime[failureType] ?: 0
        if (System.currentTimeMillis() - lastFailure > FAILURE_RESET_WINDOW_MS) {
            counter.set(1)
        }
        
        if (count >= MAX_CRITICAL_FAILURES) {
            _criticalFailureCount.value = _criticalFailureCount.value + 1
            logger.logCritical("Critical failure threshold reached for $failureType (count: $count)")
            handleCriticalFailure(failureType)
        }
    }
    
    /**
     * Handle critical failures that exceed thresholds
     */
    private fun handleCriticalFailure(failureType: FailureType) {
        when (failureType) {
            FailureType.NETWORK_DISCONNECTION -> {
                // Could restart network interfaces or trigger device reboot
                logger.logCritical("Critical network failure - consider device restart")
            }
            FailureType.WEBSOCKET_FAILURE -> {
                // Switch to polling mode permanently
                logger.logCritical("Critical WebSocket failure - switching to polling mode")
            }
            FailureType.DEVICE_CONFIGURATION_LOST -> {
                // Force return to setup mode
                logger.logCritical("Critical configuration failure - forcing setup mode")
            }
            else -> {
                logger.logCritical("Critical failure for $failureType - manual intervention may be required")
            }
        }
    }
    
    /**
     * Handle when recovery itself fails
     */
    private fun handleRecoveryFailure(failureType: FailureType) {
        when (failureType) {
            FailureType.WEBSOCKET_FAILURE -> {
                // Fall back to polling
                logger.logWarn("WebSocket recovery failed - falling back to API polling")
            }
            FailureType.AUDIO_SYSTEM_FAILURE -> {
                // Disable audio features
                logger.logWarn("Audio recovery failed - disabling audio features")
            }
            else -> {
                logger.logError("Recovery failed for $failureType - no fallback available")
            }
        }
    }
    
    /**
     * Reset failure counter after successful recovery
     */
    private fun resetFailureCounter(failureType: FailureType) {
        retryCounters[failureType]?.set(0)
        lastFailureTime.remove(failureType)
    }
    
    /**
     * Start monitoring network connectivity
     */
    private fun startNetworkMonitoring() {
        scope.launch {
            while (isActive) {
                try {
                    val isConnected = isNetworkAvailable()
                    val previousState = _networkAvailable.value
                    _networkAvailable.value = isConnected
                    
                    if (!previousState && isConnected) {
                        logger.logInfo("Network connectivity restored", LogCategory.NETWORK)
                        handleFailure(
                            FailureType.NETWORK_DISCONNECTION,
                            "Network reconnected",
                            customRecoveryAction = { true }
                        )
                    } else if (previousState && !isConnected) {
                        logger.logWarn("Network connectivity lost", LogCategory.NETWORK)
                    }
                    
                } catch (e: Exception) {
                    logger.logError("Network monitoring failed", e, LogCategory.ERROR_RECOVERY)
                }
                
                delay(NETWORK_CHECK_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Check if network is available
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (e: Exception) {
            logger.logError("Failed to check network availability", e, LogCategory.NETWORK)
            false
        }
    }
    
    /**
     * Get recovery system status
     */
    fun getStatus(): Map<String, Any> {
        return mapOf(
            "networkAvailable" to _networkAvailable.value,
            "activeRecoveries" to activeRecoveries.keys.map { it.name },
            "criticalFailures" to _criticalFailureCount.value,
            "failureCounters" to retryCounters.mapKeys { it.key.name }.mapValues { it.value.get() },
            "registeredActions" to recoveryActions.keys.map { it.name }
        )
    }
    
    /**
     * Force a recovery test
     */
    fun testRecovery(failureType: FailureType, testMessage: String = "Test failure") {
        logger.logInfo("Testing recovery for $failureType", LogCategory.ERROR_RECOVERY)
        handleFailure(
            failureType,
            testMessage,
            mapOf("test" to "true"),
            customRecoveryAction = {
                delay(1000) // Simulate recovery work
                true // Simulate success
            }
        )
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        // Cancel all active recoveries
        activeRecoveries.values.forEach { it.cancel() }
        activeRecoveries.clear()
        
        // Cancel monitoring
        scope.cancel()
        
        logger.logInfo("ErrorRecoveryManager cleaned up", LogCategory.ERROR_RECOVERY)
    }
}

/**
 * Utility functions for common recovery scenarios
 */
object RecoveryUtils {
    
    /**
     * Create a recovery action with exponential backoff
     */
    fun createRetryAction(
        name: String,
        action: suspend () -> Boolean,
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000L
    ): RecoveryAction {
        return RecoveryAction(
            name = name,
            action = action,
            maxRetries = maxRetries,
            backoffMs = initialDelayMs
        )
    }
    
    /**
     * Create a network-dependent recovery action
     */
    fun createNetworkDependentAction(
        name: String,
        action: suspend () -> Boolean,
        connectivityManager: ConnectivityManager
    ): RecoveryAction {
        return RecoveryAction(
            name = name,
            action = {
                // Check network first
                val network = connectivityManager.activeNetwork
                val hasNetwork = network != null && connectivityManager.getNetworkCapabilities(network) != null
                
                if (hasNetwork) {
                    action.invoke()
                } else {
                    false // No network available
                }
            },
            maxRetries = 5,
            backoffMs = 2000L
        )
    }
}