/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.feature.settings

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.net.TrafficStats
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.annotation.DelicateCoilApi
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.directory
import coil3.imageLoader
import com.rift.music.LocalDatabase
import com.rift.music.LocalPlayerAwareWindowInsets
import com.rift.music.LocalPlayerConnection
import com.rift.music.R
import com.rift.music.core.extensions.tryOrNull
import com.rift.music.ui.component.ActionPromptDialog
import com.rift.music.ui.component.IconButton
import com.rift.music.ui.component.Material3SettingsGroup
import com.rift.music.ui.component.Material3SettingsItem
import com.rift.music.ui.theme.AppleTokens
import com.rift.music.ui.utils.appTopBarWindowInsets
import com.rift.music.ui.utils.backToMain
import com.rift.music.ui.utils.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

// Baseline network counters captured once when the process loads this class
private val appStartRxBytes = TrafficStats.getUidRxBytes(Process.myUid()).let {
    if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it.coerceAtLeast(0L)
}
private val appStartTxBytes = TrafficStats.getUidTxBytes(Process.myUid()).let {
    if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it.coerceAtLeast(0L)
}

/** Active telemetry metric displayed on the live graph. */
enum class UsageGraphTab(val label: String, val iconRes: Int) {
    CPU("CPU", R.drawable.speed),
    GPU("GPU", R.drawable.graphic_eq),
    DATA("Data Consumed", R.drawable.network_node),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoilApi::class, DelicateCoilApi::class)
