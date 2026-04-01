package com.dqmp.app.display.ui.screen

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dqmp.app.display.R
import com.dqmp.app.display.ui.theme.ThemeProvider
import com.dqmp.app.display.viewmodel.ConfigurationViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject

@Composable
fun QRConfigurationScreen(
    onConfigurationComplete: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConfigurationViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val theme = ThemeProvider.getTheme("emerald") // Default theme for configuration
    
    // Generate device ID and QR code data
    val deviceId = remember { 
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) 
    }
    
    val qrCodeData = remember(deviceId) {
        JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", "Android TV Display")
            put("type", "outlet_display")
            put("version", "1.0.0")
            put("configurationUrl", "dqmp://configure")
        }.toString()
    }
    
    val qrBitmap = remember(qrCodeData) {
        generateQRCode(qrCodeData, 512)
    }
    
    // Animated background
    val infiniteTransition = rememberInfiniteTransition(label = "background")
    val animatedOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "offset"
    )
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        theme.background,
                        theme.surface,
                        theme.background
                    ),
                    center = androidx.compose.ui.geometry.Offset(
                        x = 0.5f + animatedOffset * 0.2f,
                        y = 0.5f + animatedOffset * 0.1f
                    ),
                    radius = 800f + animatedOffset * 200f
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Header Section
            HeaderSection(theme = theme)
            
            // QR Code Section
            QRCodeSection(
                qrBitmap = qrBitmap,
                deviceId = deviceId,
                theme = theme
            )
            
            // Instructions Section
            InstructionsSection(theme = theme)
            
            // Status Section
            StatusSection(
                status = uiState.connectionStatus,
                theme = theme
            )
        }
        
        // Loading overlay when configuring
        if (uiState.isConfiguring) {
            ConfiguringOverlay(theme = theme)
        }
        
        // Listen for configuration completion
        LaunchedEffect(uiState.isConfigured) {
            if (uiState.isConfigured) {
                onConfigurationComplete(uiState.outletId)
            }
        }
    }
}

@Composable
private fun HeaderSection(theme: com.dqmp.app.display.ui.theme.AppTheme) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            color = theme.text,
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Text(
            text = stringResource(R.string.configuration_required),
            color = theme.textSecondary,
            fontSize = 24.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun QRCodeSection(
    qrBitmap: Bitmap?,
    deviceId: String,
    theme: com.dqmp.app.display.ui.theme.AppTheme
) {
    Card(
        modifier = Modifier.size(400.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.scan_qr_code),
                color = Color.Black,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.qr_code_description),
                    modifier = Modifier
                        .size(280.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = 2.dp,
                            color = theme.accent.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(12.dp)
                        )
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .background(
                            Color.Gray.copy(alpha = 0.2f),
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = theme.accent,
                        strokeWidth = 4.dp
                    )
                }
            }
            
            Text(
                text = stringResource(R.string.device_id, deviceId.takeLast(8).uppercase()),
                color = Color.Gray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun InstructionsSection(theme: com.dqmp.app.display.ui.theme.AppTheme) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = theme.surface.copy(alpha = 0.8f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.configuration_instructions),
                color = theme.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InstructionStep(
                    step = "1",
                    text = stringResource(R.string.instruction_step_1),
                    theme = theme
                )
                InstructionStep(
                    step = "2",
                    text = stringResource(R.string.instruction_step_2),
                    theme = theme
                )
                InstructionStep(
                    step = "3",
                    text = stringResource(R.string.instruction_step_3),
                    theme = theme
                )
            }
        }
    }
}

@Composable
private fun InstructionStep(
    step: String,
    text: String,
    theme: com.dqmp.app.display.ui.theme.AppTheme
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(theme.accent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step,
                color = theme.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        Text(
            text = text,
            color = theme.textSecondary,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatusSection(
    status: String,
    theme: com.dqmp.app.display.ui.theme.AppTheme
) {
    val (color, icon, message) = when (status) {
        "waiting" -> Triple(
            Color.Yellow,
            "⏳",
            stringResource(R.string.waiting_for_configuration)
        )
        "connecting" -> Triple(
            Color.Blue,
            "🔄",
            stringResource(R.string.connecting_to_manager)
        )
        "error" -> Triple(
            Color.Red,
            "❌",
            stringResource(R.string.configuration_error)
        )
        else -> Triple(
            Color.Gray,
            "📱",
            stringResource(R.string.ready_to_scan)
        )
    }
    
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = icon,
            fontSize = 24.sp
        )
        
        Text(
            text = message,
            color = color,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ConfiguringOverlay(theme: com.dqmp.app.display.ui.theme.AppTheme) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = theme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
        ) {
            Column(
                modifier = Modifier.padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                CircularProgressIndicator(
                    color = theme.accent,
                    strokeWidth = 6.dp,
                    modifier = Modifier.size(64.dp)
                )
                
                Text(
                    text = stringResource(R.string.configuring_device),
                    color = theme.text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium
                )
                
                Text(
                    text = stringResource(R.string.please_wait),
                    color = theme.textSecondary,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun generateQRCode(text: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix: BitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(
                    x, y, 
                    if (bitMatrix[x, y]) android.graphics.Color.BLACK 
                    else android.graphics.Color.WHITE
                )
            }
        }
        bitmap
    } catch (e: WriterException) {
        e.printStackTrace()
        null
    }
}