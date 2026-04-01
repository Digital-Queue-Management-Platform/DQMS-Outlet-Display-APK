package com.dqmp.app.display.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.view.WindowManager
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Android TV Lifecycle Management
 * 
 * Handles Android TV specific lifecycle concerns including:
 * - Screen wake lock management
 * - Kiosk mode enforcement  
 * - App resume/pause handling
 * - Boot-up initialization
 * - Power management
 */

data class LifecycleState(
    val isScreenOn: Boolean = true,
    val isAppVisible: Boolean = true,
    val isKioskMode: Boolean = false,
    val wakeLockActive: Boolean = false,
    val lastResumeTime: Long = 0L,
    val bootCompleted: Boolean = false
)

class AndroidTVLifecycleManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) : DefaultLifecycleObserver {
    
    private val logger = ProductionLogger.getInstance(context)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    
    // Wake lock management
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Lifecycle state
    private val _lifecycleState = MutableStateFlow(LifecycleState())
    val lifecycleState: StateFlow<LifecycleState> = _lifecycleState.asStateFlow()
    
    // Lifecycle callbacks
    private var onAppResumed: (suspend () -> Unit)? = null
    private var onAppPaused: (suspend () -> Unit)? = null
    private var onNetworkRestored: (suspend () -> Unit)? = null
    
    companion object {
        private const val TAG = "AndroidTVLifecycle"
        private const val WAKE_LOCK_TAG = "DQMP:KeepScreenOn"
    }
    
    init {
        lifecycleOwner.lifecycle.addObserver(this)
        logger.logInfo("AndroidTVLifecycleManager initialized", LogCategory.LIFECYCLE)
    }
    
    /**
     * Initialize kiosk mode and wake lock
     */
    fun setupKioskMode(activity: Activity, enable: Boolean = true) {
        if (enable) {
            try {
                // Hide system UI for kiosk mode
                activity.window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
                
                // Keep screen on
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                
                // Prevent accidental exit
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                
                _lifecycleState.update { it.copy(isKioskMode = true) }
                
                logger.logInfo("Kiosk mode enabled", LogCategory.LIFECYCLE, mapOf(
                    "fullscreen" to "true",
                    "keepScreenOn" to "true"
                ))
                
            } catch (e: Exception) {
                logger.logError("Failed to setup kiosk mode", e, LogCategory.LIFECYCLE)
            }
        }
    }
    
