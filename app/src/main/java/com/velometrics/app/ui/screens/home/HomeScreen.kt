package com.velometrics.app.ui.screens.home

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import kotlin.math.roundToInt
import com.velometrics.app.domain.model.CyclingSessionSummary
import com.velometrics.app.util.FormatUtils
import java.time.format.DateTimeFormatter
import java.util.Locale

// ─── Custom file-picker contract that requests descending sort by display name ───

private class OpenMultipleDocumentsSorted : ActivityResultContract<String, List<Uri>>() {
    override fun createIntent(context: android.content.Context, input: String): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            // Request descending sort by display name (works on Android 8+; ignored otherwise)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(OpenableColumns.DISPLAY_NAME)
                )
                putExtra(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                )
            }
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val clipData = intent.clipData
        return when {
            clipData != null -> (0 until clipData.itemCount).map { clipData.getItemAt(it).uri }
            intent.data != null -> listOf(intent.data!!)
            else -> emptyList()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSessionClick: (Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val sessions by viewModel.sessions.collectAsState()
    val sessionCount by viewModel.sessionCount.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val monthlyData by viewModel.monthlyData.collectAsState()
    val selectedMonthIndex by viewModel.selectedMonthIndex.collectAsState()
    val selectedMonthSummary by viewModel.selectedMonthSummary.collectAsState()
    val isInitialLoading by viewModel.isInitialLoading.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val dropboxSyncMessage by viewModel.dropboxSyncMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(dropboxSyncMessage) {
        dropboxSyncMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearDropboxSyncMessage()
        }
    }

    val listState = rememberLazyListState()
    var showScrollBar by remember { mutableStateOf(false) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            showScrollBar = true
        } else if (showScrollBar) {
            delay(2000)
            showScrollBar = false
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = OpenMultipleDocumentsSorted()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importFromUris(uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Velometrics")
                        if (sessionCount > 0) {
                            Text(
                                text = "$sessionCount rides",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { filePickerLauncher.launch("*/*") }) {
                Icon(Icons.Default.Add, contentDescription = "Import .fit file")
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isSyncing,
            onRefresh = { viewModel.syncDropbox() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                // Import progress indicator
                when (val state = importState) {
                    is ImportUiState.BatchLoading -> {
                        LinearProgressIndicator(
                            progress = { state.current.toFloat() / state.total.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "Importing file ${state.current} of ${state.total}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    is ImportUiState.Loading -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "Importing…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    else -> {}
                }
    
                when {
                    isInitialLoading -> {
                        // Show blank screen while rides are loading from the DB
                        Box(modifier = Modifier.fillMaxSize())
                    }
                    sessions.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.AutoMirrored.Filled.DirectionsBike,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "No rides imported yet",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Tap the + button to import .fit files",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    else -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                // Monthly stats section — above the ride list
                                item {
                                    MonthlyStatsSection(
                                        monthlyData = monthlyData,
                                        selectedMonthIndex = selectedMonthIndex,
                                        selectedSummary = selectedMonthSummary,
                                        onMonthSelected = { viewModel.selectMonthIndex(it) }
                                    )
                                }
    
                                // Session list
                                items(sessions) { session ->
                                    SessionCard(session = session, onClick = { onSessionClick(session.id) })
                                }
                            }
                            ScrollBarOverlay(
                                visible = showScrollBar,
                                listState = listState,
                                modifier = Modifier.align(Alignment.CenterEnd)
                            )
                        }
                    }
                }
            }
        }

        // Small-file warning dialog: shown when an imported file has fewer than 60 GPS points
        val currentState = importState
        if (currentState is ImportUiState.SmallFileWarning) {
            AlertDialog(
                onDismissRequest = { viewModel.skipSmallFile() },
                title = { Text("Small file") },
                text = {
                    Text(
                        "File ${currentState.fileName} has only ${currentState.dataPointCount} " +
                                "data points. Do you really want to import it?"
                    )
                },
                confirmButton = {
                    Button(onClick = { viewModel.confirmSmallFileImport() }) {
                        Text("Import")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { viewModel.skipSmallFile() }) {
                        Text("Skip file")
                    }
                }
            )
        }
    }
}

@Composable
private fun ScrollBarOverlay(
    visible: Boolean,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(600)),
        modifier = modifier
    ) {
        ScrollBarIndicator(
            listState = listState,
            modifier = Modifier.padding(end = 2.dp)
        )
    }
}

// Estimate total content height from the average visible-item size.
// Using pixel heights keeps the thumb height stable when a partially-visible
// item enters or leaves the visible set (which would flip visibleCount ± 1).
private class ScrollBarMetrics(
    val avgItemPx: Float,
    val estimatedTotalPx: Float,
    val viewportPx: Float
)

