package com.dqmp.app.display.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.alpha
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

            // Main Content - EXACT WEB DASHBOARD REPLICA
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Now Serving Section - matches web exactly
                WebStyleContentCard(
                    modifier = Modifier.weight(1.2f),
                    title = "Now Serving",
                    icon = Icons.Default.AutoAwesome // Sparkles equivalent
                ) {
                    if (data.inService.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No token is currently in service.",
                                color = Slate600,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        WebStyleTokenList(
                            tokens = data.inService,
                            backgroundColor = Color(0xFFECFDF5), // bg-emerald-50
                            borderColor = Color(0xFFA7F3D0), // border-emerald-200
                            showServices = settings?.services ?: true,
                            autoSlide = settings?.autoSlide ?: true,
                            isServing = true
                        )
                    }
                }
                
                // Up Next Section - matches web exactly
                WebStyleContentCard(
                    modifier = Modifier.weight(1f),
                    title = "Up Next",
                    icon = Icons.Default.ConfirmationNumber // Ticket equivalent
                ) {
                    val upNext = data.waiting.take(settings?.next ?: 8)
                    if (upNext.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No waiting tokens right now.",
                                color = Slate600,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    } else {
                        WebStyleTokenList(
                            tokens = upNext,
                            backgroundColor = Color(0xFFF8FAFC), // bg-slate-50
                            borderColor = Color(0xFFE2E8F0), // border-slate-200
                            showServices = settings?.services ?: true,
                            autoSlide = settings?.autoSlide ?: true,
                            isServing = false
                        )
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
    // Match web dashboard header layout exactly - TV optimized
    Column(modifier = Modifier.fillMaxWidth()) {
        // Top Row: Outlet info, Date/Time, Status cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Outlet Info (Left) - matches web grid-cols-1 md:grid-cols-2 2xl:grid-cols-3
            Column(modifier = Modifier.weight(1.5f)) {
                Text(
                    text = outletName,
                    color = Slate900,
                    fontSize = 28.sp, // Reduced for TV screens
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = location,
                    color = Slate600,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            // Date/Time Card (Center) - matches web dashboard styling
            Card(
                modifier = Modifier.wrapContentWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)) // border-slate-200
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Date section
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.DateRange, 
                            contentDescription = null, 
                            tint = Sky400, 
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = dateFormat.format(currentTime),
                            color = Slate700,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    
                    // Divider
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(20.dp)
                            .background(Color(0xFFE2E8F0)) // border-slate-200
                    )
                    
                    // Time section
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Schedule, 
                            contentDescription = null, 
                            tint = Emerald400, 
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = timeFormat.format(currentTime),
                            color = Slate700,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            
            // Status Cards (Right) - exact match to web dashboard
            Row(
                modifier = Modifier.weight(2f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Waiting Card - matches web bg-emerald-50 border-emerald-200
                WebStyleStatCard(
                    label = "Waiting",
                    value = totalWaiting.toString(),
                    backgroundColor = Color(0xFFECFDF5), // bg-emerald-50
                    borderColor = Color(0xFFA7F3D0), // border-emerald-200  
                    textColor = Color(0xFF047857), // text-emerald-700
                    modifier = Modifier.weight(1f)
                )
                
                // Serving Card - matches web bg-sky-50 border-sky-200
                WebStyleStatCard(
                    label = "Serving", 
                    value = serving.toString(),
                    backgroundColor = Color(0xFFF0F9FF), // bg-sky-50
                    borderColor = Color(0xFFBAE6FD), // border-sky-200
                    textColor = Color(0xFF0369A1), // text-sky-700
                    modifier = Modifier.weight(1f)
                )
                
                // Counters Card - matches web bg-indigo-50 border-indigo-200  
                WebStyleStatCard(
                    label = "Counters",
                    value = availableOfficers.toString(),
                    backgroundColor = Color(0xFFF0F0FF), // bg-indigo-50
                    borderColor = Color(0xFFC7D2FE), // border-indigo-200
                    textColor = Color(0xFF4338CA), // text-indigo-700
                    modifier = Modifier.weight(1f)
                )
            }
        }
        
        // Connection status indicator (if stale)
        if (isStale) {
            Card(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFF6B6B).copy(alpha = 0.1f)),
                border = BorderStroke(1.dp, Color(0xFFFF6B6B).copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF6B6B),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "CONNECTION LOST - Data may be outdated",
                        color = Color(0xFFFF6B6B),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun WebStyleStatCard(
    label: String,
    value: String,
    backgroundColor: Color,
    borderColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    // Exact replica of web dashboard stat cards - TV optimized
    Card(
        modifier = modifier.height(70.dp), // Reduced height for TV
        shape = RoundedCornerShape(12.dp), // Slightly smaller radius
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp), // Reduced padding
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Label text - matches web text-[10px] font-black uppercase tracking-widest opacity-70  
            Text(
                text = label.uppercase(),
                color = textColor.copy(alpha = 0.7f),
                fontSize = 9.sp, // Slightly smaller for TV
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp, // Reduced letter spacing
                textAlign = TextAlign.Center,
                lineHeight = 10.sp
            )
            
            Spacer(modifier = Modifier.height(2.dp))
            
            // Value text - matches web font-black text-slate-900 with dynamic fontSize
            Text(
                text = value,
                color = Slate900,
                fontSize = 20.sp, // Reduced for TV layout
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
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

// NEW WEB-IDENTICAL COMPONENTS

@Composable
fun WebStyleContentCard(
    modifier: Modifier = Modifier,
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    // Exact replica of web dashboard content cards: rounded-3xl border shadow-sm bg-white border-slate-200
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp), // rounded-3xl = 24dp
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)), // border-slate-200
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp) // shadow-sm
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp) // p-2 sm:p-4 equivalent
        ) {
            // Header with icon and title - matches web exactly
            Row(
                modifier = Modifier.padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (title == "Now Serving") Emerald400 else Sky400,
                    modifier = Modifier.size(20.dp) // w-5 h-5
                )
                Text(
                    text = title,
                    color = Slate900,
                    fontSize = 18.sp, // text-base sm:text-lg md:text-xl
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Content area
            Box(modifier = Modifier.weight(1f)) {
                content()
            }
        }
    }
}

