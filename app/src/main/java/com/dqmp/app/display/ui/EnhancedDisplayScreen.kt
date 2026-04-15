package com.dqmp.app.display.ui

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dqmp.app.display.R
import com.dqmp.app.display.data.BranchStatusResponse
import com.dqmp.app.display.data.DisplayData
import com.dqmp.app.display.data.Notice
import com.dqmp.app.display.data.Token
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val WebDisplayFont = FontFamily.SansSerif

private fun isDirectMp4Url(url: String): Boolean {
    val lower = url.lowercase(Locale.US)
    return (lower.startsWith("http://") || lower.startsWith("https://")) &&
        (lower.contains(".mp4") || lower.contains(".mp4?"))
}

private fun promoCacheFileForUrl(context: android.content.Context, url: String): File {
    val safeName = url.hashCode().toUInt().toString(16)
    return File(File(context.cacheDir, "promo-video-cache"), "$safeName.mp4")
}

private fun downloadPromoVideoToCache(context: android.content.Context, url: String): File? {
    val cacheFile = promoCacheFileForUrl(context, url)
    if (cacheFile.exists() && cacheFile.length() > 0L) return cacheFile

    cacheFile.parentFile?.mkdirs()

    return try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }

        connection.connect()

        if (connection.responseCode !in 200..299) {
            connection.disconnect()
            return null
        }

        connection.inputStream.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }

        connection.disconnect()
        if (cacheFile.exists() && cacheFile.length() > 0L) cacheFile else null
    } catch (_: Exception) {
        if (cacheFile.exists()) cacheFile.delete()
        null
    }
}

private fun String.toWebCapitalize(): String {
    return split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { part ->
            part.lowercase().replaceFirstChar { c ->
                if (c.isLowerCase()) c.titlecase(Locale.getDefault()) else c.toString()
            }
        }
}

@Composable
fun EnhancedDisplayScreen(
    data: DisplayData,
    counters: List<com.dqmp.app.display.data.CounterStatus>,
    branchStatus: BranchStatusResponse,
    isStale: Boolean = false,
    lastSync: Long = 0L,
    clockSkew: Long = 0L
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val settings = remember(data.displaySettings) { data.displaySettings }
        val zoomScale = remember(settings?.contentScale) { (settings?.contentScale ?: 100).toFloat() / 100f }
        val responsiveScale = remember(maxWidth, maxHeight, zoomScale) {
            val widthScale = maxWidth.value / 1920f
            val heightScale = maxHeight.value / 1080f
            minOf(widthScale, heightScale).coerceIn(0.72f, 1.18f) * zoomScale
        }

        var currentTime by remember { mutableStateOf(Date(System.currentTimeMillis() + clockSkew)) }
        val slTimeZone = remember { TimeZone.getTimeZone("GMT+05:30") }
        val timeFormat = remember {
            SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).apply { timeZone = slTimeZone }
        }
        val dateFormat = remember {
            SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).apply { timeZone = slTimeZone }
        }

        LaunchedEffect(clockSkew) {
            while (true) {
                currentTime = Date(System.currentTimeMillis() + clockSkew)
                kotlinx.coroutines.delay(1000)
            }
        }

        val servingByCounter = remember(data.inService) {
            data.inService
                .take(4)
                .sortedBy { it.counterNumber ?: Int.MAX_VALUE }
        }
        val upNext = remember(data.waiting) { data.waiting.take(6) }

        val hasNowServing = servingByCounter.isNotEmpty()
        val leftTopWeight = if (hasNowServing) 6f else 0f
        val leftVideoWeight = if (hasNowServing) 4f else 10f

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF8FAFC))
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x08003366), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(150f, 120f),
                        radius = 1000f
                    )
                )
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0x0D0EA5E9), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(1800f, 900f),
                        radius = 1000f
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = 1f,
                        scaleY = 1f,
                        transformOrigin = TransformOrigin(0f, 0f)
                    )
                    .padding(responsiveDp(24.dp, responsiveScale))
            ) {
                WebHeader(
                    outletName = (data.outletMeta?.name ?: "Sri Lanka Telecom").toWebCapitalize(),
                    location = data.outletMeta?.location ?: "Outlet Service Portal",
                    now = currentTime,
                    timeFormat = timeFormat,
                    dateFormat = dateFormat,
                    scale = responsiveScale
                )

                Spacer(modifier = Modifier.height(responsiveDp(12.dp, responsiveScale)))

                val notice = branchStatus.activeNotice ?: branchStatus.standardNotice
                if (notice != null) {
                    NoticeBar(notice = notice, scale = responsiveScale)
                    Spacer(modifier = Modifier.height(responsiveDp(8.dp, responsiveScale)))
                }

                if (isStale) {
                    ErrorStrip(message = "Connection lost. Data may be outdated.", scale = responsiveScale)
                    Spacer(modifier = Modifier.height(responsiveDp(8.dp, responsiveScale)))
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(responsiveDp(16.dp, responsiveScale))
                ) {
                    Column(
                        modifier = Modifier.weight(7f),
                        verticalArrangement = Arrangement.spacedBy(responsiveDp(12.dp, responsiveScale))
                    ) {
                        if (hasNowServing) {
                            NowServingPanel(
                                modifier = Modifier.weight(leftTopWeight),
                                tokens = servingByCounter,
                                scale = responsiveScale
                            )
                        }

                        PromoVideoPanel(
                            modifier = Modifier.weight(leftVideoWeight),
                            videoId = settings?.videoId ?: "Iea84C32YHA",
                            scale = responsiveScale
                        )
                    }

                    UpNextSidebar(
                        modifier = Modifier.weight(3f),
                        tokens = upNext,
                        showService = settings?.services ?: false,
                        totalWaiting = data.totalWaiting,
                        totalServing = data.inService.size,
                        totalCounters = data.availableOfficers,
                        scale = responsiveScale
                    )
                }
            }
        }
    }
}

