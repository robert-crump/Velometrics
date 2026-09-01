package com.velometrics.app.ui.screens.alltimestats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.domain.model.PowerCurvePoint
import com.velometrics.app.domain.model.PowerSpeedPoint
import com.velometrics.app.domain.model.RecordEntry
import com.velometrics.app.domain.model.YearStat
import com.velometrics.app.ui.components.LoadingBox
import com.velometrics.app.ui.components.MetricCell
import com.velometrics.app.ui.components.NotFoundBox
import com.velometrics.app.ui.components.PowerCurveChart
import com.velometrics.app.ui.components.ScatterPlotChart
import com.velometrics.app.ui.components.ScatterPoint
import com.velometrics.app.ui.components.roundedAxisBounds
import com.velometrics.app.util.FormatUtils

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
                LoadingBox(modifier = Modifier.padding(padding))
            }
            !uiState.hasAnySessions -> {
                NotFoundBox(
                    text = "No rides recorded yet",
                    modifier = Modifier.padding(padding),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    Column(modifier = Modifier.weight(1f)) {
                        MetricCell(label = "Rides", value = "${year.rideCount}")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        MetricCell(
                            label = "Distance",
                            value = FormatUtils.formatDistanceRounded(year.totalDistanceKm)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        MetricCell(
                            label = "Elev. gain",
                            value = FormatUtils.formatElevationGainRounded(year.totalElevationGainM)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        MetricCell(
                            label = "Duration",
                            value = FormatUtils.formatDuration(year.totalNetDurationSec)
                        )
                    }
                }
            }
        }
    }
}