@Composable
fun TrackUsageScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val imageDiskCache = context.imageLoader.diskCache
    val playerCache = LocalPlayerConnection.current?.service?.playerCache
    val downloadCache = LocalPlayerConnection.current?.service?.downloadCache
    val coroutineScope = rememberCoroutineScope()

    // ── Activity and Window Resolution ─────────────────────────────────────────
    val activity = remember(context) { context.findActivity() }
    val window = activity?.window

    val refreshRate = remember(activity) {
        tryOrNull {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity?.display?.mode?.refreshRate ?: 60f
            } else {
                @Suppress("DEPRECATION")
                activity?.windowManager?.defaultDisplay?.refreshRate ?: 60f
            }
        } ?: 60f
    }
    val frameBudgetMs = remember(refreshRate) { 1000f / refreshRate.coerceAtLeast(30f) }
    val frameBudgetNs = remember(frameBudgetMs) { (frameBudgetMs * 1_000_000f).toLong() }

    // ── Telemetry Graph Selected Tab ───────────────────────────────────────────
    var selectedTab by remember { mutableStateOf(UsageGraphTab.CPU) }

    // Rolling history lists (30 samples = ~24 seconds window with smooth 800ms ticks)
    val historySize = 30
    val cpuHistory = remember { mutableStateListOf<Float>().apply { repeat(historySize) { add(0f) } } }
    val gpuHistory = remember { mutableStateListOf<Float>().apply { repeat(historySize) { add(0f) } } }
    val networkHistory = remember { mutableStateListOf<Float>().apply { repeat(historySize) { add(0f) } } }

    // ── Live Metrics State ─────────────────────────────────────────────────────
    val cpuCores = remember { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }
    var currentCpuPercent by remember { mutableFloatStateOf(0f) }
    var peakCpuPercent by remember { mutableFloatStateOf(0f) }

    var currentGpuLoad by remember { mutableFloatStateOf(0f) }
    var currentGpuDurationMs by remember { mutableFloatStateOf(0f) }

    val uid = remember { context.applicationInfo.uid }
    var sessionRx by remember { mutableLongStateOf(0L) }
    var sessionTx by remember { mutableLongStateOf(0L) }
    var currentTransferSpeedBytes by remember { mutableLongStateOf(0L) }
    var peakTransferSpeedBytes by remember { mutableLongStateOf(0L) }

    // ── Storage state ─────────────────────────────────────────────────────────
    var imageCacheSize by remember { mutableLongStateOf(imageDiskCache?.size ?: 0L) }
    var playerCacheSize by remember { mutableLongStateOf(tryOrNull { playerCache?.cacheSpace } ?: 0L) }
    var downloadCacheSize by remember { mutableLongStateOf(tryOrNull { downloadCache?.cacheSpace } ?: 0L) }

    // ── Memory state ──────────────────────────────────────────────────────────
    val activityManager = remember { context.getSystemService(ActivityManager::class.java) }
    var totalRam by remember { mutableLongStateOf(0L) }
    var availRam by remember { mutableLongStateOf(0L) }
    var appPssKb by remember { mutableLongStateOf(0L) }

    val ramProgress by animateFloatAsState(
        targetValue = if (totalRam > 0) {
            ((totalRam - availRam).toFloat() / totalRam.toFloat()).coerceIn(0f, 1f)
        } else 0f,
        label = "ramProgress",
    )

    // ── Background FrameMetrics Handler (Zero Main-Thread Overhead) ───────────
    val accumulatedGpuNs = remember { AtomicLong(0L) }
    val frameCount = remember { AtomicInteger(0) }

    DisposableEffect(window) {
        if (window != null) {
            val handlerThread = HandlerThread("TelemetryFrameMetrics").apply { start() }
            val bgHandler = Handler(handlerThread.looper)
            val frameListener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
                val gpuNs = tryOrNull { frameMetrics.getMetric(FrameMetrics.GPU_DURATION) } ?: 0L
                if (gpuNs > 0) accumulatedGpuNs.addAndGet(gpuNs)
                frameCount.incrementAndGet()
            }
            tryOrNull { window.addOnFrameMetricsAvailableListener(frameListener, bgHandler) }

            onDispose {
                tryOrNull { window.removeOnFrameMetricsAvailableListener(frameListener) }
                tryOrNull { handlerThread.quitSafely() }
            }
        } else {
            onDispose { }
        }
    }

    // ── Live Poller with Exponential Smoothing (EMA) for Silky Transitions ────
    LaunchedEffect(playerCache, downloadCache, imageDiskCache) {
        var prevCpuTime = Process.getElapsedCpuTime()
        var prevRealtime = SystemClock.elapsedRealtime()
        var prevRxBytes = TrafficStats.getUidRxBytes(uid).let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it.coerceAtLeast(0L) }
        var prevTxBytes = TrafficStats.getUidTxBytes(uid).let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it.coerceAtLeast(0L) }
        var smoothedCpu = 0f
        var smoothedGpu = 0f

        while (isActive) {
            withContext(Dispatchers.IO) {
                val nowRealtime = SystemClock.elapsedRealtime()
                val deltaRealtime = (nowRealtime - prevRealtime).coerceAtLeast(1L)
                prevRealtime = nowRealtime

                // 1. CPU Usage Calculation with Exponential Moving Average (EMA)
                val nowCpuTime = Process.getElapsedCpuTime()
                val deltaCpu = (nowCpuTime - prevCpuTime).coerceAtLeast(0L)
                prevCpuTime = nowCpuTime
                val rawCpu = if (deltaRealtime > 0 && cpuCores > 0) {
                    ((deltaCpu.toFloat() / (deltaRealtime.toFloat() * cpuCores)) * 100f).coerceIn(0f, 100f)
                } else 0f
                smoothedCpu = if (smoothedCpu == 0f) rawCpu else (smoothedCpu * 0.65f + rawCpu * 0.35f)

                // 2. GPU / Frame Metrics Calculation with EMA
                val frames = frameCount.getAndSet(0)
                val gpuNs = accumulatedGpuNs.getAndSet(0L)
                val avgGpuMs = if (frames > 0) (gpuNs.toFloat() / (frames * 1_000_000f)) else 0f
                val rawGpuLoad = if (frameBudgetNs > 0 && frames > 0) {
                    ((gpuNs.toFloat() / frames) / frameBudgetNs.toFloat() * 100f).coerceIn(0f, 100f)
                } else 0f
                smoothedGpu = if (smoothedGpu == 0f) rawGpuLoad else (smoothedGpu * 0.70f + rawGpuLoad * 0.30f)

                // 3. Network Throughput & Consumption Calculation
                val rawRx = TrafficStats.getUidRxBytes(uid).let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it.coerceAtLeast(0L) }
                val rawTx = TrafficStats.getUidTxBytes(uid).let { if (it == TrafficStats.UNSUPPORTED.toLong()) 0L else it.coerceAtLeast(0L) }
                val deltaRx = (rawRx - prevRxBytes).coerceAtLeast(0L)
                val deltaTx = (rawTx - prevTxBytes).coerceAtLeast(0L)
                prevRxBytes = rawRx
                prevTxBytes = rawTx

                val transferSpeedBytes = if (deltaRealtime > 0) {
                    ((deltaRx + deltaTx) * 1000L / deltaRealtime)
                } else 0L

                val sRx = (rawRx - appStartRxBytes).coerceAtLeast(0L)
                val sTx = (rawTx - appStartTxBytes).coerceAtLeast(0L)

                // 4. Storage & Memory reads
                val imgSize = imageDiskCache?.size ?: 0L
                val plySize = tryOrNull { playerCache?.cacheSpace } ?: 0L
                val dwnSize = tryOrNull { downloadCache?.cacheSpace } ?: 0L

                var totR = 0L
                var avlR = 0L
                var pss = 0L
                tryOrNull {
                    val memInfo = ActivityManager.MemoryInfo()
                    activityManager?.getMemoryInfo(memInfo)
                    totR = memInfo.totalMem
                    avlR = memInfo.availMem
                    val dbgInfo = Debug.MemoryInfo()
                    Debug.getMemoryInfo(dbgInfo)
                    pss = dbgInfo.totalPss.toLong()
                }

                // 5. Update Compose States on Main Dispatcher
                withContext(Dispatchers.Main) {
                    currentCpuPercent = smoothedCpu
                    peakCpuPercent = max(peakCpuPercent, smoothedCpu)
                    if (cpuHistory.size >= historySize) cpuHistory.removeAt(0)
                    cpuHistory.add(smoothedCpu)

                    currentGpuLoad = smoothedGpu
                    currentGpuDurationMs = avgGpuMs
                    if (gpuHistory.size >= historySize) gpuHistory.removeAt(0)
                    gpuHistory.add(smoothedGpu)

                    val speedKb = transferSpeedBytes / 1024f
                    currentTransferSpeedBytes = transferSpeedBytes
                    peakTransferSpeedBytes = max(peakTransferSpeedBytes, transferSpeedBytes)
                    sessionRx = sRx
                    sessionTx = sTx
                    if (networkHistory.size >= historySize) networkHistory.removeAt(0)
                    networkHistory.add(speedKb)

                    imageCacheSize = imgSize
                    playerCacheSize = plySize
                    downloadCacheSize = dwnSize
                    if (totR > 0) {
                        totalRam = totR
                        availRam = avlR
                        appPssKb = pss
                    }
                }
            }
            delay(800)
        }
    }

    val librarySongCount by database
        .inLibrarySongsCount()
        .collectAsState(initial = 0)

    // ── Clear Cache Dialog ─────────────────────────────────────────────────────
    var showClearCacheDialog by remember { mutableStateOf(false) }

    if (showClearCacheDialog) {
        ActionPromptDialog(
            title = "Clear song and image caches",
            onDismiss = { showClearCacheDialog = false },
            onConfirm = {
                coroutineScope.launch(Dispatchers.IO) {
                    tryOrNull {
                        playerCache?.keys?.forEach { key ->
                            tryOrNull { playerCache.removeResource(key) }
                        }
                    }

                    try {
                        val urlsToPreserve = mutableSetOf<String>()
                        val downloadedSongs = try {
                            database.downloadedSongsByNameAsc().first()
                        } catch (_: Exception) {
                            emptyList()
                        }
                        downloadedSongs.forEach { song ->
                            song.song.thumbnailUrl?.let { urlsToPreserve.add(it.encodeUtf8().sha256().hex()) }
                            song.album?.thumbnailUrl?.let { urlsToPreserve.add(it.encodeUtf8().sha256().hex()) }
                        }

                        val directory = imageDiskCache?.directory?.toFile()
                        if (directory != null && directory.exists() && directory.isDirectory) {
                            directory.listFiles()?.forEach { file ->
                                if (file.isFile && !file.name.startsWith("journal")) {
                                    val isPreserved = urlsToPreserve.any { hash -> file.name.startsWith(hash) }
                                    if (!isPreserved) {
                                        file.delete()
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }

                    val refreshedPlayerCache = tryOrNull { playerCache?.cacheSpace } ?: 0L
                    val refreshedImageCache = imageDiskCache?.size ?: 0L
                    withContext(Dispatchers.Main) {
                        playerCacheSize = refreshedPlayerCache
                        imageCacheSize = refreshedImageCache
                    }
                }
                showClearCacheDialog = false
            },
            onCancel = { showClearCacheDialog = false },
            content = {
                Text(
                    "This will clear the streaming song cache and non-downloaded image cache. " +
                        "Downloaded music and offline cover art will not be affected."
                )
            },
        )
    }

    // ── Screen Content Layout ──────────────────────────────────────────────────
    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)
            )
        )

        Spacer(Modifier.height(8.dp))

        // ── Real-Time Resource Telemetry & Graph Card ─────────────────────────
        LiveTelemetryCard(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it },
            cpuHistory = cpuHistory,
            gpuHistory = gpuHistory,
            networkHistory = networkHistory,
            currentCpu = currentCpuPercent,
            peakCpu = peakCpuPercent,
            cores = cpuCores,
            currentGpuLoad = currentGpuLoad,
            gpuDurationMs = currentGpuDurationMs,
            refreshRate = refreshRate,
            currentSpeedBytes = currentTransferSpeedBytes,
            peakSpeedBytes = peakTransferSpeedBytes,
            totalConsumedBytes = sessionRx + sessionTx,
            sessionRx = sessionRx,
        )

        Spacer(Modifier.height(20.dp))

        // ── 1. Processor & Memory Group ───────────────────────────────────────
        val animatedCpu by animateFloatAsState(
            targetValue = currentCpuPercent,
            animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            label = "animatedCpuText",
        )

        Material3SettingsGroup(
            title = "Processor & Memory",
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.speed),
                    title = { Text("App CPU utilization") },
                    description = {
                        Text(
                            "${String.format("%.1f%%", animatedCpu)} (Peak: ${String.format("%.1f%%", peakCpuPercent)}) · $cpuCores cores active"
                        )
                    },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.storage),
                    title = { Text("Memory footprint") },
                    description = {
                        Column {
                            Text(
                                if (totalRam > 0) {
                                    "App: ${formatFileSize(appPssKb * 1024L)} · System: ${formatFileSize(totalRam - availRam)} / ${formatFileSize(totalRam)}"
                                } else "Loading…"
                            )
                            if (totalRam > 0) {
                                LinearProgressIndicator(
                                    progress = { ramProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    strokeCap = StrokeCap.Round,
                                )
                            }
                        }
                    },
                ),
            )
        )

        Spacer(Modifier.height(16.dp))

        // ── 2. GPU & Graphics Pipeline Group ─────────────────────────────────
        val animatedGpu by animateFloatAsState(
            targetValue = currentGpuLoad,
            animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            label = "animatedGpuText",
        )

        Material3SettingsGroup(
            title = "GPU & Display Pipeline",
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.graphic_eq),
                    title = { Text("GPU render activity") },
                    description = {
                        Text(
                            "${String.format("%.1f%%", animatedGpu)} load · ${String.format("%.2f ms", currentGpuDurationMs)} avg frame draw"
                        )
                    },
                ),
            )
        )

        Spacer(Modifier.height(16.dp))

        // ── 3. Network & Data Consumption Group ───────────────────────────────
        val totalDataConsumed = sessionRx + sessionTx
        Material3SettingsGroup(
            title = "Network & Data Consumption",
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.network_node),
                    title = { Text("Total data consumed") },
                    description = {
                        Text(
                            "${formatFileSize(totalDataConsumed)} total transferred since app launch",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.download),
                    title = { Text("Data transfer breakdown") },
                    description = {
                        Text("Downloaded: ${formatFileSize(sessionRx)} · Uploaded: ${formatFileSize(sessionTx)}")
                    },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.speed),
                    title = { Text("Live transfer rate") },
                    description = {
                        Text(
                            "${formatFileSize(currentTransferSpeedBytes)}/s (Peak: ${formatFileSize(peakTransferSpeedBytes)}/s)"
                        )
                    },
                ),
            )
        )

        Spacer(Modifier.height(16.dp))

        // ── 4. Storage & Cache Footprint Group ────────────────────────────────
        Material3SettingsGroup(
            title = "Storage & Local Caches",
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.music_note),
                    title = { Text("Song streaming cache") },
                    description = {
                        Column {
                            Text(formatFileSize(playerCacheSize))
                            UsageBar(used = playerCacheSize, max = 1024L * 1024 * 1024)
                        }
                    },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.graphic_eq),
                    title = { Text("Image & artwork cache") },
                    description = {
                        Column {
                            Text(formatFileSize(imageCacheSize))
                            UsageBar(used = imageCacheSize, max = 512L * 1024 * 1024)
                        }
                    },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.download),
                    title = { Text("Downloaded songs") },
                    description = { Text(formatFileSize(downloadCacheSize)) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.library_music_outlined),
                    title = { Text("Library songs") },
                    description = { Text("$librarySongCount tracks saved in database") },
                ),
            )
        )

        Spacer(Modifier.height(20.dp))

        // ── Action: Clear Cache ────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Button(
                onClick = { showClearCacheDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.delete),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Clear song & image cache")
            }
        }

        Spacer(Modifier.padding(bottom = 36.dp))
    }

    TopAppBar(
        windowInsets = appTopBarWindowInsets(),
        title = { Text("App Usage") },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

/**
 * Top interactive card showcasing live telemetry with real-time graphs for CPU, GPU, and Data.
 */
@Composable
private fun LiveTelemetryCard(
    selectedTab: UsageGraphTab,
    onTabSelected: (UsageGraphTab) -> Unit,
    cpuHistory: List<Float>,
    gpuHistory: List<Float>,
    networkHistory: List<Float>,
    currentCpu: Float,
    peakCpu: Float,
    cores: Int,
    currentGpuLoad: Float,
    gpuDurationMs: Float,
    refreshRate: Float,
    currentSpeedBytes: Long,
    peakSpeedBytes: Long,
    totalConsumedBytes: Long,
    sessionRx: Long,
) {
    val accentColor by animateColorAsState(
        targetValue = when (selectedTab) {
            UsageGraphTab.CPU -> Color(0xFF38BDF8) // Neon Sky Blue
            UsageGraphTab.GPU -> Color(0xFFA855F7) // Radiant Purple
            UsageGraphTab.DATA -> Color(0xFF34D399) // Mint Green
        },
        label = "graphAccentColor",
    )

    val animatedCpu by animateFloatAsState(
        targetValue = currentCpu,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "animatedCpuPill",
    )
    val animatedGpu by animateFloatAsState(
        targetValue = currentGpuLoad,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "animatedGpuPill",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppleTokens.CardCorner))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                width = 1.dp,
                color = accentColor.copy(alpha = 0.25f),
                shape = RoundedCornerShape(AppleTokens.CardCorner),
            )
            .padding(16.dp)
    ) {
        // Tab Selection Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UsageGraphTab.values().forEach { tab ->
                val isSelected = tab == selectedTab
                val tabColor = when (tab) {
                    UsageGraphTab.CPU -> Color(0xFF38BDF8)
                    UsageGraphTab.GPU -> Color(0xFFA855F7)
                    UsageGraphTab.DATA -> Color(0xFF34D399)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) tabColor.copy(alpha = 0.18f)
                            else Color.White.copy(alpha = 0.04f)
                        )
                        .border(
                            width = if (isSelected) 1.5.dp else 0.5.dp,
                            color = if (isSelected) tabColor.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable { onTabSelected(tab) }
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = painterResource(tab.iconRes),
                            contentDescription = null,
                            tint = if (isSelected) tabColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (isSelected) tabColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Live Header Readout
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = when (selectedTab) {
                        UsageGraphTab.CPU -> "CPU Activity (30s)"
                        UsageGraphTab.GPU -> "GPU Activity (30s)"
                        UsageGraphTab.DATA -> "Network Throughput (30s)"
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = when (selectedTab) {
                        UsageGraphTab.CPU -> "$cores cores · Active utilization"
                        UsageGraphTab.GPU -> "${refreshRate.toInt()} Hz display · Hardware accelerated"
                        UsageGraphTab.DATA -> "${formatFileSize(totalConsumedBytes)} consumed this session"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9EACB9),
                )
            }

            // Real-time badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(accentColor.copy(alpha = 0.16f))
                    .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = when (selectedTab) {
                        UsageGraphTab.CPU -> "${String.format("%.1f", animatedCpu)}%"
                        UsageGraphTab.GPU -> "${String.format("%.1f", animatedGpu)}%"
                        UsageGraphTab.DATA -> "${formatFileSize(currentSpeedBytes)}/s"
                    },
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
                    color = accentColor,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Dynamic Real-time Canvas Graph with Crossfade Transition
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                fadeIn(animationSpec = tween(220, delayMillis = 40)) togetherWith
                    fadeOut(animationSpec = tween(150))
            },
            label = "graphContentTransition",
        ) { tab ->
            when (tab) {
                UsageGraphTab.CPU -> {
                    SmoothTelemetryGraph(
                        points = cpuHistory,
                        accentColor = accentColor,
                        maxScale = 100f,
                        unit = "%",
                        formatLabel = { "${it.toInt()}%" },
                    )
                }
                UsageGraphTab.GPU -> {
                    SmoothTelemetryGraph(
                        points = gpuHistory,
                        accentColor = accentColor,
                        maxScale = 100f,
                        unit = "%",
                        formatLabel = { "${it.toInt()}%" },
                    )
                }
                UsageGraphTab.DATA -> {
                    val maxKb = max(50f, networkHistory.maxOrNull() ?: 50f)
                    SmoothTelemetryGraph(
                        points = networkHistory,
                        accentColor = accentColor,
                        maxScale = maxKb,
                        unit = "KB/s",
                        formatLabel = { kb ->
                            if (kb >= 1024f) "${String.format("%.1f", kb / 1024f)} MB/s"
                            else "${kb.toInt()} KB/s"
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Sub-stat badges
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (selectedTab) {
                UsageGraphTab.CPU -> {
                    StatPill(title = "Current", value = "${String.format("%.1f", animatedCpu)}%", modifier = Modifier.weight(1f))
                    StatPill(title = "Peak", value = "${String.format("%.1f", peakCpu)}%", modifier = Modifier.weight(1f))
                    StatPill(title = "Average", value = "${String.format("%.1f", cpuHistory.average())}%", modifier = Modifier.weight(1f))
                }
                UsageGraphTab.GPU -> {
                    StatPill(title = "GPU Load", value = "${String.format("%.1f", animatedGpu)}%", modifier = Modifier.weight(1f))
                    StatPill(title = "Draw Time", value = "${String.format("%.2f", gpuDurationMs)} ms", modifier = Modifier.weight(1f))
                    StatPill(title = "Target", value = "${String.format("%.1f", 1000f / refreshRate)} ms", modifier = Modifier.weight(1f))
                }
                UsageGraphTab.DATA -> {
                    StatPill(title = "Consumed", value = formatFileSize(totalConsumedBytes), modifier = Modifier.weight(1f))
                    StatPill(title = "Peak Speed", value = "${formatFileSize(peakSpeedBytes)}/s", modifier = Modifier.weight(1f))
                    StatPill(title = "Downloaded", value = formatFileSize(sessionRx), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * High-performance smooth Catmull-Rom cubic-spline graph canvas with gradient under-fill and dashed guidelines.
 */
@Composable
private fun SmoothTelemetryGraph(
    points: List<Float>,
    accentColor: Color,
    maxScale: Float,
    unit: String,
    modifier: Modifier = Modifier,
    minScale: Float = 0f,
    formatLabel: (Float) -> String = { "%.0f".format(it) },
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(128.dp)) {
            val width = size.width
            val height = size.height
            val range = (maxScale - minScale).coerceAtLeast(1f)

            // 1. Draw dashed horizontal grid lines at 25%, 50%, 75%
            val gridAlpha = 0.12f
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
            listOf(0.25f, 0.50f, 0.75f).forEach { fraction ->
                val y = height * (1f - fraction)
                drawLine(
                    color = Color.White.copy(alpha = gridAlpha),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashEffect,
                )
            }

            if (points.size < 2) return@Canvas

            // 2. Map data points to canvas coordinates with smooth padding
            val stepX = width / (points.size - 1)
            val coords = points.mapIndexed { idx, value ->
                val normalizedY = ((value - minScale) / range).coerceIn(0f, 1f)
                val x = idx * stepX
                val y = height - (normalizedY * (height * 0.82f)) - (height * 0.08f)
                Offset(x, y)
            }

            // 3. Build natural Catmull-Rom cubic Bezier path for continuous tangent smoothness
            val strokePath = Path().apply {
                moveTo(coords[0].x, coords[0].y)
                for (i in 0 until coords.size - 1) {
                    val p0 = if (i > 0) coords[i - 1] else coords[i]
                    val p1 = coords[i]
                    val p2 = coords[i + 1]
                    val p3 = if (i + 2 < coords.size) coords[i + 2] else p2

                    val cp1x = p1.x + (p2.x - p0.x) / 6f
                    val cp1y = (p1.y + (p2.y - p0.y) / 6f).coerceIn(height * 0.06f, height * 0.94f)
                    val cp2x = p2.x - (p3.x - p1.x) / 6f
                    val cp2y = (p2.y - (p3.y - p1.y) / 6f).coerceIn(height * 0.06f, height * 0.94f)

                    cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
                }
            }

            // 4. Build gradient area under curve
            val fillPath = Path().apply {
                addPath(strokePath)
                lineTo(coords.last().x, height)
                lineTo(coords.first().x, height)
                close()
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.35f),
                        accentColor.copy(alpha = 0.03f),
                    ),
                    startY = 0f,
                    endY = height,
                ),
            )

            // 5. Draw the glowing curved line
            drawPath(
                path = strokePath,
                color = accentColor,
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )

            // 6. Draw real-time glowing pulse dot on the latest sample
            val latest = coords.last()
            drawCircle(
                color = accentColor.copy(alpha = pulseAlpha * 0.5f),
                radius = 8.dp.toPx(),
                center = latest,
            )
            drawCircle(
                color = accentColor,
                radius = 3.5.dp.toPx(),
                center = latest,
            )
        }

        // Top and bottom scale indicators
        Text(
            text = formatLabel(maxScale),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp, top = 2.dp),
        )
        Text(
            text = formatLabel(minScale),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 2.dp),
        )
    }
}

/** Compact sub-stat metric badge */
@Composable
private fun StatPill(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(vertical = 6.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = Color(0xFF9EACB9),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** Thin animated progress bar clamped to used/max. */
@Composable
private fun UsageBar(used: Long, max: Long) {
    val progress by animateFloatAsState(
        targetValue = if (max > 0) (used.toFloat() / max.toFloat()).coerceIn(0f, 1f) else 0f,
        label = "usageBar",
    )
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        strokeCap = StrokeCap.Round,
    )
}

/** Resolves the host Android Activity from Context if available. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