@Composable 
fun WebStyleTokenList(
    tokens: List<Token>,
    backgroundColor: Color,
    borderColor: Color,
    showServices: Boolean,
    autoSlide: Boolean,
    isServing: Boolean
) {
    if (tokens.isEmpty()) return
    
    // Horizontal scrolling token list - matches web marquee
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    ) {
        items(tokens) { token ->
            WebStyleTokenCard(
                token = token,
                backgroundColor = backgroundColor,
                borderColor = borderColor,
                showService = showServices,
                isServing = isServing
            )
        }
    }
}

@Composable
fun WebStyleTokenCard(
    token: Token,
    backgroundColor: Color,
    borderColor: Color, 
    showService: Boolean,
    isServing: Boolean
) {
    // Exact replica of web token cards with proper sizing
    Card(
        modifier = Modifier
            .width(200.dp) // w-[min(72vw,230px)] equivalent
            .height(80.dp),
        shape = RoundedCornerShape(12.dp), // rounded-xl
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp) // shadow-sm
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp), // py-2 px-3
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // Header row with queue position or service info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isServing) "Serving" else "Queue",
                        color = if (isServing) Color(0xFF059669) else Color(0xFF475569), // emerald-700 : slate-700
                        fontSize = 10.sp, // text-[12px]
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.alpha(0.8f) // opacity-80
                    )
                    
                    if (showService && token.serviceTypes?.isNotEmpty() == true) {
                        Card(
                            shape = RoundedCornerShape(6.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, Color(0xFFDEEFFE)), // border-sky-100
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Text(
                                text = token.serviceTypes?.firstOrNull()?.take(8) ?: "",
                                color = Color(0xFF0284C7), // text-sky-600
                                fontSize = 9.sp, // text-[11px]
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Token number - matches web styling
                Text(
                    text = String.format("%03d", token.tokenNumber), // padStart(3, "0")
                    color = Slate900,
                    fontSize = 20.sp, // clamp(1.2rem, 8vw, 2.5rem)
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp // tracking-wider
                )
            }
        }
    }
}