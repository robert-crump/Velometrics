package com.cyclegraph.app.ui.screens.mapview

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cyclegraph.app.domain.model.IntervalSession
import com.cyclegraph.app.ui.components.ComposableMapView
import com.cyclegraph.app.ui.components.MapHeatmapRenderer
import com.cyclegraph.app.ui.components.MapIntervalRenderer
import com.cyclegraph.app.ui.components.MapOverlayRenderer
import com.cyclegraph.app.ui.components.MapTrackRenderer
import com.cyclegraph.app.util.CyclingConstants.DEFAULT_MAP_ZOOM
import com.cyclegraph.app.util.CyclingConstants.INTERVAL_DURATION_COLOR_RAMP
import com.cyclegraph.app.util.CyclingConstants.SPEED_COLOR_MAP
import com.cyclegraph.app.util.CyclingConstants.STOP_COLOR_LONG
import com.cyclegraph.app.util.CyclingConstants.STOP_COLOR_MEDIUM
import com.cyclegraph.app.util.CyclingConstants.STOP_COLOR_SHORT
import com.cyclegraph.app.util.CyclingConstants.TRACK_COLORS
import com.cyclegraph.app.util.FormatUtils
import com.cyclegraph.app.util.GpsTrackParser
import com.cyclegraph.app.util.IntervalGroup
import com.cyclegraph.app.util.MapOverlayUtils
import kotlin.math.cos
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapViewScreen(
    viewModel: MapViewViewModel = hiltViewModel()
) {
    val edges by viewModel.allEdges.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val visibleSessionIds by viewModel.visibleSessionIds.collectAsState()
    val showAllRidesLayer by viewModel.showAllRidesLayer.collectAsState()
    val showSpeedOverlay by viewModel.showSpeedOverlay.collectAsState()
    val selectedSpeedCategories by viewModel.selectedSpeedCategories.collectAsState()
    val showStopSpots by viewModel.showStopSpots.collectAsState()
    val showHeatmap by viewModel.showHeatmap.collectAsState()
    val heatmapLoading by viewModel.heatmapLoading.collectAsState()
    val heatmapCells by viewModel.heatmapCells.collectAsState()

    // Interval overlay state
    val showIntervalOverlay by viewModel.showIntervalOverlay.collectAsState()
    val allIntervals by viewModel.allIntervals.collectAsState()
    val allPrototypeRoutes by viewModel.allPrototypeRoutes.collectAsState()
    val selectedInterval by viewModel.selectedInterval.collectAsState()
    val selectedGroup by viewModel.selectedGroup.collectAsState()
    val highlightedIntervalId by viewModel.highlightedIntervalId.collectAsState()

    // Derived interval grouping
    val intervalData = remember(allIntervals, allPrototypeRoutes) {
        MapOverlayUtils.groupIntervals(allIntervals, allPrototypeRoutes)
    }
    val intervalGroups = intervalData.first
    val ungroupedIntervals = intervalData.second

    val currentLocation by viewModel.currentLocation.collectAsState()
    val locationAccuracy by viewModel.locationAccuracy.collectAsState()
    val isAcquiringGps by viewModel.isAcquiringGps.collectAsState()
    val poorGpsSnackbar by viewModel.poorGpsSnackbar.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(poorGpsSnackbar) {
        if (poorGpsSnackbar) {
            snackbarHostState.showSnackbar("Poor gps quality.")
            viewModel.dismissPoorGpsSnackbar()
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startLocationUpdates()
    }

    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    var mapAndStyle by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var renderedTrackIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var fineLocationZoomedIn by remember { mutableStateOf(false) }

    // Render user location marker; re-center once a fine fix (accuracy ≤ 50 m) is obtained
    LaunchedEffect(currentLocation, locationAccuracy, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        val loc = currentLocation ?: return@LaunchedEffect
        val accuracy = locationAccuracy ?: 1000f
        renderUserMarker(ms.first, ms.second, loc, accuracy)
        if (!fineLocationZoomedIn && accuracy <= 50f) {
            fineLocationZoomedIn = true
            ms.first.animateCamera(
                CameraUpdateFactory.newLatLngZoom(loc, DEFAULT_MAP_ZOOM + 2.0)
            )
        }
    }

    // rememberUpdatedState for click listener (avoids stale captures)
    val currentShowInterval by rememberUpdatedState(showIntervalOverlay)
    val currentGroups by rememberUpdatedState(intervalGroups)
    val currentAllIntervals by rememberUpdatedState(allIntervals)

    // Sync visible tracks with map
    LaunchedEffect(visibleSessionIds, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        val style = ms.second

        // Remove tracks no longer visible
        val toRemove = renderedTrackIds.filter { trackId ->
            val sessionId = trackId.toLongOrNull()
            sessionId == null || sessionId !in visibleSessionIds
        }
        toRemove.forEach { MapTrackRenderer.removeTrack(style, it) }

        // Add newly visible tracks — parse GPS JSON off the main thread
        val newRendered = mutableSetOf<String>()
        visibleSessionIds.forEach { sessionId ->
            val trackId = sessionId.toString()
            val session = sessions.find { it.id == sessionId }
            if (session != null && trackId !in renderedTrackIds - toRemove.toSet()) {
                val points = withContext(Dispatchers.Default) {
                    GpsTrackParser.parse(session.gpsTrack)
                }
                if (points.size >= 2) {
                    val colorIndex = session.id.toInt().mod(TRACK_COLORS.size)
                    MapTrackRenderer.addTrack(style, trackId, points, TRACK_COLORS[colorIndex])
                }
            }
            newRendered.add(trackId)
        }

        renderedTrackIds = newRendered
    }

    // All rides layer sync
    LaunchedEffect(showAllRidesLayer, edges, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        MapOverlayRenderer.removeEdges(ms.second)
        if (showAllRidesLayer && edges.isNotEmpty()) {
            MapOverlayRenderer.renderEdges(ms.second, edges)
        }
    }

    // Speed overlay sync — re-render when selected categories or edges change
    LaunchedEffect(showSpeedOverlay, selectedSpeedCategories, edges, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        MapOverlayRenderer.removeSpeedOverlay(ms.second)
        if (showSpeedOverlay) {
            MapOverlayRenderer.renderSpeedOverlay(ms.second, edges, selectedSpeedCategories)
        }
    }

    // Stop spots sync
    LaunchedEffect(showStopSpots, edges, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        MapOverlayRenderer.removeStopSpots(ms.second)
        if (showStopSpots) {
            MapOverlayRenderer.renderStopSpots(ms.second, edges)
        }
    }

    // Heatmap overlay sync
    LaunchedEffect(showHeatmap, heatmapCells, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        MapHeatmapRenderer.removeHeatmap(ms.second)
        if (showHeatmap && heatmapCells.isNotEmpty()) {
            MapHeatmapRenderer.renderHeatmap(ms.second, heatmapCells)
        }
    }

    // Interval overlay sync
    LaunchedEffect(showIntervalOverlay, ungroupedIntervals, intervalGroups, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        MapIntervalRenderer.removeIntervalOverlay(ms.second)
        if (showIntervalOverlay) {
            MapIntervalRenderer.renderUngroupedIntervals(ms.second, ungroupedIntervals)
            MapIntervalRenderer.renderGroupedIntervals(ms.second, intervalGroups)
            MapIntervalRenderer.renderGroupLabels(ms.second, intervalGroups)
        }
    }

    // Highlight sync
    LaunchedEffect(highlightedIntervalId, allIntervals, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        MapIntervalRenderer.removeHighlight(ms.second)
        val id = highlightedIntervalId
        if (id != null) {
            val interval = allIntervals.find { it.id == id }
            if (interval != null) {
                MapIntervalRenderer.renderHighlight(ms.second, interval)
            }
        }
    }

    // Map click listener for interval tap interaction
    LaunchedEffect(mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        ms.first.addOnMapClickListener { latLng ->
            if (!currentShowInterval) return@addOnMapClickListener false
            val screenPoint = ms.first.projection.toScreenLocation(latLng)

            // Query grouped layer first (higher priority)
            val groupedFeatures = ms.first.queryRenderedFeatures(screenPoint, "interval-grouped-layer")
            if (groupedFeatures.isNotEmpty()) {
                val protoIdStr = groupedFeatures[0].getStringProperty("prototypeId")
                val group = currentGroups.find { it.prototypeRoute.id.toString() == protoIdStr }
                if (group != null) { viewModel.selectGroup(group) }
                return@addOnMapClickListener true
            }

            // Then ungrouped layer
            val ungroupedFeatures = ms.first.queryRenderedFeatures(screenPoint, "interval-ungrouped-layer")
            if (ungroupedFeatures.isNotEmpty()) {
                val idStr = ungroupedFeatures[0].getStringProperty("intervalId")
                val interval = currentAllIntervals.find { it.id.toString() == idStr }
                if (interval != null) { viewModel.selectInterval(interval) }
                return@addOnMapClickListener true
            }

            viewModel.clearSelection()
            false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Full-screen map
        ComposableMapView(
            modifier = Modifier.fillMaxSize(),
            gesturesEnabled = true,
            onMapReady = { map, style ->
                mapAndStyle = Pair(map, style)
            }
        )

        // Interval detail card (ungrouped interval tap)
        if (selectedInterval != null) {
            IntervalDetailCard(
                interval = selectedInterval!!,
                onDismiss = { viewModel.clearSelection() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp, start = 16.dp, end = 16.dp)
            )
        }

        // Stacked FABs - bottom right: locate-me above layers
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SmallFloatingActionButton(
                onClick = {
                    viewModel.startLocationUpdates()
                    val loc = currentLocation
                    val ms = mapAndStyle
                    if (loc != null && ms != null) {
                        ms.first.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(loc, DEFAULT_MAP_ZOOM + 2.0)
                        )
                    }
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Locate me")
                    if (isAcquiringGps) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            FloatingActionButton(onClick = { showBottomSheet = true }) {
                Icon(Icons.Default.Layers, contentDescription = "Toggle layers")
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        )
    }

    // Bottom sheet (layers)
    if (showBottomSheet) {
        val sheetState = rememberModalBottomSheetState()

        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState = sheetState
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                // --- Overlay controls section ---
                Text(
                    text = "Overlays",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                // All Rides toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("All rides (Robert)", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = showAllRidesLayer,
                        onCheckedChange = { viewModel.toggleAllRidesLayer() }
                    )
                }

                // Speed Overlay toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Speed Overlay", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = showSpeedOverlay,
                        onCheckedChange = { viewModel.toggleSpeedOverlay() }
                    )
                }

                // Stop Spots toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Stop Spots", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = showStopSpots,
                        onCheckedChange = { viewModel.toggleStopSpots() }
                    )
                }

                // Heatmap toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Heatmap", style = MaterialTheme.typography.bodyMedium)
                        if (heatmapLoading) {
                            Spacer(modifier = Modifier.width(8.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                    Switch(
                        checked = showHeatmap,
                        onCheckedChange = { viewModel.toggleHeatmap() }
                    )
                }

                // Intervals toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Intervals", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = showIntervalOverlay,
                        onCheckedChange = { viewModel.toggleIntervalOverlay() }
                    )
                }

                if (showAllRidesLayer || showSpeedOverlay || showStopSpots || showHeatmap || showIntervalOverlay) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LegendCard(
                        showAllRides = showAllRidesLayer,
                        showSpeed = showSpeedOverlay,
                        showStops = showStopSpots,
                        showHeatmap = showHeatmap,
                        showIntervals = showIntervalOverlay,
                        selectedSpeedCategories = selectedSpeedCategories,
                        onSpeedCategoryClick = { viewModel.toggleSpeedCategory(it) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Grouped prototype detail bottom sheet
    if (selectedGroup != null) {
        PrototypeGroupSheet(
            group = selectedGroup!!,
            highlightedIntervalId = highlightedIntervalId,
            onIntervalTap = { interval -> viewModel.highlightInterval(interval.id) },
            onDismiss = { viewModel.clearSelection() }
        )
    }
}

@Composable
private fun IntervalDetailCard(
    interval: IntervalSession,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = FormatUtils.formatDate(interval.startTimestamp),
                    style = MaterialTheme.typography.titleSmall
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Duration: ${FormatUtils.formatDuration(interval.durationSec)} (norm: ${FormatUtils.formatDuration(interval.durationNormalizedSec)})",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Distance: ${FormatUtils.formatDistance(interval.distanceM / 1000.0)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Avg Power: ${FormatUtils.formatPower(interval.avgPower)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Avg Speed: ${FormatUtils.formatSpeed(interval.avgSpeedKmh)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Direction: ${interval.direction}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrototypeGroupSheet(
    group: IntervalGroup,
    highlightedIntervalId: Long?,
    onIntervalTap: (IntervalSession) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val sortedIntervals = remember(group) {
        group.intervals.sortedByDescending { it.startTimestamp }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "${group.prototypeRoute.name} — ${group.intervals.size} intervals",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Avg: ${MapOverlayUtils.formatDurationMinSec(group.avgDurationNormalizedSec)} min / ${FormatUtils.formatPower(group.avgPower)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(sortedIntervals, key = { it.id }) { interval ->
                    val isHighlighted = interval.id == highlightedIntervalId
                    val bgColor = if (isHighlighted) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    } else {
                        Color.Transparent
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgColor, RoundedCornerShape(8.dp))
                            .clickable { onIntervalTap(interval) }
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = FormatUtils.formatDate(interval.startTimestamp),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = FormatUtils.formatDuration(interval.durationSec),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(60.dp)
                        )
                        Text(
                            text = "(${FormatUtils.formatDuration(interval.durationNormalizedSec)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(70.dp)
                        )
                        Text(
                            text = FormatUtils.formatPower(interval.avgPower),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(50.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** Convert a hex color string to its grayscale equivalent (luminance-based). */
private fun toGrayscaleColor(hexColor: String): Color {
    val argb = android.graphics.Color.parseColor(hexColor)
    val r = android.graphics.Color.red(argb) / 255f
    val g = android.graphics.Color.green(argb) / 255f
    val b = android.graphics.Color.blue(argb) / 255f
    val lum = 0.299f * r + 0.587f * g + 0.114f * b
    return Color(lum, lum, lum)
}

@Composable
private fun LegendCard(
    showAllRides: Boolean,
    showSpeed: Boolean,
    showStops: Boolean,
    showHeatmap: Boolean = false,
    showIntervals: Boolean = false,
    selectedSpeedCategories: Set<String> = emptySet(),
    onSpeedCategoryClick: (String) -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (showAllRides) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(Color(android.graphics.Color.parseColor("#4CAF50")), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "All rides (Robert)", style = MaterialTheme.typography.labelMedium)
                }
            }

            if (showAllRides && showSpeed) Spacer(modifier = Modifier.height(8.dp))

            if (showSpeed) {
                Text(
                    text = "Speed — tap to toggle category",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Speed category key → legend label mapping
                val speedBins = listOf(
                    "0-20 km/h" to "0-20",
                    "20-25 km/h" to "20-25",
                    "25-30 km/h" to "25-30",
                    "30-40 km/h" to "30-40",
                    "40-50 km/h" to "40+"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    speedBins.forEach { (categoryKey, label) ->
                        val hexColor = SPEED_COLOR_MAP[categoryKey]!!
                        val isActive = categoryKey in selectedSpeedCategories
                        val dotColor = if (isActive) {
                            Color(android.graphics.Color.parseColor(hexColor))
                        } else {
                            toGrayscaleColor(hexColor)
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSpeedCategoryClick(categoryKey) }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (isActive) 20.dp else 16.dp)
                                    .background(dotColor, CircleShape)
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isActive) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (showSpeed && showStops) {
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (showStops) {
                Text(
                    text = "Stops",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val stopTypes = listOf(
                        "Low" to STOP_COLOR_SHORT,
                        "Medium" to STOP_COLOR_MEDIUM,
                        "High" to STOP_COLOR_LONG
                    )
                    stopTypes.forEach { (label, color) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        Color(android.graphics.Color.parseColor(color)),
                                        CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            if ((showSpeed || showStops) && (showHeatmap || showIntervals)) {
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (showHeatmap) {
                Text(
                    text = "Heatmap — visit density",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val heatmapColors = listOf(
                        "#001464", "#0046C8", "#1E82FF", "#82BEFF", "#DCE8FF"
                    )
                    heatmapColors.forEach { hex ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(10.dp)
                                .background(Color(android.graphics.Color.parseColor(hex)))
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Low", style = MaterialTheme.typography.labelSmall)
                    Text("High", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (showHeatmap && showIntervals) {
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (showIntervals) {
                Text(
                    text = "Intervals",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "2 min",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        INTERVAL_DURATION_COLOR_RAMP.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(12.dp)
                                    .background(Color(android.graphics.Color.parseColor(color)))
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "8+ min",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

private fun renderUserMarker(map: MapLibreMap, style: Style, location: LatLng, accuracyM: Float) {
    val sourceId = "user-location-source"
    val outerLayerId = "user-location-outer"
    val innerLayerId = "user-location-inner"

    val feature = Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude))
    val source = GeoJsonSource(sourceId, feature)

    // Remove existing layers/source if present
    if (style.getLayer(outerLayerId) != null) style.removeLayer(outerLayerId)
    if (style.getLayer(innerLayerId) != null) style.removeLayer(innerLayerId)
    if (style.getSource(sourceId) != null) style.removeSource(sourceId)

    style.addSource(source)

    // Bin the GPS accuracy to reduce visual jitter
    val binRadiusM = when {
        accuracyM <= 10f  -> 10.0
        accuracyM <= 20f  -> 20.0
        accuracyM <= 50f  -> 50.0
        accuracyM <= 100f -> 100.0
        else              -> 200.0
    }

    // Convert the bin radius from metres to screen pixels at the current zoom and latitude
    val zoom = map.cameraPosition.zoom
    val latRad = Math.toRadians(location.latitude)
    val pixelsPerMeter = 256.0 * Math.pow(2.0, zoom) /
            (2 * Math.PI * com.cyclegraph.app.util.GeoUtils.EARTH_RADIUS_M * cos(latRad))
    val outerRadius = (binRadiusM * pixelsPerMeter).toFloat().coerceAtLeast(12f)

    val outerCircle = CircleLayer(outerLayerId, sourceId).apply {
        setProperties(
            PropertyFactory.circleRadius(outerRadius),
            PropertyFactory.circleColor("#42A5F5"),
            PropertyFactory.circleOpacity(0.25f)
        )
    }

    // Inner dot — 8px radius, fully opaque, with white stroke
    val innerCircle = CircleLayer(innerLayerId, sourceId).apply {
        setProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor("#42A5F5"),
            PropertyFactory.circleOpacity(1.0f),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor("#FFFFFF")
        )
    }

    style.addLayer(outerCircle)
    style.addLayer(innerCircle)
}