private fun responsiveDp(base: androidx.compose.ui.unit.Dp, scale: Float): androidx.compose.ui.unit.Dp {
    return (base.value * scale).dp
}

private fun responsiveSp(base: Int, scale: Float): androidx.compose.ui.unit.TextUnit {
    return (base * scale).sp
}

@Composable
private fun WebHeader(
    outletName: String,
    location: String,
    now: Date,
    timeFormat: SimpleDateFormat,
    dateFormat: SimpleDateFormat,
    scale: Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(horizontal = responsiveDp(24.dp, scale), vertical = responsiveDp(12.dp, scale))
            .height(responsiveDp(110.dp, scale)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(responsiveDp(16.dp, scale))
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "Logo",
                modifier = Modifier.height(responsiveDp(84.dp, scale))
            )
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = outletName,
                    color = Color(0xFF1E1B4B),
                    fontSize = responsiveSp(72, scale),
                    fontWeight = FontWeight.Bold,
                    fontFamily = WebDisplayFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(responsiveDp(24.dp, scale))
                            .height(responsiveDp(2.dp, scale))
                            .background(Color(0xFF4F46E5), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(responsiveDp(10.dp, scale)))
                    Text(
                        text = location.uppercase(),
                        color = Color(0xFF4F46E5),
                        fontSize = responsiveSp(18, scale),
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = WebDisplayFont,
                        letterSpacing = 2.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(responsiveDp(8.dp, scale))
        ) {
            Row(
                modifier = Modifier
                    .background(Color(0xFFECFDF5), RoundedCornerShape(999.dp))
                    .padding(horizontal = responsiveDp(14.dp, scale), vertical = responsiveDp(6.dp, scale)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(responsiveDp(8.dp, scale))
            ) {
                Box(
                    modifier = Modifier
                        .size(responsiveDp(8.dp, scale))
                        .background(Color(0xFF10B981), CircleShape)
                )
                Text(
                    text = "LIVE SYNC",
                    color = Color(0xFF047857),
                    fontSize = responsiveSp(12, scale),
                    fontWeight = FontWeight.Bold,
                    fontFamily = WebDisplayFont,
                    letterSpacing = 1.sp
                )
            }

            Card(
                shape = RoundedCornerShape(responsiveDp(18.dp, scale)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = responsiveDp(20.dp, scale), vertical = responsiveDp(10.dp, scale)),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = timeFormat.format(now),
                        color = Color.White,
                        fontSize = responsiveSp(60, scale),
                        fontWeight = FontWeight.Bold,
                        fontFamily = WebDisplayFont,
                        letterSpacing = (-1).sp
                    )
                    Text(
                        text = dateFormat.format(now),
                        color = Color(0xFFC7D2FE),
                        fontSize = responsiveSp(18, scale),
                        fontWeight = FontWeight.Medium,
                        fontFamily = WebDisplayFont
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(responsiveDp(4.dp, scale))
            .background(Color(0xFF003366), RoundedCornerShape(999.dp))
    )
}

@Composable
private fun NoticeBar(notice: Notice, scale: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFFBEB), RoundedCornerShape(16.dp))
            .padding(horizontal = responsiveDp(12.dp, scale), vertical = responsiveDp(10.dp, scale)),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(responsiveDp(10.dp, scale))
    ) {
        Icon(
            imageVector = Icons.Default.WarningAmber,
            contentDescription = null,
            tint = Color(0xFFD97706),
            modifier = Modifier.size(responsiveDp(20.dp, scale))
        )
        Column {
            Text(
                text = notice.title,
                color = Color(0xFF92400E),
                fontSize = responsiveSp(13, scale),
                fontWeight = FontWeight.Bold,
                fontFamily = WebDisplayFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (notice.message.isNotBlank()) {
                Text(
                    text = notice.message,
                    color = Color(0xFFB45309),
                    fontSize = responsiveSp(11, scale),
                    fontWeight = FontWeight.Medium,
                    fontFamily = WebDisplayFont,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ErrorStrip(message: String, scale: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFEF2F2), RoundedCornerShape(16.dp))
            .padding(horizontal = responsiveDp(12.dp, scale), vertical = responsiveDp(8.dp, scale)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(responsiveDp(8.dp, scale))
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = Color(0xFFDC2626),
            modifier = Modifier.size(responsiveDp(16.dp, scale))
        )
        Text(
            text = message,
            color = Color(0xFFB91C1C),
            fontSize = responsiveSp(12, scale),
            fontWeight = FontWeight.SemiBold,
            fontFamily = WebDisplayFont
        )
    }
}

@Composable
private fun NowServingPanel(
    modifier: Modifier,
    tokens: List<Token>,
    scale: Float
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(responsiveDp(40.dp, scale)),
        border = BorderStroke(responsiveDp(4.dp, scale), Color(0xFFF1F5F9)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = responsiveDp(8.dp, scale))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(responsiveDp(24.dp, scale))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(responsiveDp(10.dp, scale))
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = Color(0xFF6366F1),
                    modifier = Modifier.size(responsiveDp(34.dp, scale))
                )
                Text(
                    text = "Now Serving",
                    color = Color(0xFF1F2937),
                    fontSize = responsiveSp(36, scale),
                    fontWeight = FontWeight.Bold,
                    fontFamily = WebDisplayFont
                )
            }

            Spacer(modifier = Modifier.height(responsiveDp(12.dp, scale)))

            if (tokens.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFF8FAFC), RoundedCornerShape(responsiveDp(20.dp, scale))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No active tokens",
                        color = Color(0xFF94A3B8),
                        fontSize = responsiveSp(24, scale),
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                val columns = when (tokens.size) {
                    1 -> 1
                    2 -> 2
                    else -> 4
                }

                if (columns == 4) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(responsiveDp(16.dp, scale))
                    ) {
                        tokens.take(4).forEach { token ->
                            NowServingCard(
                                modifier = Modifier.weight(1f),
                                token = token,
                                scale = scale
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(responsiveDp(16.dp, scale))
                    ) {
                        tokens.take(columns).forEach { token ->
                            NowServingCard(
                                modifier = Modifier.weight(1f),
                                token = token,
                                scale = scale
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowServingCard(
    modifier: Modifier,
    token: Token,
    scale: Float
) {
    Card(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(responsiveDp(24.dp, scale)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = responsiveDp(8.dp, scale))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF1E1B4B), Color(0xFF312E81))
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .weight(6f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Token",
                    color = Color(0xFFA5B4FC),
                    fontSize = responsiveSp(12, scale),
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = WebDisplayFont,
                    letterSpacing = responsiveSp(1, scale)
                )
                Text(
                    text = token.tokenNumber.toString(),
                    color = Color.White,
                    fontSize = responsiveSp(74, scale),
                    fontWeight = FontWeight.Black,
                    fontFamily = WebDisplayFont,
                    letterSpacing = responsiveSp(-1, scale)
                )
            }

            Row(
                modifier = Modifier
                    .weight(4f)
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.06f)),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Counter ${token.counterNumber ?: 0}",
                    color = Color(0xFFFACC15),
                    fontSize = responsiveSp(36, scale),
                    fontWeight = FontWeight.Black,
                    fontFamily = WebDisplayFont
                )
            }
        }
    }
}

