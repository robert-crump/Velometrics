package com.velometrics.app.ui.screens.alltimestats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.domain.model.PowerCurvePoint
import com.velometrics.app.domain.model.PowerSpeedPoint
import com.velometrics.app.domain.model.RecordEntry
import com.velometrics.app.domain.model.YearStat
import com.velometrics.app.ui.components.ScatterPlotChart
import com.velometrics.app.ui.components.ScatterPoint
import com.velometrics.app.ui.components.roundedAxisBounds
import com.velometrics.app.util.FormatUtils
import kotlin.math.abs
import kotlin.math.ln

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllTimeStatsScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToSession: (Long) -> Unit = {},
    viewModel: AllTimeStatsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("All-time Stats") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            !uiState.hasAnySessions -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No rides recorded yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    RecordSection(
                        title = "All-time best",
                        records = uiState.bestTrio,
                        onNavigateToSession = onNavigateToSession
                    )
                    RecordSection(
                        title = "Distance splits",
                        records = uiState.distanceSplits,
                        onNavigateToSession = onNavigateToSession
                    )
                    PowerCurveSection(
                        points = uiState.powerCurve,
                        hasData = uiState.hasAnyPowerCurveData,
                        onNavigateToSession = onNavigateToSession
                    )
                    PowerSpeedPointCloudSection(points = uiState.powerSpeedPoints)
                    YearBreakdownSection(yearStats = uiState.yearStats)
                }
            }
        }
    }
}

