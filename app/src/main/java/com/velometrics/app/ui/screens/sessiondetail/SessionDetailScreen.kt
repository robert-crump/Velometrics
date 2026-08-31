package com.velometrics.app.ui.screens.sessiondetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.maplibre.android.maps.Style
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.energy
import com.velometrics.app.domain.service.SessionComparison
import com.velometrics.app.ui.components.*
import com.velometrics.app.util.FormatUtils
import com.velometrics.app.util.GpsTrackParser
import com.velometrics.app.util.MapOverlayUtils
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: SessionDetailViewModel = hiltViewModel()
) {
    val session by viewModel.session.collectAsState()
    val intervals by viewModel.intervals.collectAsState()
    val comparison by viewModel.comparison.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val powerZoneAverages by viewModel.powerZoneAverages.collectAsState()
    val hrZoneAverages by viewModel.hrZoneAverages.collectAsState()
    val speedHistogram by viewModel.speedHistogram.collectAsState()
    val speedHistogramAverages by viewModel.speedHistogramAverages.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ride details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            LoadingBox(modifier = Modifier.padding(padding))
        } else if (session == null) {
            NotFoundBox(text = "Session not found", modifier = Modifier.padding(padding))
        } else {
            val s = session!!
            var drawerFraction by remember { mutableStateOf(0.5f) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Full-screen map background (interactive)
                SessionDetailMap(
                    gpsTrack = s.gpsTrack,
                    intervals = intervals,
                    drawerFraction = drawerFraction
                )

                // Pull-up drawer with all statistics; opens at 50%
                PullUpDrawer(
                    initialFraction = 0.5f,
                    onFractionSnapped = { drawerFraction = it }
                ) {
                    RideSummaryGrid(session = s, comparison = comparison)

                    if (s.hasPower && s.powerZoneDistribution != null) {
                        PowerZoneChart(
                            powerZones = s.powerZoneDistribution!!,
                            averagePercentages = powerZoneAverages
                        )
                    } else if (!s.hasPower) {
                        // Session has no power data — show a placeholder card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Power Zones",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No power data available",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    SpeedHistogramChart(
                        percentages = speedHistogram,
                        allRidesAveragePercentages = speedHistogramAverages
                    )

                    if (s.hrZoneDistribution != null) {
                        HeartRateZoneChart(
                            hrZones = s.hrZoneDistribution!!,
                            averagePercentages = hrZoneAverages
                        )
                    }

                    if (s.cardiacDriftBuckets != null && s.cardiacDriftPercent != null) {
                        CardiacDriftChart(
                            buckets = s.cardiacDriftBuckets!!,
                            decouplingPercent = s.cardiacDriftPercent!!
                        )
                    }

                    if (s.hasPower && s.fatEfficiencyHistogram != null) {
                        FatEfficiencyHistogram(histogram = s.fatEfficiencyHistogram!!)
                    }

                    if (s.hasPower && s.sprintCount > 0 && s.sprintHistogram != null) {
                        SprintCard(sprintHistogram = s.sprintHistogram!!)
                    }

                    if (s.hasPower && intervals.isNotEmpty()) {
                        IntervalListCard(
                            intervals = intervals,
                            onIntervalClick = {}
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SessionDetailMap(
    gpsTrack: String?,
    intervals: List<IntervalSession>,
    drawerFraction: Float
) {
    val points = remember(gpsTrack) { GpsTrackParser.parse(gpsTrack) }
    val mapStyleRef = remember { mutableStateOf<Style?>(null) }

    LaunchedEffect(mapStyleRef.value, intervals) {
        val style = mapStyleRef.value ?: return@LaunchedEffect
        MapIntervalRenderer.removeIntervalOverlay(style)
        if (intervals.isNotEmpty()) {
            MapIntervalRenderer.renderUngroupedIntervals(style, intervals)
        }
    }

    TrackMapWithDrawer(
        points = points,
        drawerFraction = drawerFraction,
        trackId = "session-detail",
        onMapReady = { _, style -> mapStyleRef.value = style },
        overlayContent = {
            // Interval duration legend overlay
            if (intervals.isNotEmpty()) {
                IntervalMapLegend(
                    intervals = intervals,
                    modifier = Modifier.align(Alignment.TopStart)
                )
            }
        }
    )
}

private fun hexToComposeColor(hex: String): Color {
    val clean = hex.removePrefix("#")
    val r = clean.substring(0, 2).toInt(16)
    val g = clean.substring(2, 4).toInt(16)
    val b = clean.substring(4, 6).toInt(16)
    return Color(r, g, b)
}

/**
 * Overlay legend showing which duration range bands are present in the session.
 * Bands are listed shortest-first; only bands with at least one matching interval are shown.
 */
@Composable
private fun IntervalMapLegend(intervals: List<IntervalSession>, modifier: Modifier = Modifier) {
    // (label, representative duration for color, presence predicate) — ascending duration order
    val bands = listOf(
        Triple("< 3:30 min", 165, intervals.any { it.durationNormalizedSec < 210 }),
        Triple("3:30–5 min",  255, intervals.any { it.durationNormalizedSec in 210..299 }),
        Triple("5–6:30 min",  345, intervals.any { it.durationNormalizedSec in 300..389 }),
        Triple("> 6:30 min",  435, intervals.any { it.durationNormalizedSec >= 390 })
    ).filter { it.third }

    if (bands.isEmpty()) return

    Surface(
        modifier = modifier.padding(8.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = "Intervals",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(2.dp))
            bands.forEach { (label, midSec, _) ->
                val color = hexToComposeColor(MapOverlayUtils.normalizedDurationToColor(midSec))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(color, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}


/** Which comparison pool the Session Detail triangles are currently measured against. */
private enum class ComparisonMode(val label: String) {
    LAST_5("vs. last 5"),
    ALL_PREVIOUS("vs. all")
}

@Composable
private fun ComparisonModeToggle(mode: ComparisonMode, onModeChange: (ComparisonMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ComparisonMode.entries.forEach { option ->
            FilterChip(
                selected = mode == option,
                onClick = { onModeChange(option) },
                label = { Text(option.label) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                )
            )
        }
    }
}

/** Rule-based classification chip (#169), e.g. "Zone 2" or "Intervals". */
@Composable
private fun RideTagChip(tag: String) {
    AssistChip(
        onClick = {},
        label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
    )
}

@Composable
private fun RideSummaryGrid(session: CyclingSession, comparison: SessionComparison?) {
    var comparisonMode by remember { mutableStateOf(ComparisonMode.LAST_5) }
    val avgSpeed = if (session.netDurationSec > 0)
        session.distanceKm / session.netDurationSec * 3600 else 0.0

    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("d. MMM yyyy", Locale("de"))
            .withZone(ZoneId.systemDefault())
    }

    val fatEffScore: Int? = session.fatEfficiencyScore

    val cardiacEfficiency: Double? = if (session.hasPower) {
        val power = session.averagePower
        val hr = session.avgHeartRate
        if (power != null && hr != null && hr != 0) power.toDouble() / hr else null
    } else null

    val totalKcal: Double? = session.energy?.totalKcal?.toDouble()
    val elevGainPer100km: Double? = session.elevationGainM?.let {
        if (session.distanceKm > 0) it / session.distanceKm * 100 else null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = dateFormatter.format(session.sessionStart),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                )
                session.tag?.let { tag -> RideTagChip(tag) }
            }
            ComparisonModeToggle(mode = comparisonMode, onModeChange = { comparisonMode = it })
        }
        Spacer(modifier = Modifier.height(12.dp))

        fun <T> pooled(last5: T, allPrevious: T): T =
            if (comparisonMode == ComparisonMode.LAST_5) last5 else allPrevious

        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                MetricCell(
                    label = "Distance",
                    value = FormatUtils.formatDistance(session.distanceKm),
                    current = session.distanceKm,
                    reference = pooled(comparison?.medianDistanceKmLast5, comparison?.medianDistanceKmAllPrevious),
                    higherIsBetter = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Net time",
                    value = FormatUtils.formatDuration(session.netDurationSec),
                    current = session.netDurationSec.toDouble(),
                    reference = pooled(
                        comparison?.medianNetDurationSecLast5?.toDouble(),
                        comparison?.medianNetDurationSecAllPrevious?.toDouble()
                    ),
                    higherIsBetter = true  // longer net duration is better
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Avg Power",
                    value = if (session.hasPower && session.averagePower != null)
                        FormatUtils.formatPower(session.averagePower) else "—",
                    current = session.averagePower?.toDouble(),
                    reference = pooled(
                        comparison?.medianAvgPowerLast5?.toDouble(),
                        comparison?.medianAvgPowerAllPrevious?.toDouble()
                    ),
                    higherIsBetter = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Calories",
                    value = session.energy?.formatTotalKcal() ?: "—",
                    current = totalKcal,
                    reference = pooled(comparison?.medianTotalKcalLast5, comparison?.medianTotalKcalAllPrevious),
                    higherIsBetter = true  // more calories burned = better workout
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Elevation gain",
                    value = session.elevationGainM?.let { FormatUtils.formatElevationGain(it) } ?: "—",
                    current = session.elevationGainM,
                    reference = pooled(
                        comparison?.medianElevationGainMLast5,
                        comparison?.medianElevationGainMAllPrevious
                    ),
                    higherIsBetter = true  // more climbing is an achievement, not a cost
                )
                val elevGainPer100kmLabel = session.elevationGainM?.let {
                    FormatUtils.formatElevationGainPer100km(it, session.distanceKm)
                }
                if (elevGainPer100kmLabel != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    MetricCell(
                        label = "Elev. gain / 100km",
                        value = elevGainPer100kmLabel,
                        current = elevGainPer100km,
                        reference = pooled(
                            comparison?.medianElevGainPer100kmLast5,
                            comparison?.medianElevGainPer100kmAllPrevious
                        ),
                        higherIsBetter = true  // consistent with raw Elevation gain, above
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Fat efficiency score replaces total duration
                MetricCell(
                    label = "Fat Eff.",
                    value = if (fatEffScore != null) "$fatEffScore" else "—",
                    current = fatEffScore?.toDouble(),
                    reference = pooled(
                        comparison?.medianFatEfficiencyLast5,
                        comparison?.medianFatEfficiencyAllPrevious
                    ),
                    higherIsBetter = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Avg Speed",
                    value = FormatUtils.formatSpeed(avgSpeed),
                    current = avgSpeed,
                    reference = pooled(
                        comparison?.medianAvgSpeedKmhLast5,
                        comparison?.medianAvgSpeedKmhAllPrevious
                    ),
                    higherIsBetter = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Norm. Power",
                    value = if (session.hasPower && session.normalizedPower != null)
                        FormatUtils.formatPower(session.normalizedPower) else "—",
                    current = session.normalizedPower?.toDouble(),
                    reference = pooled(
                        comparison?.medianNormalizedPowerLast5?.toDouble(),
                        comparison?.medianNormalizedPowerAllPrevious?.toDouble()
                    ),
                    higherIsBetter = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Fat / Carbs",
                    value = session.energy?.formatFatCarbGrams() ?: "—"
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Cardiac Eff.",
                    value = cardiacEfficiency?.let { FormatUtils.formatCardiacEfficiency(it) } ?: "—",
                    current = cardiacEfficiency,
                    reference = pooled(
                        comparison?.medianCardiacEfficiencyLast5,
                        comparison?.medianCardiacEfficiencyAllPrevious
                    ),
                    higherIsBetter = true
                )
            }
        }
    }
}