@Composable
private fun PromoVideoPanel(
    modifier: Modifier,
    videoId: String,
    scale: Float
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(responsiveDp(40.dp, scale)),
        border = BorderStroke(responsiveDp(4.dp, scale), Color(0xFFF1F5F9)),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        elevation = CardDefaults.cardElevation(defaultElevation = responsiveDp(8.dp, scale))
    ) {
        if (videoId.isBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F172A)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(responsiveDp(56.dp, scale))
                    )
                    Spacer(modifier = Modifier.height(responsiveDp(10.dp, scale)))
                    Text(
                        text = "No promotion video configured",
                        color = Color(0xFF64748B),
                        fontSize = responsiveSp(14, scale),
                        fontWeight = FontWeight.Medium,
                        fontFamily = WebDisplayFont
                    )
                }
            }
        } else {
            val fallbackLoopUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"

            val configuredPlaylist = remember(videoId) { parsePromoMediaUrls(videoId) }
            val playlist = remember(configuredPlaylist) {
                if (configuredPlaylist.isNotEmpty()) configuredPlaylist else listOf(fallbackLoopUrl)
            }

            Box(modifier = Modifier.fillMaxSize()) {
                PlaylistVideoPlayer(
                    mediaUrls = playlist,
                    scale = scale
                )

                if (configuredPlaylist.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(vertical = responsiveDp(8.dp, scale), horizontal = responsiveDp(12.dp, scale))
                    ) {
                        Text(
                            text = "No valid direct media URL configured. Playing fallback promo video.",
                            color = Color(0xFFE2E8F0),
                            fontSize = responsiveSp(11, scale),
                            fontFamily = WebDisplayFont,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

    }
}

@Composable
private fun PlaylistVideoPlayer(
    mediaUrls: List<String>,
    scale: Float
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val safeUrls = remember(mediaUrls) {
        mediaUrls.map { it.trim() }.filter { it.isNotEmpty() }
    }
    var resolvedUrls by remember(safeUrls) { mutableStateOf(safeUrls) }

    LaunchedEffect(safeUrls) {
        val firstUrl = safeUrls.firstOrNull()
        if (safeUrls.size == 1 && firstUrl != null && isDirectMp4Url(firstUrl)) {
            val cachedFile = withContext(Dispatchers.IO) {
                downloadPromoVideoToCache(appContext, firstUrl)
            }

            resolvedUrls = if (cachedFile != null) {
                listOf(Uri.fromFile(cachedFile).toString())
            } else {
                safeUrls
            }
        } else {
            resolvedUrls = safeUrls
        }
    }

    val player = remember(resolvedUrls) {
        ExoPlayer.Builder(appContext)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        1000,
                        5000,
                        250,
                        500
                    )
                    .build()
            )
            .build().apply {
            setMediaItems(resolvedUrls.map { MediaItem.fromUri(it) })
            repeatMode = if (resolvedUrls.size <= 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
            playWhenReady = true
            volume = 0f
            prepare()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (player.mediaItemCount <= 1) {
                    player.seekTo(0, 0L)
                } else {
                    val next = (player.currentMediaItemIndex + 1) % player.mediaItemCount
                    player.seekTo(next, 0L)
                }
                player.prepare()
                player.playWhenReady = true
            }
        }
        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(responsiveDp(32.dp, scale))),
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                this.player = player
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view ->
            view.player = player
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
    )
}

