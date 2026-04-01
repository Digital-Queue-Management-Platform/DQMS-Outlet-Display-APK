package com.dqmp.app.display.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dqmp.app.display.ui.theme.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.dqmp.app.display.data.DeviceManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

/**
 * Professional QR Code Setup Screen
 * 
 * Displays a QR code containing device information for teleshop managers to scan
 * from their dashboard to configure the outlet automatically. This eliminates
 * manual outlet ID entry and provides professional one-touch setup.
 */

data class SetupDeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val macAddress: String,
    val setupCode: String,
    val timestamp: Long
)

@Composable
fun QRCodeSetupScreen(
    onConfigurationReceived: (outletId: String, baseUrl: String) -> Unit,
    onManualSetup: () -> Unit
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }
    var isChecking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Waiting for setup...") }
    
    // Device manager for configuration polling
    val deviceManager = remember { DeviceManager(context) }
    
    // Generate fresh device info every time, with refresh capability
    val deviceInfo = remember(refreshKey) { generateDeviceInfo(context) }
    val qrBitmap = remember(refreshKey) { generateQRCode(deviceInfo) }
    
    // Poll for configuration completion using DeviceManager
    LaunchedEffect(deviceInfo.deviceId) {
        isChecking = true
        statusMessage = "Connecting to backend..."
        
        // Start polling with proper error handling
        deviceManager.pollForConfiguration(
            deviceId = deviceInfo.deviceId,
            onConfigurationFound = { outletId, baseUrl ->
                statusMessage = "Configuration complete! Connecting..."
                onConfigurationReceived(outletId, baseUrl)
            },
            onError = { error ->
                statusMessage = "Connection error: $error"
                isChecking = false
            }
        )
        
        statusMessage = "Scanning for configuration..."
        isChecking = true
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Slate900
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            horizontalArrangement = Arrangement.spacedBy(64.dp)
        ) {
            // Left Panel - QR Code
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "OUTLET SETUP",
                    style = MaterialTheme.typography.headlineLarge,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Card(
                    modifier = Modifier
                        .size(400.dp)
                        .border(4.dp, Emerald500, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Setup QR Code",
                                modifier = Modifier.size(300.dp)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(300.dp)
                                    .background(Slate200, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "QR Code\nGeneration\nFailed",
                                    textAlign = TextAlign.Center,
                                    color = Slate600
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Setup Code",
                            fontSize = 14.sp,
                            color = Slate600,
                            fontWeight = FontWeight.Medium
                        )
                        
                        Text(
                            text = deviceInfo.setupCode,
                            fontSize = 18.sp,
                            color = Emerald600,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "Device ID: ${deviceInfo.deviceId}",
                    fontSize = 16.sp,
                    color = Slate400,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Refresh QR Code Button
                Button(
                    onClick = { refreshKey++ },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Emerald600,
                        contentColor = androidx.compose.ui.graphics.Color.White
                    ),
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generate New QR Code")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Status Message
                Text(
                    text = statusMessage,
                    fontSize = 14.sp,
                    color = if (isChecking) Emerald400 else Slate400,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Right Panel - Instructions
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // Header
                Column {
                    Icon(
                        imageVector = Icons.Default.QrCode2,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Emerald500
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "Professional Setup",
                        style = MaterialTheme.typography.headlineMedium,
                        color = androidx.compose.ui.graphics.Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Text(
                        text = "One-time configuration for outlet display",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Slate400
                    )
                }

                // Instructions
                Card(
                    colors = CardDefaults.cardColors(containerColor = Slate800),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        InstructionStep(
                            number = "1",
                            title = "Open Teleshop Manager",
                            description = "Access your teleshop manager dashboard on computer or mobile"
                        )
                        
                        InstructionStep(
                            number = "2", 
                            title = "Navigate to Outlet Setup",
                            description = "Go to Outlets > Add New Outlet Display Device"
                        )
                        
                        InstructionStep(
                            number = "3",
                            title = "Scan QR Code",
                            description = "Use the QR scanner in dashboard to scan this code"
                        )
                        
                        InstructionStep(
                            number = "4",
                            title = "Automatic Configuration",
                            description = "Display will automatically configure and start showing queue"
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Manual Setup Option
                OutlinedButton(
                    onClick = onManualSetup,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Slate600),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Slate300
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Manual Setup", fontSize = 16.sp)
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Reset Device ID button
                OutlinedButton(
                    onClick = { 
                        // Clear the saved device ID to force regeneration
                        context.getSharedPreferences("dqmp_device", Context.MODE_PRIVATE)
                            .edit().clear().apply()
                        // Trigger refresh
                        refreshKey += 1
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Orange500),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Orange500
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generate New Device ID", fontSize = 16.sp)
                }
                
                // Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.NetworkWifi,
                        contentDescription = null,
                        tint = Emerald500,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Waiting for configuration...",
                        color = Slate400,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun InstructionStep(
    number: String,
    title: String,
    description: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(Emerald500, RoundedCornerShape(50))
                .border(2.dp, androidx.compose.ui.graphics.Color.White, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                color = androidx.compose.ui.graphics.Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        Column {
            Text(
                text = title,
                color = androidx.compose.ui.graphics.Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )
            Text(
                text = description,
                color = Slate400,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}

private fun generateDeviceInfo(context: android.content.Context): SetupDeviceInfo {
    // Get or create a persistent device identifier
    val sharedPrefs = context.getSharedPreferences("dqmp_device", Context.MODE_PRIVATE)
    var deviceId = sharedPrefs.getString("device_id", null)
    
    if (deviceId == null) {
        // Generate a new stable device ID first time
        deviceId = try {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            
            if (androidId != null && androidId != "9774d56d682e549c") {
                // Use ANDROID_ID if available and not the known placeholder
                androidId
            } else {
                // Generate a random but persistent ID
                UUID.randomUUID().toString().replace("-", "").take(16)
            }
        } catch (e: Exception) {
            UUID.randomUUID().toString().replace("-", "").take(16)
        }
        
        // Save the device ID permanently
        sharedPrefs.edit().putString("device_id", deviceId).apply()
    }
    
    val deviceName = android.os.Build.MODEL
    
    // Get WiFi MAC address (uses alternative method for newer Android versions)
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val macAddress = try {
        // For newer Android versions, this returns a placeholder for privacy
        @Suppress("DEPRECATION")
        wifiManager.connectionInfo?.macAddress ?: "02:00:00:00:00:00"
    } catch (e: Exception) {
        "02:00:00:00:00:00"
    }
    
    val setupCode = generateSetupCode()
    val timestamp = System.currentTimeMillis()
    
    return SetupDeviceInfo(deviceId!!, deviceName, macAddress, setupCode, timestamp)
}

private fun generateSetupCode(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return (1..8)
        .map { chars.random() }
        .joinToString("")
        .chunked(4)
        .joinToString("-")
}

private fun generateQRCode(deviceInfo: SetupDeviceInfo): Bitmap? {
    return try {
        val qrCodeContent = """
            {
                "type": "dqmp_outlet_setup",
                "version": "1.0",
                "deviceId": "${deviceInfo.deviceId}",
                "deviceName": "${deviceInfo.deviceName}",
                "macAddress": "${deviceInfo.macAddress}",
                "setupCode": "${deviceInfo.setupCode}",
                "timestamp": ${deviceInfo.timestamp}
            }
        """.trimIndent()
        
        val writer = QRCodeWriter()
        val bitMatrix: BitMatrix = writer.encode(qrCodeContent, BarcodeFormat.QR_CODE, 300, 300)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        
        bitmap
    } catch (e: WriterException) {
        null
    } catch (e: Exception) {
        null
    }
}