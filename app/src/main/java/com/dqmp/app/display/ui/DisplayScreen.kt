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
    lastSync: Long = 0L,
    clockSkew: Long = 0L
) {
    var currentTime by remember { mutableStateOf(Date(System.currentTimeMillis() + clockSkew)) }
    // Fixed: Explicit GMT offset for Sri Lanka to ensure accuracy on all devices
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

    val zoomScale = (data.displaySettings?.contentScale ?: 100).toFloat() / 100f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate50)
    ) {
        // Background Gradients
        Box(
            modifier = Modifier.fillMaxSize()
                .background(Brush.radialGradient(
                    colors = listOf(Emerald500.copy(alpha = 0.04f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(300f, 100f),
                    radius = 1200f
                ))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = zoomScale, scaleY = zoomScale, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f))
                .padding(24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = data.outletMeta?.name ?: "Outlet Queue Display", color = Slate900, fontSize = 32.sp, fontWeight = FontWeight.Black)
                    Text(text = data.outletMeta?.location ?: "Customer queue information", color = Slate500, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }

                Surface(color = Color.White, shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Slate200), shadowElevation = 1.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.DateRange, null, tint = Sky400, modifier = Modifier.size(20.dp))
                            Text(dateFormat.format(currentTime), color = Slate700, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(modifier = Modifier.width(1.dp).height(20.dp).background(Slate200))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Schedule, null, tint = Emerald500, modifier = Modifier.size(20.dp))
                            Text(timeFormat.format(currentTime), color = Slate900, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    MiniStat("Waiting", data.totalWaiting.toString(), Emerald600)
                    Spacer(Modifier.width(16.dp))
                    MiniStat("Serving", data.inService.size.toString(), Sky500)
                    Spacer(Modifier.width(16.dp))
                    MiniStat("Counters", data.availableOfficers.toString(), Color(0xFF4F46E5))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Notice Bar
            val notice = branchStatus.activeNotice ?: branchStatus.standardNotice
            if (notice != null) {
                NoticeBar(notice = notice, isCritical = branchStatus.activeNotice != null)
                Spacer(modifier = Modifier.height(20.dp))
            }

            // Body: Now Serving & Up Next side-by-side with Auto-Sliding
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Section: Now Serving (Horizontal Sliding)
                SectionHeader("Now Serving", Emerald500, Icons.Default.Notifications)
                Spacer(modifier = Modifier.height(16.dp))
                AutoSlidingLazyRow(
                    items = data.inService,
                    modifier = Modifier.weight(1.2f).fillMaxWidth(),
                    autoSlide = data.displaySettings?.autoSlide ?: true
                ) { token ->
                    ServingCard(token, showServices = data.displaySettings?.services ?: true, modifier = Modifier.padding(8.dp).width(340.dp).fillMaxHeight())
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Section: Up Next (Horizontal Sliding)
                SectionHeader("Up Next", Sky400, Icons.Default.List)
                Spacer(modifier = Modifier.height(16.dp))
                AutoSlidingLazyRow(
                    items = data.waiting.take(data.displaySettings?.next ?: 20).withIndex().toList(),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    autoSlide = data.displaySettings?.autoSlide ?: true
                ) { (idx, token) ->
                    NextCard(token, idx + 1, modifier = Modifier.padding(8.dp).width(260.dp).fillMaxHeight())
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            BrandedFooter()
        }

        if (isStale) SyncOverlay(lastSync)
    }
}

@Composable
fun <T> AutoSlidingLazyRow(
    items: List<T>,
    modifier: Modifier = Modifier,
    slideIntervalMs: Long = 4000,
    autoSlide: Boolean = true,
    itemContent: @Composable (T) -> Unit
) {
    if (items.isEmpty()) {
        Surface(modifier = modifier, color = Color.White, shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Slate100)) {
            NoDataView("No data available.")
        }
        return
    }

    val listState = rememberLazyListState()
    
    // Auto-sliding logic
    LaunchedEffect(items, autoSlide) {
        if (items.size > 1 && autoSlide) {
            while (true) {
                kotlinx.coroutines.delay(slideIntervalMs)
                val nextIndex = (listState.firstVisibleItemIndex + 1) % items.size
                listState.animateScrollToItem(nextIndex)
            }
        }
    }

    androidx.compose.foundation.lazy.LazyRow(
        state = listState,
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        items(items) { item ->
            itemContent(item)
        }
    }
}