private fun scrollBarMetrics(layoutInfo: LazyListLayoutInfo): ScrollBarMetrics? {
    val visItems = layoutInfo.visibleItemsInfo
    if (layoutInfo.totalItemsCount == 0 || visItems.isEmpty()) return null
    val avgItemPx = visItems.sumOf { it.size }.toFloat() / visItems.size
    val estimatedTotalPx = avgItemPx * layoutInfo.totalItemsCount
    val viewportPx = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
    if (viewportPx >= estimatedTotalPx) return null
    return ScrollBarMetrics(avgItemPx, estimatedTotalPx, viewportPx)
}

@Composable
private fun ScrollBarIndicator(
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val coroutineScope = rememberCoroutineScope()

    fun scrollToFraction(fraction: Float) {
        val metrics = scrollBarMetrics(listState.layoutInfo) ?: return
        val maxScrollPx = metrics.estimatedTotalPx - metrics.viewportPx
        val targetPx = fraction.coerceIn(0f, 1f) * maxScrollPx
        val totalItems = listState.layoutInfo.totalItemsCount
        val targetIndex = (targetPx / metrics.avgItemPx).toInt().coerceIn(0, totalItems - 1)
        val targetOffset = (targetPx - targetIndex * metrics.avgItemPx).toInt()
        coroutineScope.launch {
            listState.scrollToItem(targetIndex, targetOffset)
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(24.dp)
            .padding(vertical = 8.dp)
            .pointerInput(listState) {
                detectDragGestures(
                    onDragStart = { offset -> scrollToFraction(offset.y / size.height) }
                ) { change, _ ->
                    change.consume()
                    scrollToFraction(change.position.y / size.height)
                }
            },
        contentAlignment = Alignment.CenterEnd
    ) {
        Canvas(modifier = Modifier.fillMaxHeight().width(4.dp)) {
            val metrics = scrollBarMetrics(listState.layoutInfo) ?: return@Canvas

            val thumbHeight = (size.height * metrics.viewportPx / metrics.estimatedTotalPx)
                .coerceIn(24f, size.height)

            // Smooth position: convert to a pixel-based fraction of the scrollable range
            val scrolledPx = listState.firstVisibleItemIndex * metrics.avgItemPx +
                    listState.firstVisibleItemScrollOffset
            val maxScrollPx = metrics.estimatedTotalPx - metrics.viewportPx
            val fraction = if (maxScrollPx > 0f) (scrolledPx / maxScrollPx).coerceIn(0f, 1f) else 0f
            val thumbTop = fraction * (size.height - thumbHeight)
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(0f, thumbTop),
                size = androidx.compose.ui.geometry.Size(size.width, thumbHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2f)
            )
        }
    }
}

@Composable
private fun MonthlyStatsSection(
    monthlyData: List<MonthlyRideSummary>,
    selectedMonthIndex: Int,
    selectedSummary: MonthlyRideSummary?,
    onMonthSelected: (Int) -> Unit
) {
    val isCurrentMonth = selectedMonthIndex == 11
    val headerLabel = if (isCurrentMonth || selectedSummary == null) {
        "This month"
    } else {
        val formatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
        selectedSummary.yearMonth.format(formatter)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = headerLabel,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Stats row: Rides | Total km | Total net duration
        Row(modifier = Modifier.fillMaxWidth()) {
            MonthStatCell(
                label = "Rides",
                value = "${selectedSummary?.rideCount ?: 0}",
                modifier = Modifier.weight(1f)
            )
            MonthStatCell(
                label = "Total km",
                value = selectedSummary?.totalKm?.toInt()?.let { km ->
                    km.toString().reversed().chunked(3).joinToString(".").reversed()
                } ?: "0",
                modifier = Modifier.weight(1f)
            )
            MonthStatCell(
                label = "Total net duration",
                value = FormatUtils.formatDuration(selectedSummary?.totalNetDurationSec ?: 0),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Line chart — 12 months, newest pre-selected
        if (monthlyData.isNotEmpty()) {
            MonthlyLineChart(
                monthlyData = monthlyData,
                selectedIndex = selectedMonthIndex,
                onMonthSelected = onMonthSelected
            )
        }
    }

    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun MonthStatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall
        )
    }
}