private fun parsePromoMediaUrls(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()

    return raw
        .split(',', '\n', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { value ->
            val v = value.lowercase(Locale.US)
            val isHttp = v.startsWith("http://") || v.startsWith("https://")
            val isDirect =
                v.contains(".m3u8") ||
                    v.contains(".mpd") ||
                    v.contains(".mp4") ||
                    v.contains(".webm")

            if (isHttp && isDirect) value else null
        }
        .distinct()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpNextSidebar(
    modifier: Modifier,
    tokens: List<Token>,
    showService: Boolean,
    totalWaiting: Int,
    totalServing: Int,
    totalCounters: Int,
    scale: Float
) {
    Card(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(responsiveDp(48.dp, scale)),
        border = BorderStroke(responsiveDp(4.dp, scale), Color(0x1F1E3A8A)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        elevation = CardDefaults.cardElevation(defaultElevation = responsiveDp(12.dp, scale))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x801E3A8A))
                    .padding(horizontal = responsiveDp(24.dp, scale), vertical = responsiveDp(20.dp, scale)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(responsiveDp(10.dp, scale))
            ) {
                Icon(
                    imageVector = Icons.Default.ConfirmationNumber,
                    contentDescription = null,
                    tint = Color(0xFFC7D2FE),
                    modifier = Modifier.size(responsiveDp(32.dp, scale))
                )
                Text(
                    text = "Up Next",
                    color = Color.White,
                    fontSize = responsiveSp(36, scale),
                    fontWeight = FontWeight.Bold,
                    fontFamily = WebDisplayFont
                )
            }

            if (tokens.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No pending tokens",
                        color = Color(0xFF64748B),
                        fontSize = responsiveSp(24, scale),
                        fontWeight = FontWeight.Medium,
                        fontFamily = WebDisplayFont
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = responsiveDp(18.dp, scale), vertical = responsiveDp(16.dp, scale)),
                    verticalArrangement = Arrangement.spacedBy(responsiveDp(12.dp, scale))
                ) {
                    itemsIndexed(tokens, key = { _, token -> token.id }) { idx, token ->
                        val highlighted = idx == 0
                        Card(
                            shape = RoundedCornerShape(responsiveDp(28.dp, scale)),
                            colors = CardDefaults.cardColors(
                                containerColor = if (highlighted) Color.White else Color(0xFF002244)
                            ),
                            border = BorderStroke(
                                responsiveDp(2.dp, scale),
                                if (highlighted) Color(0xFF0EA5E9) else Color.White.copy(alpha = 0.1f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = if (highlighted) responsiveDp(4.dp, scale) else 0.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(responsiveDp(100.dp, scale))
                                    .padding(horizontal = responsiveDp(16.dp, scale)),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(responsiveDp(16.dp, scale))
                                ) {
                                    Text(
                                        text = token.tokenNumber.toString(),
                                        color = if (highlighted) Color(0xFF1E1B4B) else Color(0xFFF1F5F9),
                                        fontSize = responsiveSp(60, scale),
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = WebDisplayFont
                                    )
                                    Column {
                                        Text(
                                            text = "Queue Position: ${idx + 1}",
                                            color = if (highlighted) Color(0xFF475569) else Color(0xFF94A3B8),
                                            fontSize = responsiveSp(20, scale),
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = WebDisplayFont
                                        )
                                        if (showService && token.serviceTypes.isNotEmpty()) {
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(responsiveDp(6.dp, scale))) {
                                                token.serviceTypes.forEach { service ->
                                                    Text(
                                                        text = service,
                                                        color = if (highlighted) Color(0xFF4F46E5) else Color(0xFF818CF8),
                                                        fontSize = responsiveSp(20, scale),
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = WebDisplayFont,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                if (highlighted) {
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0xFF4F46E5), RoundedCornerShape(999.dp))
                                            .padding(horizontal = responsiveDp(12.dp, scale), vertical = responsiveDp(6.dp, scale))
                                    ) {
                                        Text(
                                            text = "Please Prepare",
                                            color = Color.White,
                                            fontSize = responsiveSp(12, scale),
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = WebDisplayFont,
                                            letterSpacing = 0.5.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.05f))
                    .padding(responsiveDp(14.dp, scale)),
                horizontalArrangement = Arrangement.spacedBy(responsiveDp(10.dp, scale))
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Waiting",
                    value = totalWaiting.toString(),
                    bg = Color(0x332567B2),
                    titleColor = Color(0xFFA5B4FC)
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Serving",
                    value = totalServing.toString(),
                    bg = Color(0x3310B981),
                    titleColor = Color(0xFF6EE7B7)
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Counters",
                    value = totalCounters.toString(),
                    bg = Color(0x331D4ED8),
                    titleColor = Color(0xFF93C5FD)
                )
            }
        }
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier,
    title: String,
    value: String,
    bg: Color,
    titleColor: Color,
    scale: Float = 1f
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(responsiveDp(16.dp, scale)),
        colors = CardDefaults.cardColors(containerColor = bg),
        border = BorderStroke(responsiveDp(1.dp, scale), titleColor.copy(alpha = 0.2f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = responsiveDp(10.dp, scale)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title.uppercase(),
                color = titleColor,
                fontSize = responsiveSp(10, scale),
                fontWeight = FontWeight.SemiBold,
                fontFamily = WebDisplayFont,
                letterSpacing = responsiveSp(1, scale)
            )
            Text(
                text = value,
                color = Color.White,
                fontSize = responsiveSp(36, scale),
                fontWeight = FontWeight.Bold,
                fontFamily = WebDisplayFont,
                textAlign = TextAlign.Center
            )
        }
    }
}