@Composable
fun MiniStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), color = Slate400, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun NoDataView(msg: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(msg, color = Slate400, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ServingCard(token: Token, showServices: Boolean = true, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.99f, targetValue = 1.01f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Reverse)
    )

    Surface(
        modifier = modifier.graphicsLayer { scaleX = pulse; scaleY = pulse },
        color = Emerald50,
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, Emerald200.copy(alpha = 0.5f)),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Emerald600, shape = CircleShape) {
                    Text("COUNTER ${token.counterNumber ?: "?"}", modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
                Surface(color = Emerald500.copy(alpha = 0.1f), shape = CircleShape) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(modifier = Modifier.size(6.dp).background(Emerald500, CircleShape))
                        Text("SERVING", color = Emerald700, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Text(
                text = token.tokenNumber.toString().padStart(3, '0'),
                fontSize = 110.sp,
                color = Slate900,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                lineHeight = 110.sp
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    token.customer?.name?.uppercase() ?: "CUSTOMER",
                    color = Slate500,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                
                if (showServices && token.serviceTypes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Surface(color = Emerald100, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            token.serviceTypes.joinToString(" • ").uppercase(),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = Emerald800,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NextCard(token: Token, position: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp).fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = "POS #$position", color = Slate400, fontSize = 12.sp, fontWeight = FontWeight.Black)
                Surface(color = Slate100, shape = CircleShape) {
                    Text("WAITING", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = Slate600, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            
            Text(
                text = token.tokenNumber.toString().padStart(3, '0'),
                fontSize = 80.sp,
                color = Slate900,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                lineHeight = 80.sp
            )

            if (token.counterNumber != null && token.counterNumber > 0) {
                Surface(color = Sky100, shape = RoundedCornerShape(4.dp)) {
                    Text(
                        "GO TO COUNTER ${token.counterNumber}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        color = Sky500,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            } else {
                Text(text = "PLEASE WAIT", color = Slate300, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun BrandedFooter() {
    Surface(modifier = Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Slate100), shadowElevation = 1.dp) {
        Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(painter = painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.height(28.dp))
                Box(modifier = Modifier.width(1.dp).height(24.dp).background(Slate200))
                Image(painter = painterResource(R.drawable.transzent_logo), contentDescription = null, modifier = Modifier.height(36.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(text = "Digital Queue Management Platform", color = Slate800, fontSize = 14.sp, fontWeight = FontWeight.Black)
                Text(text = "© 2026 SLT-Mobitel Digital Platforms Section", color = Slate400, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, color: Color, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
        Text(text = title, color = Slate900, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun NoticeBar(notice: Notice, isCritical: Boolean) {
    val bg = if (isCritical) Color(0xFFFFFBEC) else Color(0xFFF0FDF4)
    val color = if (isCritical) Color(0xFFD97706) else Emerald600
    val borderColor = if (isCritical) Color(0xFFFCD34D) else Emerald200
    Surface(color = bg, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.Warning, null, tint = color, modifier = Modifier.size(28.dp))
            Column {
                Text(notice.title, color = Slate900, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (notice.message.isNotEmpty()) {
                    Text(notice.message, color = Slate600, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun SyncOverlay(lastSync: Long) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)), contentAlignment = Alignment.TopCenter) {
        Surface(modifier = Modifier.padding(top = 24.dp), color = Color.Red, shape = RoundedCornerShape(16.dp), shadowElevation = 4.dp) {
            Text("RECONNECTING...", modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 1.sp, fontSize = 14.sp)
        }
    }
}

