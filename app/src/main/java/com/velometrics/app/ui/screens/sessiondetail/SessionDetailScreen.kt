package com.velometrics.app.ui.screens.sessiondetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.energy
import com.velometrics.app.domain.service.SessionComparison
import com.velometrics.app.ui.components.*
import com.velometrics.app.util.CyclingConstants.TRACK_FIT_PADDING
import com.velometrics.app.util.FormatUtils
import com.velometrics.app.util.GpsTrackParser
import com.velometrics.app.util.MapOverlayUtils
import org.maplibre.android.camera.CameraUpdateFactory
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (session == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Session not found")
            }
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
                        PowerZoneChart(powerZones = s.powerZoneDistribution!!)
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

                    if (s.hrZoneDistribution != null) {
                        HeartRateZoneChart(hrZones = s.hrZoneDistribution!!)
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
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val boundsRef = remember { mutableStateOf<LatLngBounds?>(null) }
    val density = LocalDensity.current

    LaunchedEffect(mapStyleRef.value, intervals) {
        val style = mapStyleRef.value ?: return@LaunchedEffect
        MapIntervalRenderer.removeIntervalOverlay(style)
        if (intervals.isNotEmpty()) {
            MapIntervalRenderer.renderUngroupedIntervals(style, intervals)
        }
    }

    if (points.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Map,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No GPS data available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val mapHeightPx = with(density) { maxHeight.toPx() }

            // Re-center track whenever the drawer snaps to a new position
            LaunchedEffect(drawerFraction) {
                if (drawerFraction >= 1.0f) return@LaunchedEffect
                val map = mapRef.value ?: return@LaunchedEffect
                val bounds = boundsRef.value ?: return@LaunchedEffect
                val bottomPx = (mapHeightPx * drawerFraction).toInt() + TRACK_FIT_PADDING
                map.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        bounds,
                        TRACK_FIT_PADDING, TRACK_FIT_PADDING, TRACK_FIT_PADDING, bottomPx
                    )
                )
            }

            ComposableMapView(
                modifier = Modifier.fillMaxSize(),
                gesturesEnabled = true,
                onMapReady = { map, style ->
                    mapRef.value = map
                    MapTrackRenderer.addTrack(style, "session-detail", points, "#2196F3")
                    val bounds = GpsTrackParser.computeBounds(points)
                    boundsRef.value = bounds
                    if (bounds != null) {
                        val bottomPx = (mapHeightPx * drawerFraction).toInt() + TRACK_FIT_PADDING
                        map.moveCamera(
                            CameraUpdateFactory.newLatLngBounds(
                                bounds,
                                TRACK_FIT_PADDING, TRACK_FIT_PADDING, TRACK_FIT_PADDING, bottomPx
                            )
                        )
                    }
                    mapStyleRef.value = style
                }
            )

            // Interval duration legend overlay
            if (intervals.isNotEmpty()) {
                IntervalMapLegend(
                    intervals = intervals,
                    modifier = Modifier.align(Alignment.TopStart)
                )
            }
        }
    }
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


@Composable
private fun RideSummaryGrid(session: CyclingSession, comparison: SessionComparison?) {
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = dateFormatter.format(session.sessionStart),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                MetricCell(
                    label = "Distance",
                    value = FormatUtils.formatDistance(session.distanceKm),
                    current = session.distanceKm,
                    median = comparison?.medianDistanceKm,
                    higherIsBetter = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Net time",
                    value = FormatUtils.formatDuration(session.netDurationSec),
                    current = session.netDurationSec.toDouble(),
                    median = comparison?.medianNetDurationSec?.toDouble(),
                    higherIsBetter = true  // longer net duration is better
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Avg Power",
                    value = if (session.hasPower && session.averagePower != null)
                        FormatUtils.formatPower(session.averagePower) else "—",
                    current = session.averagePower?.toDouble(),
                    median = comparison?.medianAvgPower?.toDouble(),
                    higherIsBetter = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Calories",
                    value = session.energy?.formatTotalKcal() ?: "—",
                    current = null,
                    median = null,
                    higherIsBetter = false
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Elevation gain",
                    value = session.elevationGainM?.let { FormatUtils.formatElevationGain(it) } ?: "—",
                    current = null,
                    median = null,
                    higherIsBetter = false
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Fat efficiency score replaces total duration
                MetricCell(
                    label = "Fat Eff.",
                    value = if (fatEffScore != null) "$fatEffScore" else "—",
                    current = fatEffScore?.toDouble(),
                    median = comparison?.medianFatEfficiency,
                    higherIsBetter = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Avg Speed",
                    value = FormatUtils.formatSpeed(avgSpeed),
                    current = avgSpeed,
                    median = comparison?.medianAvgSpeedKmh,
                    higherIsBetter = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Norm. Power",
                    value = if (session.hasPower && session.normalizedPower != null)
                        FormatUtils.formatPower(session.normalizedPower) else "—",
                    current = session.normalizedPower?.toDouble(),
                    median = comparison?.medianNormalizedPower?.toDouble(),
                    higherIsBetter = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Fat / Carbs",
                    value = session.energy?.formatFatCarbGrams() ?: "—",
                    current = null,
                    median = null,
                    higherIsBetter = false
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Cardiac Eff.",
                    value = cardiacEfficiency?.let { FormatUtils.formatCardiacEfficiency(it) } ?: "—",
                    current = cardiacEfficiency,
                    median = comparison?.medianCardiacEfficiency,
                    higherIsBetter = true
                )
            }
        }
    }
}