@Composable
private fun MonthlyLineChart(
    monthlyData: List<MonthlyRideSummary>,
    selectedIndex: Int,
    onMonthSelected: (Int) -> Unit
) {
    val selectedColor = MaterialTheme.colorScheme.primary
    val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current

    // Round max ride count up to the next multiple of 5 (minimum 5)
    val actualMax = monthlyData.maxOfOrNull { it.rideCount } ?: 0
    val maxCount = if (actualMax == 0) 5
                   else if (actualMax % 5 == 0) actualMax
                   else (actualMax / 5 + 1) * 5

    val pointCount = monthlyData.size

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        val chartWidthPx = with(density) { maxWidth.toPx() }
        val chartHeightPx = with(density) { maxHeight.toPx() }
        // Left padding accommodates y-axis labels; right side uses smaller padding
        val leftPaddingPx = with(density) { 28.dp.toPx() }
        val rightPaddingPx = with(density) { 8.dp.toPx() }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(monthlyData, chartWidthPx) {
                    fun selectAtX(x: Float) {
                        if (pointCount >= 2) {
                            val innerWidth = chartWidthPx - leftPaddingPx - rightPaddingPx
                            val stepX = innerWidth / (pointCount - 1)
                            val idx = ((x - leftPaddingPx) / stepX)
                                .roundToInt()
                                .coerceIn(0, pointCount - 1)
                            onMonthSelected(idx)
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        selectAtX(down.position.x)
                        down.consume()
                        var dragging = true
                        while (dragging) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull()
                            if (change != null && change.pressed) {
                                selectAtX(change.position.x)
                                change.consume()
                            } else {
                                dragging = false
                            }
                        }
                    }
                }
        ) {
            if (pointCount < 2) return@Canvas

            val innerWidth = chartWidthPx - leftPaddingPx - rightPaddingPx
            val stepX = innerWidth / (pointCount - 1)
            val paddingTopPx = 8.dp.toPx()
            val paddingBottomPx = 20.dp.toPx()
            val availableHeight = chartHeightPx - paddingTopPx - paddingBottomPx

            fun xFor(i: Int) = leftPaddingPx + i * stepX
            fun yFor(count: Int) = paddingTopPx + availableHeight * (1f - count.toFloat() / maxCount)

            val labelPaint = android.graphics.Paint().apply {
                textSize = 9.dp.toPx()
                color = android.graphics.Color.argb(
                    (0.5f * 255).toInt(),
                    (onSurface.red * 255).toInt(),
                    (onSurface.green * 255).toInt(),
                    (onSurface.blue * 255).toInt()
                )
            }

            // Horizontal gridlines and y-axis labels at multiples of 5
            var tick = 0
            while (tick <= maxCount) {
                val y = yFor(tick)
                // Dashed gridline
                drawLine(
                    color = gridColor,
                    start = Offset(leftPaddingPx, y),
                    end = Offset(chartWidthPx - rightPaddingPx, y),
                    strokeWidth = 1.dp.toPx()
                )
                // Y-axis label (right-aligned just before the chart area)
                labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                drawContext.canvas.nativeCanvas.drawText(
                    "$tick",
                    leftPaddingPx - 4.dp.toPx(),
                    y + labelPaint.textSize / 3,
                    labelPaint
                )
                tick += 5
            }

            // Line path
            val path = Path()
            monthlyData.forEachIndexed { i, summary ->
                val x = xFor(i)
                val y = yFor(summary.rideCount)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = lineColor, style = Stroke(width = 2.dp.toPx()))

            // Dots + month labels
            monthlyData.forEachIndexed { i, summary ->
                val x = xFor(i)
                val y = yFor(summary.rideCount)
                val isSelected = i == selectedIndex

                drawCircle(
                    color = if (isSelected) selectedColor else unselectedColor,
                    radius = if (isSelected) 5.dp.toPx() else 3.dp.toPx(),
                    center = Offset(x, y)
                )

                val abbr = summary.yearMonth.month.name.take(1).uppercase() +
                        summary.yearMonth.month.name.drop(1).take(2).lowercase()
                val textAlpha = if (isSelected) 255 else (0.5f * 255).toInt()
                labelPaint.textAlign = android.graphics.Paint.Align.CENTER
                labelPaint.color = android.graphics.Color.argb(
                    textAlpha,
                    (onSurface.red * 255).toInt(),
                    (onSurface.green * 255).toInt(),
                    (onSurface.blue * 255).toInt()
                )
                drawContext.canvas.nativeCanvas.drawText(
                    abbr,
                    x,
                    chartHeightPx - 4.dp.toPx(),
                    labelPaint
                )
            }
        }
    }
}

@Composable
private fun SessionCard(session: CyclingSessionSummary, onClick: () -> Unit) {
    val avgSpeed = if (session.netDurationSec > 0)
        session.distanceKm / session.netDurationSec * 3600 else 0.0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = FormatUtils.formatDate(session.sessionStart),
                    style = MaterialTheme.typography.titleMedium
                )
                // Lightning bolt icon: green if power data, gray otherwise
                // Size is 12dp — 50% wider than the previous 8dp dot
                Icon(
                    imageVector = Icons.Default.FlashOn,
                    contentDescription = if (session.hasPower) "Has power data" else "No power data",
                    modifier = Modifier.size(12.dp),
                    tint = if (session.hasPower) Color(0xFF4CAF50) else Color.Gray
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = FormatUtils.formatDistance(session.distanceKm),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = FormatUtils.formatDuration(session.netDurationSec),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = FormatUtils.formatSpeed(avgSpeed),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (session.hasPower && session.averagePower != null) {
                    Text(
                        text = FormatUtils.formatPower(session.averagePower),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
