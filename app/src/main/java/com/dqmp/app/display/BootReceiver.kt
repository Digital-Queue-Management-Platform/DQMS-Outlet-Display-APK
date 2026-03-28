package com.dqmp.app.display

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Professional Boot Receiver for Auto-Start Functionality
 * 
 * Automatically launches DQMP Outlet Display when Android TV device boots up.
 * Essential for retail environments where devices need to start the queue display
 * immediately after power on without manual intervention.
 */
class BootReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.i("DQMP_BOOT", "Device boot detected - launching DQMP Outlet Display")
                
                try {
                    val startIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                               Intent.FLAG_ACTIVITY_CLEAR_TASK or
                               Intent.FLAG_ACTIVITY_NO_ANIMATION
                        putExtra("auto_start", true)
                        putExtra("kiosk_mode", true)
                    }
                    
                    context.startActivity(startIntent)
                    Log.i("DQMP_BOOT", "DQMP Outlet Display launched successfully")
                    
                } catch (e: Exception) {
                    Log.e("DQMP_BOOT", "Failed to launch DQMP after boot", e)
                }
            }
        }
    }
}
