package com.dqmp.app.display.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dqmp.app.display.data.*
import com.dqmp.app.display.ui.theme.*

@Composable
fun NowServingSection(
    modifier: Modifier = Modifier,
    tokens: List<Token>,
    showServices: Boolean = true,
    autoSlide: Boolean = true
) {
    SectionCard(
        modifier = modifier,
        title = "Now Serving",
        icon = Icons.Default.Notifications,
        iconColor = Emerald500,
        isEmpty = tokens.isEmpty(),
        emptyMessage = "No customers being served right now."
    ) {
        if (tokens.isNotEmpty()) {
            EnhancedAutoScrollingRow(
                items = tokens,
                autoSlide = autoSlide,
                slideInterval = 4000L
            ) { token ->
                ServingTokenCard(
                    token = token,
                    showServices = showServices,
                    modifier = Modifier.width(280.dp)
                )
            }
        }
    }
}

@Composable
fun UpNextSection(
    modifier: Modifier = Modifier,
    tokens: List<Token>,
    showServices: Boolean = true,
    autoSlide: Boolean = true
) {
    SectionCard(
        modifier = modifier,
        title = "Up Next",
        icon = Icons.Default.List,
        iconColor = Sky400,
        isEmpty = tokens.isEmpty(),
        emptyMessage = "No waiting tokens right now."
    ) {
        if (tokens.isNotEmpty()) {
            EnhancedAutoScrollingRow(
                items = tokens.withIndex().toList(),
                autoSlide = autoSlide,
                slideInterval = 5000L
            ) { (index, token) ->
                WaitingTokenCard(
                    token = token,
                    queuePosition = index + 1,
                    showServices = showServices,
                    modifier = Modifier.width(200.dp)
                )
            }
        }
    }
}

@Composable
fun CounterStatusSection(
    modifier: Modifier = Modifier,
    counters: List<CounterStatus>,
    autoSlide: Boolean = true
) {
    val activeCounters = counters.filter { it.number != null }
    
    SectionCard(
        modifier = modifier,
        title = "Counter Status",
        icon = Icons.Default.Groups,
        iconColor = Emerald600,
        isEmpty = activeCounters.isEmpty(),
        emptyMessage = "No active counters."
    ) {
        if (activeCounters.isNotEmpty()) {
            EnhancedAutoScrollingRow(
                items = activeCounters,
                autoSlide = autoSlide,
                slideInterval = 6000L
            ) { counter ->
                CounterCard(
                    counter = counter,
                    modifier = Modifier.width(220.dp)
                )
            }
        }
    }
}

@Composable
fun RecentCallsSection(
    modifier: Modifier = Modifier,
    recentCalls: List<CalledRecord>,
    autoSlide: Boolean = true
) {
    SectionCard(
        modifier = modifier,
        title = "Recently Called",
        icon = Icons.Default.History,
        iconColor = Color(0xFF6366F1),
        isEmpty = recentCalls.isEmpty(),
        emptyMessage = "No recent calls yet."
    ) {
        if (recentCalls.isNotEmpty()) {
            EnhancedAutoScrollingRow(
                items = recentCalls,
                autoSlide = autoSlide,
                slideInterval = 4500L
            ) { call ->
                RecentCallCard(
                    call = call,
                    modifier = Modifier.width(160.dp)
                )
            }
        }
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String,
    icon: ImageVector,
    iconColor: Color,
    isEmpty: Boolean,
    emptyMessage: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Section Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    color = Slate900,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Content
            if (isEmpty) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = Slate300,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = emptyMessage,
                            color = Slate500,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    content()
                }
            }
        }
    }
}

