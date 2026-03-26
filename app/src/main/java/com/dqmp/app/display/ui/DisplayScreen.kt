package com.dqmp.app.display.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dqmp.app.display.R
import com.dqmp.app.display.data.*
import com.dqmp.app.display.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DisplayScreen(
    data: DisplayData,
    counters: List<CounterStatus>,
    branchStatus: BranchStatusResponse,
    isStale: Boolean = false,
    lastSync: Long = 0L
) {
    var currentTime by remember { mutableStateOf(Date()) }
    
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = Date()
            kotlinx.coroutines.delay(1000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate50)
    ) {
        // Subtle radial gradient background effect
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Emerald500.copy(alpha = 0.05f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(0f, 0f),
                        radius = 1000f
                    )
                )
        )

        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            // Header: Outlet Name, Date/Time, and Summary Boxes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Outlet Meta
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        data.outletMeta?.name ?: "Outlet Display",
                        color = Slate900,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        data.outletMeta?.location?.uppercase() ?: "CUSTOMER QUEUE INFORMATION",
                        color = Slate600,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                // Center: Date and Time
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InfoChip(Icons.Default.DateRange, SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(currentTime), Sky400)
                    InfoChip(Icons.Default.Timer, SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(currentTime), Emerald400)
                }

                // Right: Summary Boxes
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryBox("Waiting", data.totalWaiting.toString(), Emerald50, Emerald700)
                    SummaryBox("Serving", data.inService.size.toString(), Sky50, Sky500)
                    SummaryBox("Counters", data.availableOfficers.toString(), Color(0xFFEEF2FF), Color(0xFF4F46E5))
                }
            }

            // Notices (if any)
            val activeNotice = branchStatus.activeNotice ?: branchStatus.standardNotice
            if (activeNotice != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    color = Color(0xFFFFFBEB), // Amber 50
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFDE68A)) // Amber 200
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFD97706), modifier = Modifier.size(24.dp))
                        Column {
                            Text(activeNotice.title, color = Color(0xFF92400E), fontWeight = FontWeight.Black, fontSize = 20.sp)
                            if (activeNotice.message.isNotEmpty()) {
                                Text(activeNotice.message, color = Color(0xFFB45309), fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main Content Area
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                
                // Left Column: Now Serving and Up Next
                Column(modifier = Modifier.weight(if (data.displaySettings?.counters == true) 2f else 3f)) {
                    
                    // Now Serving Section
                    SectionHeader("Now Serving", Icons.Default.Star, Emerald500)
                    Spacer(Modifier.height(16.dp))
                    
                    Box(modifier = Modifier.weight(1.5f).fillMaxWidth()) {
                        if (data.inService.isEmpty()) {
                            EmptyStateCard("No token is currently in service.")
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                data.inService.take(2).forEach { token ->
                                    ServingTokenCard(token, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Up Next Section
                    SectionHeader("Up Next", Icons.Default.Receipt, Sky500)
                    Spacer(Modifier.height(16.dp))
                    UpNextMarquee(data.waiting, modifier = Modifier.weight(1f))
                }

                // Right Column: Counter Status (if enabled)
                if (data.displaySettings?.counters == true) {
                    Column(modifier = Modifier.weight(1f)) {
                        SectionHeader("Counter Status", Icons.Default.People, Color(0xFF4F46E5))
                        Spacer(Modifier.height(16.dp))
                        CounterStatusList(counters)
                    }
                }
            }

            // Footer
            Spacer(modifier = Modifier.height(32.dp))
            Footer()
        }

        if (isStale) SyncOverlay(lastSync)
    }
}

@Composable
fun InfoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Text(text, color = Slate700, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

@Composable
fun SummaryBox(label: String, value: String, bgColor: Color, textColor: Color) {
    Surface(
        modifier = Modifier.width(100.dp),
        color = bgColor,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, textColor.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Black, color = textColor.copy(alpha = 0.7f), letterSpacing = 1.sp)
            Text(value, fontSize = 28.sp, fontWeight = FontWeight.Black, color = Slate900)
        }
    }
}

@Composable
fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
        Text(title, fontSize = 24.sp, color = Slate900, fontWeight = FontWeight.Black)
    }
}

@Composable
fun EmptyStateCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Slate100.copy(alpha = 0.5f),
        shape = RoundedCornerShape(32.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, Slate200)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(message, color = Slate500, fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun ServingTokenCard(token: Token, modifier: Modifier) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.02f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse)
    )

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer { scaleX = pulse; scaleY = pulse },
        color = Emerald50,
        shape = RoundedCornerShape(32.dp),
        border = androidx.compose.foundation.BorderStroke(3.dp, Emerald200),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (token.counterNumber != null) "COUNTER #${token.counterNumber}" else "STAFF STATION",
                    color = Emerald700,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
                if (!token.serviceTypes.isNullOrEmpty()) {
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(
                            token.serviceTypes[0],
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = Emerald600,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            
            Text(
                token.tokenNumber.toString().padStart(3, '0'),
                fontSize = 120.sp,
                color = Slate900,
                fontWeight = FontWeight.Black,
                lineHeight = 120.sp
            )
            
            Text(
                token.customer?.name ?: "NOW SERVING",
                color = Slate700,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun UpNextMarquee(tokens: List<Token>, modifier: Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(32.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
    ) {
        if (tokens.isEmpty()) {
            Box(contentAlignment = Alignment.Center) {
                Text("No tokens waiting.", color = Slate400, fontSize = 18.sp)
            }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(tokens) { token ->
                    Surface(
                        modifier = Modifier.width(200.dp),
                        color = Slate50,
                        shape = RoundedCornerShape(24.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "TOKEN",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate500
                            )
                            Text(
                                token.tokenNumber.toString().padStart(3, '0'),
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Black,
                                color = Slate900
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CounterStatusList(counters: List<CounterStatus>) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.White,
        shape = RoundedCornerShape(32.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
    ) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(counters.filter { it.number != null }) { counter ->
                val status = counter.officer?.status ?: "offline"
                val (bgColor, textColor, statusText) = when (status) {
                    "available" -> Triple(Emerald50, Emerald700, "Online")
                    "serving" -> Triple(Sky50, Sky500, "Serving")
                    "on_break" -> Triple(Color(0xFFFFF7ED), Color(0xFFC2410C), "On Break")
                    else -> Triple(Slate100, Slate500, "Offline")
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = bgColor,
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, textColor.copy(alpha = 0.1f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            color = textColor,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(counter.number.toString(), color = Color.White, fontWeight = FontWeight.Black)
                            }
                        }
                        Column {
                            Text("Counter #${counter.number}", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Slate900)
                            Text(statusText, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Footer() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painter = painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.height(48.dp))
        Spacer(Modifier.width(24.dp))
        Box(Modifier.width(1.dp).height(40.dp).background(Slate200))
        Spacer(Modifier.width(24.dp))
        Column {
            Text("Digital Queue Management Platform", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Slate900)
            Text("Powered by SLT-Mobitel Digital Operations Group", fontSize = 12.sp, color = Slate500)
        }
    }
}

@Composable
fun SyncOverlay(lastSync: Long) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.TopCenter) {
        Surface(modifier = Modifier.padding(top = 24.dp), color = Color.Red, shape = RoundedCornerShape(20.dp)) {
            Text("RECONNECTING TO CLOUD...", modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp), color = Color.White, fontWeight = FontWeight.Black)
        }
    }
}
