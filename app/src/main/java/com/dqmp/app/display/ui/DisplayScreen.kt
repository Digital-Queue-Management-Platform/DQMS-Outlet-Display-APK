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
            .background(Slate900)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            // Top: Branch Name and HUGE Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        data.outletMeta?.name ?: "Outlet Display",
                        color = Color.White,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        data.outletMeta?.location?.uppercase() ?: "BRANCH ACTIVE",
                        color = Sky400,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    
                    if (isStale) {
                        Spacer(Modifier.height(8.dp))
                        Surface(color = Color.Red, shape = RoundedCornerShape(8.dp)) {
                            Text("OFFLINE (SINCE ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastSync))})", 
                                color = Color.White, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), 
                                fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Prominent Date and Time
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(currentTime),
                        color = Emerald500,
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(currentTime),
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Middle: NOW SERVING (Huge Cards)
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SectionHeader("Now Serving", Emerald500)
                Spacer(Modifier.height(16.dp))
                
                if (data.inService.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(Slate800.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        Text("No active calls at the moment.", color = Slate500, style = MaterialTheme.typography.headlineLarge)
                    }
                } else {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        data.inService.take(2).forEach { token ->
                            ServingTokenCard(token, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Bottom: UP NEXT Marquee
            Column {
                SectionHeader("Up Next", Sky400)
                Spacer(Modifier.height(16.dp))
                UpNextMarquee(data.waiting)
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Footer
            Footer()
        }

        if (isStale) SyncOverlay(lastSync)
    }
}

@Composable
fun SectionHeader(title: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp, 32.dp).background(color, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(16.dp))
        Text(title.uppercase(), style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
    }
}

@Composable
fun ServingTokenCard(token: Token, modifier: Modifier) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse)
    )

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer { scaleX = pulse; scaleY = pulse }
            .border(4.dp, Emerald400.copy(alpha = 0.5f), RoundedCornerShape(48.dp)),
        color = Color(0xFF1E293B), // Explicit Slate800
        shape = RoundedCornerShape(48.dp)
    ) {
        Column(
            modifier = Modifier.padding(48.dp).fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(token.customer?.name ?: "NOW SERVING", color = Emerald400, fontSize = 28.sp, fontWeight = FontWeight.Black)
            
            Spacer(Modifier.height(16.dp))
            
            Text(
                token.tokenNumber.toString().padStart(3, '0'),
                fontSize = 160.sp,
                color = Color.White,
                fontWeight = FontWeight.Black,
                lineHeight = 160.sp
            )
            
            Spacer(Modifier.height(16.dp))

            Surface(color = Emerald500, shape = RoundedCornerShape(12.dp)) {
                Text(
                    "COUNTER ${token.counterNumber ?: "?"}", 
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    color = Color.White, 
                    fontSize = 32.sp, 
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
fun UpNextMarquee(tokens: List<Token>) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(tokens) { token ->
            Surface(
                color = Slate800,
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(2.dp, Slate700),
                modifier = Modifier.width(220.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(token.tokenNumber.toString().padStart(3, '0'), color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Black)
                    Text("WAITING", color = Sky400, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun Footer() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Image(painter = painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.height(56.dp))
        Spacer(Modifier.width(32.dp)); Box(Modifier.width(2.dp).height(56.dp).background(Slate700)); Spacer(Modifier.width(32.dp))
        Column {
            Text("Digital Queue Management Platform", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Color.White)
            Text("Powered by SLT-Mobitel Digital Operations Group", fontSize = 16.sp, color = Slate500)
        }
    }
}

@Composable
fun NoticeBar(notice: Notice, isCritical: Boolean) {
    val bg = if (isCritical) Color(0x66FF0000) else Color(0x66FFA500)
    val color = if (isCritical) Color.Red else Color(0xFFFFA500)
    
    Surface(color = bg, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(2.dp, color.copy(alpha = 0.8f))) {
        Row(modifier = Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = color, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(24.dp))
            Text(notice.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