@Composable
private fun RecordSection(
    title: String,
    records: List<RecordEntry>,
    onNavigateToSession: (Long) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            records.forEachIndexed { index, record ->
                RecordRow(record = record, onNavigateToSession = onNavigateToSession)
                if (index != records.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun RecordRow(
    record: RecordEntry,
    onNavigateToSession: (Long) -> Unit
) {
    val sessionId = record.sessionId
    val rowModifier = if (sessionId != null) {
        Modifier.clickable { onNavigateToSession(sessionId) }
    } else {
        Modifier
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(rowModifier)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(record.label, style = MaterialTheme.typography.bodyMedium)
        if (record.value != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    record.value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (record.date != null) {
                    Text(
                        record.date,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Text(
                record.emptyMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PowerCurveSection(
    points: List<PowerCurvePoint>,
    hasData: Boolean,
    onNavigateToSession: (Long) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Power curve", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            if (!hasData) {
                Text(
                    "Import a ride with power data to see your power curve",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                PowerCurveChart(points = points, onNavigateToSession = onNavigateToSession)
            }
        }
    }
}

@Composable
private fun PowerCurveChart(
    points: List<PowerCurvePoint>,
    onNavigateToSession: (Long) -> Unit
) {
    val availableIndices = remember(points) { points.indices.filter { points[it].watts != null } }
    if (availableIndices.isEmpty()) return

    var selectedIndex by remember(points) { mutableStateOf(availableIndices.first()) }

    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    val selectedDotColor = MaterialTheme.colorScheme.primary
    val unselectedDotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current

    val maxWatts = points.mapNotNull { it.watts }.maxOrNull() ?: 0
    val maxAxis = if (maxWatts % 50 == 0 && maxWatts > 0) maxWatts else ((maxWatts / 50) + 1) * 50
    val tickStep = (((maxAxis / 5).coerceAtLeast(1) + 49) / 50) * 50

    val logDurations = remember(points) { points.map { ln(it.durationSec.toDouble()) } }
    val minLog = logDurations.min()
    val maxLog = logDurations.max()

    val selected = points[selectedIndex]

    Column {
        val headerModifier = if (selected.sessionId != null) {
            Modifier.clickable { onNavigateToSession(selected.sessionId) }
        } else {
            Modifier
        }
        Row(
            modifier = Modifier.fillMaxWidth().then(headerModifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                selected.watts?.let { "$it W" } ?: "--",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                selected.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (selected.date != null) {
                Text(
                    selected.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            val chartWidthPx = with(density) { maxWidth.toPx() }
            val chartHeightPx = with(density) { maxHeight.toPx() }
            val leftPaddingPx = with(density) { 32.dp.toPx() }
            val rightPaddingPx = with(density) { 8.dp.toPx() }
            val topPaddingPx = with(density) { 8.dp.toPx() }
            val bottomPaddingPx = with(density) { 20.dp.toPx() }

            fun xFor(i: Int): Float {
                val range = (maxLog - minLog).takeIf { it > 0.0 } ?: 1.0
                val fraction = (logDurations[i] - minLog) / range
                return leftPaddingPx + fraction.toFloat() * (chartWidthPx - leftPaddingPx - rightPaddingPx)
            }

            fun yFor(watts: Int): Float {
                val available = chartHeightPx - topPaddingPx - bottomPaddingPx
                return topPaddingPx + available * (1f - watts.toFloat() / maxAxis)
            }

            fun nearestAvailableIndex(x: Float): Int =
                availableIndices.minByOrNull { abs(xFor(it) - x) } ?: availableIndices.first()

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(points) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            selectedIndex = nearestAvailableIndex(down.position.x)
                            down.consume()
                            var dragging = true
                            while (dragging) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull()
                                if (change != null && change.pressed) {
                                    selectedIndex = nearestAvailableIndex(change.position.x)
                                    change.consume()
                                } else {
                                    dragging = false
                                }
                            }
                        }
                    }
            ) {
                val labelPaint = android.graphics.Paint().apply {
                    textSize = 9.dp.toPx()
                }

                var tick = 0
                while (tick <= maxAxis) {
                    val y = yFor(tick)
                    drawLine(
                        color = gridColor,
                        start = Offset(leftPaddingPx, y),
                        end = Offset(chartWidthPx - rightPaddingPx, y),
                        strokeWidth = 1.dp.toPx()
                    )
                    labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                    labelPaint.color = android.graphics.Color.argb(
                        (0.5f * 255).toInt(),
                        (onSurface.red * 255).toInt(),
                        (onSurface.green * 255).toInt(),
                        (onSurface.blue * 255).toInt()
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        "$tick",
                        leftPaddingPx - 4.dp.toPx(),
                        y + labelPaint.textSize / 3,
                        labelPaint
                    )
                    tick += tickStep
                }

                val path = Path()
                availableIndices.forEachIndexed { orderIdx, i ->
                    val x = xFor(i)
                    val y = yFor(points[i].watts!!)
                    if (orderIdx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = lineColor, style = Stroke(width = 2.dp.toPx()))

                points.forEachIndexed { i, point ->
                    val x = xFor(i)
                    val watts = point.watts
                    if (watts != null) {
                        val isSelected = i == selectedIndex
                        drawCircle(
                            color = if (isSelected) selectedDotColor else unselectedDotColor,
                            radius = if (isSelected) 5.dp.toPx() else 3.dp.toPx(),
                            center = Offset(x, yFor(watts))
                        )
                    }
                    val textAlpha = if (i == selectedIndex) 255 else (0.5f * 255).toInt()
                    labelPaint.textAlign = android.graphics.Paint.Align.CENTER
                    labelPaint.color = android.graphics.Color.argb(
                        textAlpha,
                        (onSurface.red * 255).toInt(),
                        (onSurface.green * 255).toInt(),
                        (onSurface.blue * 255).toInt()
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        point.label,
                        x,
                        chartHeightPx - 4.dp.toPx(),
                        labelPaint
                    )
                }
            }
        }
    }
}

private val ELEVATION_BUCKET_LABELS = listOf("0-200", "200-800", "800-1,400", "1,400-2,000", ">2,000")
private val ELEVATION_BUCKET_ALPHAS = listOf(0.35f, 0.5f, 0.65f, 0.8f, 1.0f)

@Composable
private fun PowerSpeedPointCloudSection(points: List<PowerSpeedPoint>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Power vs. Speed", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            if (points.isEmpty()) {
                Text(
                    "Import rides with power and elevation data to see this chart",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                val bucketColors = ELEVATION_BUCKET_ALPHAS.map { MaterialTheme.colorScheme.primary.copy(alpha = it) }
                val (xMin, xMax) = roundedAxisBounds(
                    points.minOf { it.avgPowerW },
                    points.maxOf { it.avgPowerW }
                )
                val (yMin, yMax) = roundedAxisBounds(
                    points.minOf { it.avgSpeedKmh },
                    points.maxOf { it.avgSpeedKmh },
                    step = 1f
                )
                ScatterPlotChart(
                    points = points.map { pt ->
                        ScatterPoint(pt.avgPowerW, pt.avgSpeedKmh, bucketColors[pt.elevationBucket])
                    },
                    xLabel = "Avg Power (W)",
                    yLabel = "Avg Speed (km/h)",
                    xMin = xMin,
                    xMax = xMax,
                    yMin = yMin,
                    yMax = yMax,
                    xTickFormat = "%.0f",
                    dotRadius = 2.dp
                )
                ElevationBucketLegend(colors = bucketColors)
            }
        }
    }
}

@Composable
private fun ElevationBucketLegend(colors: List<Color>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Elevation gain / 100km",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ELEVATION_BUCKET_LABELS.forEachIndexed { i, label ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(colors[i], CircleShape)
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun YearBreakdownSection(yearStats: List<YearStat>) {
    // yearStats is sorted from the current year down to the earliest year with data, so index 0
    // is always the current year — the requested starting point.
    var selectedIndex by remember(yearStats) { mutableStateOf(0) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("By year", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            if (yearStats.isEmpty()) {
                Text(
                    "No rides recorded yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                val year = yearStats[selectedIndex]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { selectedIndex++ },
                        enabled = selectedIndex < yearStats.lastIndex
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous year")
                    }
                    Text(
                        "${year.year}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(
                        onClick = { selectedIndex-- },
                        enabled = selectedIndex > 0
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next year")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    YearStatCell(
                        "Rides",
                        "${year.rideCount}",
                        Modifier.weight(1f)
                    )
                    YearStatCell(
                        "Distance",
                        FormatUtils.formatDistanceRounded(year.totalDistanceKm),
                        Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    YearStatCell(
                        "Elev. gain",
                        FormatUtils.formatElevationGainRounded(year.totalElevationGainM),
                        Modifier.weight(1f)
                    )
                    YearStatCell(
                        "Duration",
                        FormatUtils.formatDuration(year.totalNetDurationSec),
                        Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun YearStatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