    /**
     * Acquire wake lock to keep screen on
     */
    fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    WAKE_LOCK_TAG
                )
            }
            
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire()
                _lifecycleState.update { it.copy(wakeLockActive = true) }
                
                logger.logInfo("Wake lock acquired", LogCategory.LIFECYCLE)
            }
            
        } catch (e: Exception) {
            logger.logError("Failed to acquire wake lock", e, LogCategory.LIFECYCLE)
        }
    }
    
    /**
     * Release wake lock
     */
    fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
            _lifecycleState.update { it.copy(wakeLockActive = false) }
            
            logger.logInfo("Wake lock released", LogCategory.LIFECYCLE)
            
        } catch (e: Exception) {
            logger.logError("Failed to release wake lock", e, LogCategory.LIFECYCLE)
        }
    }
    
    /**
     * Set callback for app resume
     */
    fun setOnAppResumed(callback: suspend () -> Unit) {
        onAppResumed = callback
    }
    
    /**
     * Set callback for app pause
     */
    fun setOnAppPaused(callback: suspend () -> Unit) {
        onAppPaused = callback
    }
    
    /**
     * Set callback for network restoration
     */
    fun setOnNetworkRestored(callback: suspend () -> Unit) {
        onNetworkRestored = callback
    }
    
    /**
     * Handle app coming to foreground
     */
    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        
        val now = System.currentTimeMillis()
        _lifecycleState.update { 
            it.copy(
                isAppVisible = true,
                lastResumeTime = now
            ) 
        }
        
        logger.logInfo("App resumed", LogCategory.LIFECYCLE, mapOf(
            "resumeTime" to now.toString()
        ))
        
        // Execute resume callback
        owner.lifecycleScope.launch {
            try {
                onAppResumed?.invoke()
            } catch (e: Exception) {
                logger.logError("Resume callback failed", e, LogCategory.LIFECYCLE)
            }
        }
    }
    
    /**
     * Handle app going to background
     */
    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        
        _lifecycleState.update { it.copy(isAppVisible = false) }
        
        logger.logInfo("App paused", LogCategory.LIFECYCLE)
        
        // Execute pause callback
        owner.lifecycleScope.launch {
            try {
                onAppPaused?.invoke()
            } catch (e: Exception) {
                logger.logError("Pause callback failed", e, LogCategory.LIFECYCLE)
            }
        }
    }
    
    /**
     * Handle app start
     */
    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        
        logger.logInfo("App started", LogCategory.LIFECYCLE)
        
        // Ensure wake lock is active
        acquireWakeLock()
    }
    
    /**
     * Handle app stop
     */
    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        
        logger.logInfo("App stopped", LogCategory.LIFECYCLE)
        
        // Keep wake lock for TV display - don't release on stop
        // Wake lock will be released only on destroy
    }
    
    /**
     * Handle app destruction
     */
    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        
        logger.logInfo("App destroying", LogCategory.LIFECYCLE)
        
        // Release wake lock
        releaseWakeLock()
        
        // Remove lifecycle observer
        owner.lifecycle.removeObserver(this)
    }
    
    /**
     * Handle cold start after reboot
     */
    fun handleBootCompleted() {
        _lifecycleState.update { it.copy(bootCompleted = true) }
        
        logger.logInfo("Boot completed - app starting", LogCategory.LIFECYCLE, mapOf(
            "autoStart" to "true"
        ))
        
        // Ensure screen stays on after boot
        acquireWakeLock()
    }
    
    /**
     * Handle network restoration after disconnection
     */
    fun handleNetworkRestored() {
        logger.logInfo("Network restored", LogCategory.NETWORK)
        
        // Execute network restoration callback
        lifecycleOwner.lifecycleScope.launch {
            try {
                onNetworkRestored?.invoke()
            } catch (e: Exception) {
                logger.logError("Network restoration callback failed", e, LogCategory.LIFECYCLE)
            }
        }
    }
    
    /**
     * Check if app should auto-restart
     */
    fun shouldAutoRestart(): Boolean {
        val currentState = _lifecycleState.value
        
        // Auto-restart conditions
        return when {
            !currentState.isScreenOn -> {
                logger.logInfo("Auto-restart: Screen turned on", LogCategory.LIFECYCLE)
                true
            }
            currentState.bootCompleted -> {
                logger.logInfo("Auto-restart: Boot completed", LogCategory.LIFECYCLE) 
                true
            }
            else -> false
        }
    }
    
    /**
     * Force screen to stay on (for critical operations)
     */
    fun forceScreenOn(activity: Activity) {
        try {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            
            acquireWakeLock()
            
            logger.logInfo("Forced screen on", LogCategory.LIFECYCLE)
            
        } catch (e: Exception) {
            logger.logError("Failed to force screen on", e, LogCategory.LIFECYCLE)
        }
    }
    
    /**
     * Handle back button prevention (for kiosk mode)
     */
    fun handleBackPressed(): Boolean {
        val kioskMode = _lifecycleState.value.isKioskMode
        
        if (kioskMode) {
            logger.logDebug("Back button blocked in kiosk mode", LogCategory.LIFECYCLE)
            return true // Consume the back press
        }
        
        return false // Allow back press
    }
    
    /**
     * Get current lifecycle status
     */
    fun getStatus(): Map<String, Any> {
        val state = _lifecycleState.value
        
        return mapOf(
            "isScreenOn" to state.isScreenOn,
            "isAppVisible" to state.isAppVisible,
            "isKioskMode" to state.isKioskMode,
            "wakeLockActive" to state.wakeLockActive,
            "lastResumeTime" to state.lastResumeTime,
            "bootCompleted" to state.bootCompleted,
            "uptime" to (System.currentTimeMillis() - state.lastResumeTime)
        )
    }
    
    /**
     * Restart app if needed
     */
    fun restartAppIfNeeded(activity: Activity) {
        if (shouldAutoRestart()) {
            logger.logInfo("Restarting app due to lifecycle conditions", LogCategory.LIFECYCLE)
            
            val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            intent?.apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            activity.startActivity(intent)
            activity.finish()
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        releaseWakeLock()
        logger.logInfo("AndroidTVLifecycleManager cleaned up", LogCategory.LIFECYCLE)
    }
}

/**
 * Boot completion receiver for auto-start
 */
class BootCompletedReceiver : android.content.BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val logger = ProductionLogger.getInstance(context)
            logger.logInfo("Boot completed - preparing auto-start", LogCategory.LIFECYCLE)
            
            // Start main activity with auto-start flag
            val startIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            startIntent?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("auto_start", true)
                putExtra("kiosk_mode", true)
            }
            
            try {
                context.startActivity(startIntent)
                logger.logInfo("Auto-start initiated successfully", LogCategory.LIFECYCLE)
            } catch (e: Exception) {
                logger.logError("Auto-start failed", e, LogCategory.LIFECYCLE)
            }
        }
    }
}