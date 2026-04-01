package com.dqmp.app.display.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dqmp.app.display.R
import com.dqmp.app.display.model.DisplaySettings
import com.dqmp.app.display.model.QueueItem
import com.dqmp.app.display.model.TokenCall
import com.dqmp.app.display.ui.theme.ThemeProvider
import com.dqmp.app.display.viewmodel.OutletDisplayViewModel
import kotlinx.coroutines.delay

@Composable
fun OutletDisplayScreen(
    outletId: String,
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: OutletDisplayViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val settings = uiState.settings
    val theme = ThemeProvider.getTheme(settings.theme)
    
    // Initialize connection
    LaunchedEffect(outletId) {
        viewModel.connectToOutlet(outletId)
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(theme.background, theme.surface),
                    startY = 0f,
                    endY = Float.POSITIVE_INFINITY
                )
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header
            HeaderSection(
                outletName = uiState.outletName,
                connectionStatus = uiState.connectionStatus,
                onSettingsClick = onSettingsClick,
                theme = theme,
                modifier = Modifier.padding(24.dp)
            )
            
            // Current Token Display
            if (uiState.currentTokenCall != null) {
                CurrentTokenSection(
                    tokenCall = uiState.currentTokenCall!!,
                    theme = theme,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
            
            // Queue Display
            QueueSection(
                queueItems = uiState.queueItems,
                showQueueNumbers = settings.showQueueNumbers,
                theme = theme,
                modifier = Modifier
                    .weight(1f)
                    .padding(24.dp)
            )
            
            // Footer
            FooterSection(
                theme = theme,
                modifier = Modifier.padding(24.dp)
            )
        }
        
        // Connection indicator
        if (uiState.connectionStatus != "Connected") {
            ConnectionStatusOverlay(
                status = uiState.connectionStatus,
                theme = theme
            )
        }
    }
}

@Composable
private fun HeaderSection(
    outletName: String,
    connectionStatus: String,
    onSettingsClick: () -> Unit,
    theme: com.dqmp.app.display.ui.theme.AppTheme,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = stringResource(R.string.outlet_display_title),
                color = theme.text,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = outletName,
                color = theme.textSecondary,
                fontSize = 24.sp
            )
        }
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onSettingsClick,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = theme.surface.copy(alpha = 0.8f)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = theme.text
                )
            }
            
            ConnectionStatusIndicator(
                status = connectionStatus,
                theme = theme
            )
        }
    }
}

@Composable
private fun ConnectionStatusIndicator(
    status: String,
    theme: com.dqmp.app.display.ui.theme.AppTheme
) {
    val (color, text) = when (status) {
        "Connected" -> Pair(Color.Green, stringResource(R.string.status_connected))
        "Connecting" -> Pair(Color.Yellow, stringResource(R.string.status_connecting))
        "Error" -> Pair(Color.Red, stringResource(R.string.status_error))
        else -> Pair(Color.Gray, stringResource(R.string.status_disconnected))
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, CircleShape)
        )
        Text(
            text = text,
            color = theme.textSecondary,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun CurrentTokenSection(
    tokenCall: com.dqmp.app.display.model.TokenCall,
    theme: com.dqmp.app.display.ui.theme.AppTheme,
    modifier: Modifier = Modifier
) {
    // Animated current token display
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = theme.primary.copy(alpha = alpha)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.now_serving),
                color = theme.text,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = tokenCall.tokenNumber,
                color = theme.text,
                fontSize = 72.sp,
                fontWeight = FontWeight.ExtraBold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = stringResource(R.string.proceed_to_counter, tokenCall.counterName),
                color = theme.text,
                fontSize = 24.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun QueueSection(
    queueItems: List<QueueItem>,
    showQueueNumbers: Boolean,
    theme: com.dqmp.app.display.ui.theme.AppTheme,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(
            containerColor = theme.surface.copy(alpha = 0.8f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.queue_waiting),
                color = theme.text,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            if (queueItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_queue_items),
                        color = theme.textSecondary,
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = queueItems,
                        key = { it.id }
                    ) { item ->
                        AnimatedVisibility(
                            visible = true,
                            enter = slideInVertically() + fadeIn(),
                            exit = slideOutVertically() + fadeOut()
                        ) {
                            QueueItemCard(
                                item = item,
                                showQueueNumbers = showQueueNumbers,
                                theme = theme
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueItemCard(
    item: QueueItem,
    showQueueNumbers: Boolean,
    theme: com.dqmp.app.display.ui.theme.AppTheme
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = theme.accent.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = theme.background.copy(alpha = 0.6f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.tokenNumber,
                color = theme.text,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            
            if (showQueueNumbers) {
                Text(
                    text = stringResource(R.string.status_waiting),
                    color = theme.textSecondary,
                    fontSize = 18.sp
                )
            }
        }
    }
}

@Composable
private fun FooterSection(
    theme: com.dqmp.app.display.ui.theme.AppTheme,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.powered_by_dqmp),
            color = theme.textSecondary.copy(alpha = 0.7f),
            fontSize = 16.sp
        )
    }
}

@Composable
private fun ConnectionStatusOverlay(
    status: String,
    theme: com.dqmp.app.display.ui.theme.AppTheme
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = theme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    color = theme.accent,
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(48.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = when (status) {
                        "Connecting" -> stringResource(R.string.connecting_to_server)
                        "Error" -> stringResource(R.string.connection_error)
                        else -> stringResource(R.string.reconnecting)
                    },
                    color = theme.text,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}