@Composable
fun <T> EnhancedAutoScrollingRow(
    items: List<T>,
    modifier: Modifier = Modifier,
    autoSlide: Boolean = true,
    slideInterval: Long = 4000L,
    contentPadding: PaddingValues = PaddingValues(horizontal = 8.dp),
    itemContent: @Composable (T) -> Unit
) {
    val listState = rememberLazyListState()
    
    // Auto-scrolling effect
    LaunchedEffect(items, autoSlide) {
        if (items.size > 1 && autoSlide) {
            var currentIndex = 0
            while (true) {
                kotlinx.coroutines.delay(slideInterval)
                currentIndex = (currentIndex + 1) % items.size
                listState.animateScrollToItem(currentIndex)
            }
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = contentPadding,
        content = {
            items(items) { item ->
                itemContent(item)
            }
        }
    )
}

@Composable
fun ServingTokenCard(
    token: Token,
    showServices: Boolean = true,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            tween(2000, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        )
    )

    Card(
        modifier = modifier.graphicsLayer {
            scaleX = pulse
            scaleY = pulse
        },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Emerald50),
        border = androidx.compose.foundation.BorderStroke(2.dp, Emerald200)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Counter and Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Emerald600)
                ) {
                    Text(
                        text = "Counter ${token.counterNumber ?: "?"}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Emerald500, CircleShape)
                    )
                    Text(
                        text = "SERVING",
                        color = Emerald700,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Service Type Badge
            if (showServices && token.serviceTypes.isNotEmpty()) {
                ServiceTypeBadge(
                    serviceType = token.serviceTypes[0],
                    color = Emerald100,
                    textColor = Emerald700
                )
            }

            // Token Number (Large)
            Text(
                text = token.tokenNumber.toString().padStart(3, '0'),
                color = Slate900,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // Customer Name (if available)
            if (token.customer?.name?.isNotEmpty() == true) {
                Text(
                    text = token.customer.name,
                    color = Slate600,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun WaitingTokenCard(
    token: Token,
    queuePosition: Int,
    showServices: Boolean = true,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Slate50),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header: Queue Position and Service Type
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Queue #$queuePosition",
                    color = Slate700,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )

                if (showServices && token.serviceTypes.isNotEmpty()) {
                    ServiceTypeBadge(
                        serviceType = token.serviceTypes[0],
                        color = Sky100,
                        textColor = Sky700
                    )
                }
            }

            // Token Number
            Text(
                text = token.tokenNumber.toString().padStart(3, '0'),
                color = Slate900,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun CounterCard(
    counter: CounterStatus,
    modifier: Modifier = Modifier
) {
    val officer = counter.officer
    val status = officer?.status ?: "offline"
    
    val (bgColor, borderColor, statusColor, statusText, statusIcon) = when {
        !counter.isStaffed || status == "offline" -> 
            listOf(Slate50, Slate200, Slate500, "OFFLINE", Icons.Default.PersonOff)
        status == "serving" -> 
            listOf(Sky50, Sky200, Sky600, "SERVING", Icons.Default.Person)
        status == "on_break" -> 
            listOf(Color(0xFFFEF3C7), Color(0xFFF59E0B), Color(0xFFD97706), "ON BREAK", Icons.Default.Coffee)
        else -> 
            listOf(Emerald50, Emerald200, Emerald600, "AVAILABLE", Icons.Default.CheckCircle)
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor as Color),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor as Color)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Counter Number Circle
            Card(
                shape = CircleShape,
                colors = CardDefaults.cardColors(containerColor = statusColor as Color)
            ) {
                Text(
                    text = counter.number.toString(),
                    modifier = Modifier.padding(12.dp),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }

            // Counter Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Counter #${counter.number}",
                    color = Slate700,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .background(statusColor as Color, CircleShape)
                    )
                    Text(
                        text = statusText as String,
                        color = statusColor,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Officer Name
                if (officer?.name?.isNotEmpty() == true) {
                    Text(
                        text = officer.name,
                        color = Slate600,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Status Icon
            Icon(
                statusIcon as ImageVector,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun RecentCallCard(
    call: CalledRecord,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Token Number
            Text(
                text = call.tokenNumber.toString().padStart(3, '0'),
                color = Slate900,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black
            )

            // Counter Info
            Text(
                text = if (call.counterNumber != null) "Counter #${call.counterNumber}" else "Staff Station",
                color = Slate600,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ServiceTypeBadge(
    serviceType: String,
    color: Color,
    textColor: Color
) {
    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = color),
        border = androidx.compose.foundation.BorderStroke(1.dp, textColor.copy(alpha = 0.3f))
    ) {
        Text(
            text = getServiceDisplayName(serviceType),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = textColor,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// Helper function to map service types to display names
fun getServiceDisplayName(serviceType: String): String {
    return when (serviceType.lowercase()) {
        "internet" -> "Internet"
        "mobile" -> "Mobile"
        "tv" -> "TV"
        "landline" -> "Landline"
        "payment" -> "Payment"
        "technical" -> "Technical"
        "billing" -> "Billing"
        "general" -> "General"
        else -> serviceType.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}