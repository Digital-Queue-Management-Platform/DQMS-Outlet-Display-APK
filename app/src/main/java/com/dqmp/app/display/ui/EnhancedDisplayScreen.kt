package com.dqmp.app.display.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dqmp.app.display.R
import com.dqmp.app.display.data.*
import com.dqmp.app.display.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun EnhancedDisplayScreen(
    data: DisplayData,
    counters: List<CounterStatus>,
    branchStatus: BranchStatusResponse,
    isStale: Boolean = false,
    lastSync: Long = 0L,
    clockSkew: Long = 0L
) {
    var currentTime by remember { mutableStateOf(Date(System.currentTimeMillis() + clockSkew)) }
    val slTimeZone = TimeZone.getTimeZone("GMT+05:30")
    val timeFormat = remember { 
        SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).apply { timeZone = slTimeZone } 
    }
    val dateFormat = remember { 
        SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).apply { timeZone = slTimeZone } 
    }

    LaunchedEffect(clockSkew) {
        while (true) {
            currentTime = Date(System.currentTimeMillis() + clockSkew)
            kotlinx.coroutines.delay(1000)
        }
    }

    val settings = data.displaySettings
    val zoomScale = (settings?.contentScale ?: 100).toFloat() / 100f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Emerald500.copy(alpha = 0.04f),
                        Sky400.copy(alpha = 0.03f),
                        Color.Transparent
                    ),
                    center = androidx.compose.ui.geometry.Offset(300f, 100f),
                    radius = 1200f
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = zoomScale, 
                    scaleY = zoomScale,
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                )
                .padding(16.dp)
        ) {
            // Enhanced Header Section
            EnhancedHeader(
                outletName = data.outletMeta?.name ?: "Outlet Queue Display",
                location = data.outletMeta?.location ?: "Customer queue information",
                currentTime = currentTime,
                timeFormat = timeFormat,
                dateFormat = dateFormat,
                totalWaiting = data.totalWaiting,
                serving = data.inService.size,
                availableOfficers = data.availableOfficers,
                isStale = isStale
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Notice Bar (if any notices exist)
            val notice = branchStatus.activeNotice ?: branchStatus.standardNotice
            if (notice != null) {
                NoticeBar(
                    notice = notice,
                    isCritical = branchStatus.activeNotice != null
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Main Content Grid
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Left Column: Now Serving and Up Next
                Column(
                    modifier = Modifier.weight(if ((settings?.counters == true) || (settings?.recent == true)) 3f else 1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Now Serving Section
                    NowServingSection(
                        modifier = Modifier.weight(1.2f),
                        tokens = data.inService,
                        showServices = settings?.services ?: true,
                        autoSlide = settings?.autoSlide ?: true
                    )

                    // Up Next Section  
                    UpNextSection(
                        modifier = Modifier.weight(1f),
                        tokens = data.waiting.take(settings?.next ?: 8),
                        showServices = settings?.services ?: true,
                        autoSlide = settings?.autoSlide ?: true
                    )
                }

                // Right Column: Counter Status and Recent Calls (if enabled)
                if ((settings?.counters == true) || (settings?.recent == true)) {
                    Column(
                        modifier = Modifier.weight(2f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Counter Status Panel
                        if (settings.counters == true) {
                            CounterStatusSection(
                                modifier = Modifier.weight(1f),
                                counters = counters,
                                autoSlide = settings.autoSlide ?: true
                            )
                        }

                        // Recent Calls Panel
                        if (settings.recent == true) {
                            RecentCallsSection(
                                modifier = Modifier.weight(1f),
                                recentCalls = data.recentlyCalled,
                                autoSlide = settings.autoSlide ?: true
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Enhanced Footer
            EnhancedFooter(
                isStale = isStale,
                lastSync = lastSync
            )
        }
    }
}

@Composable
fun EnhancedHeader(
    outletName: String,
    location: String,
    currentTime: Date,
    timeFormat: SimpleDateFormat,
    dateFormat: SimpleDateFormat,
    totalWaiting: Int,
    serving: Int,
    availableOfficers: Int,
    isStale: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Outlet Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = outletName,
                    color = Slate900,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = location,
                    color = Slate500,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Date and Time Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Slate50),
                border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.DateRange, 
                            contentDescription = null, 
                            tint = Sky400, 
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = dateFormat.format(currentTime),
                            color = Slate700,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(18.dp)
                            .background(Slate200)
                    )
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Schedule, 
                            contentDescription = null, 
                            tint = Emerald500, 
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = timeFormat.format(currentTime),
                            color = Slate900,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Stats Mini Cards
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatCard("Waiting", totalWaiting.toString(), Emerald600)
                StatCard("Serving", serving.toString(), Sky500)
                StatCard("Counters", availableOfficers.toString(), Color(0xFF4F46E5))
            }

            // Status Indicator
            if (isStale) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFF6B6B).copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Color(0xFFFF6B6B), CircleShape)
                        )
                        Text(
                            text = "OFFLINE",
                            color = Color(0xFFFF6B6B),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, color: Color) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.1f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label.uppercase(),
                color = Slate400,
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )
            Text(
                text = value,
                color = color,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun NoticeBar(notice: Notice, isCritical: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCritical) Color(0xFFFF6B6B).copy(alpha = 0.1f) else Emerald50
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, 
            if (isCritical) Color(0xFFFF6B6B).copy(alpha = 0.3f) else Emerald200
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                if (isCritical) Icons.Default.Warning else Icons.Default.Info,
                contentDescription = null,
                tint = if (isCritical) Color(0xFFFF6B6B) else Emerald600,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = notice.title,
                    color = if (isCritical) Color(0xFFFF6B6B) else Emerald700,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                if (notice.message.isNotEmpty()) {
                    Text(
                        text = notice.message,
                        color = if (isCritical) Color(0xFFFF6B6B).copy(alpha = 0.8f) else Emerald600,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun EnhancedFooter(isStale: Boolean, lastSync: Long) {
    val slTimeZone = TimeZone.getTimeZone("GMT+05:30")
    val syncTimeFormat = remember { 
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply { timeZone = slTimeZone } 
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.8f))
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            
            // Bottom Row: Centered Text with Logos and Status
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(-2.dp)
            ) {
                // Logo row with left and right alignment, centered text
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: SLT Logo
                    Image(
                        painter = painterResource(id = R.drawable.logo),
                        contentDescription = "SLT-Mobitel Logo",
                        modifier = Modifier.height(32.dp).padding(vertical = 2.dp)
                    )
                    
                    // Center: Platform Title
                    Text(
                        text = "Digital Queue Management Platform",
                        color = Slate800,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    
                    // Right: Transzent Logo
                    Image(
                        painter = painterResource(id = R.drawable.transzent_logo),
                        contentDescription = "Transzent Logo",
                        modifier = Modifier.height(36.dp).padding(vertical = 2.dp)
                    )
                }
                
                // Centered Copyright Text
                Text(
                    text = "© 2026 SLT-Mobitel Digital Platforms Section",
                    color = Slate500,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center
                )
                
                // Status Row (centered)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (lastSync > 0) {
                        Text(
                            text = "Last update: ${syncTimeFormat.format(Date(lastSync))}",
                            color = Slate500,
                            fontSize = 10.sp
                        )
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    if (isStale) Color(0xFFFF6B6B) else Emerald500,
                                    CircleShape
                                )
                        )
                        Text(
                            text = if (isStale) "OFFLINE" else "LIVE",
                            color = if (isStale) Color(0xFFFF6B6B) else Emerald500,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}