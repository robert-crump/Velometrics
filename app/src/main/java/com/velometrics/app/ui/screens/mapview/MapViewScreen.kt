package com.velometrics.app.ui.screens.mapview

import android.Manifest
import android.content.Context
import android.graphics.PointF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.R
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.ui.components.ComposableMapView
import com.velometrics.app.ui.components.MapScaleBar
import com.velometrics.app.ui.components.MapIntervalRenderer
import com.velometrics.app.ui.components.MapOverlayRenderer
import com.velometrics.app.ui.components.MapPoiRenderer
import com.velometrics.app.ui.components.MapTrackRenderer
import com.velometrics.app.ui.components.PoiIcons
import com.velometrics.app.ui.components.PoiPopupCard
import com.velometrics.app.ui.components.openPoiInGoogleMaps
import com.velometrics.app.util.FormatUtils
import com.velometrics.app.util.CyclingConstants.DEFAULT_MAP_ZOOM
import com.velometrics.app.util.CyclingConstants.TRACK_COLORS
import com.velometrics.app.util.CyclingConstants.USER_HEADING_ARROW_ICON_SIZE
import com.velometrics.app.util.GpsTrackParser
import com.velometrics.app.util.HeadingSensor
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.util.MapOverlayUtils
import com.velometrics.app.util.PolylineDecoder
import com.velometrics.app.util.ScaleBarInfo
import com.velometrics.app.util.ScaleBarUtils
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

// Number of recent fixes kept for the accuracy-weighted moving average that smooths the
// on-screen marker. Rendering only — POI-distance/Fast-Way-Home calculations use the
// unsmoothed currentLocation from the ViewModel.
private const val LOCATION_SMOOTHING_WINDOW_SIZE = 5

private data class LocationSample(val lat: Double, val lon: Double, val accuracyM: Float)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapViewScreen(
    viewModel: MapViewViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsState()
    val visibleSessionIds by viewModel.visibleSessionIds.collectAsState()
    val showFlowSegments by viewModel.showFlowSegments.collectAsState()
    val flowSegments by viewModel.flowSegments.collectAsState()

    // Interval overlay state
    val showIntervalOverlay by viewModel.showIntervalOverlay.collectAsState()
    val allIntervals by viewModel.allIntervals.collectAsState()
    val allRepeatedIntervals by viewModel.allRepeatedIntervals.collectAsState()
    val selectedGroup by viewModel.selectedGroup.collectAsState()
    val highlightedIntervalId by viewModel.highlightedIntervalId.collectAsState()

    // Repeated intervals with at least one matched raw interval — drawn once per archetype
    val intervalGroups = remember(allRepeatedIntervals) {
        MapOverlayUtils.groupIntervals(allRepeatedIntervals)
    }

    val showPoiLayer by viewModel.showPoiLayer.collectAsState()
    val visiblePois by viewModel.visiblePois.collectAsState()
    val availablePoiCategories by viewModel.availablePoiCategories.collectAsState()
    val activePoiChip by viewModel.activePoiChip.collectAsState()
    val selectedPoi by viewModel.selectedPoi.collectAsState()

    val currentLocation by viewModel.currentLocation.collectAsState()
    val locationAccuracy by viewModel.locationAccuracy.collectAsState()
    val showLocatingIndicator by viewModel.showLocatingIndicator.collectAsState()

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startLocationUpdates()
    }

    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val context = LocalContext.current

    var showLayersPanel by remember { mutableStateOf(false) }

    var mapAndStyle by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    var scaleBarInfo by remember { mutableStateOf<ScaleBarInfo?>(null) }
    val density = LocalDensity.current.density
    var renderedTrackIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Follow mode: camera recenters on every new fix while true. Disabled only by a
    // user-initiated pan/zoom gesture (detected via camera-move-reason), not by the app's
    // own programmatic camera animations. Re-enabled by tapping locate-me.
    var followMode by remember { mutableStateOf(true) }

    // Device heading (compass direction the phone is facing), shown as an arrow on the
    // user location marker. Null if the rotation vector sensor is unavailable.
    var currentHeading by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val headingSensor = HeadingSensor(context) { heading -> currentHeading = heading }
        headingSensor.start()
        onDispose { headingSensor.stop() }
    }

    // Accuracy-weighted moving average of recent fixes, used only to smooth the rendered
    // marker position — the canonical currentLocation stays unsmoothed for POI-distance and
    // Fast-Way-Home calculations.
    val locationSamples = remember { mutableStateListOf<LocationSample>() }
    var smoothedLocation by remember { mutableStateOf<LatLng?>(null) }
    LaunchedEffect(currentLocation, locationAccuracy) {
        val loc = currentLocation ?: return@LaunchedEffect
        val accuracy = locationAccuracy ?: 1000f
        locationSamples.add(LocationSample(loc.latitude, loc.longitude, accuracy))
        while (locationSamples.size > LOCATION_SMOOTHING_WINDOW_SIZE) {
            locationSamples.removeAt(0)
        }
        var weightSum = 0.0
        var latSum = 0.0
        var lonSum = 0.0
        locationSamples.forEach { sample ->
            val weight = 1.0 / sample.accuracyM.toDouble().coerceAtLeast(1.0).pow(2)
            weightSum += weight
            latSum += sample.lat * weight
            lonSum += sample.lon * weight
        }
        smoothedLocation = LatLng(latSum / weightSum, lonSum / weightSum)
    }

    // Render user location marker at the smoothed position
    LaunchedEffect(smoothedLocation, locationAccuracy, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        val loc = smoothedLocation ?: return@LaunchedEffect
        val accuracy = locationAccuracy ?: 1000f
        try {
            renderUserMarker(context, ms.first, ms.second, loc, accuracy, currentHeading)
        } catch (_: IllegalStateException) {
            return@LaunchedEffect
        }
    }

    // Follow-mode camera: recenter (pan only, preserving zoom) on every new raw fix while
    // follow mode is on. Uses the raw currentLocation, not the smoothed render-only position.
    LaunchedEffect(currentLocation, followMode, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        val loc = currentLocation ?: return@LaunchedEffect
        if (followMode) {
            ms.first.animateCamera(CameraUpdateFactory.newLatLng(loc))
        }
    }

    // Update the heading arrow's rotation cheaply (no layer recreation) as the device turns
    LaunchedEffect(currentHeading, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        val heading = currentHeading ?: return@LaunchedEffect
        try {
            updateHeadingArrow(context, ms.second, heading)
        } catch (_: IllegalStateException) {
            return@LaunchedEffect
        }
    }

    // rememberUpdatedState for click listener (avoids stale captures)
    val currentShowInterval by rememberUpdatedState(showIntervalOverlay)
    val currentGroups by rememberUpdatedState(intervalGroups)
    val currentShowPoi by rememberUpdatedState(showPoiLayer)
    val currentVisiblePois by rememberUpdatedState(visiblePois)

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

    // Flow segments overlay sync — viewport-scoped, only active when toggle is on
    LaunchedEffect(flowSegments, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        MapOverlayRenderer.removeFlowSegments(ms.second)
        if (flowSegments.isNotEmpty()) {
            MapOverlayRenderer.renderFlowSegments(ms.second, flowSegments)
        }
    }

    // POI layer sync
    LaunchedEffect(showPoiLayer, visiblePois, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        MapPoiRenderer.removePois(ms.second)
        if (showPoiLayer && visiblePois.isNotEmpty()) {
            MapPoiRenderer.addPois(context, ms.second, visiblePois)
        }
    }

    // POI highlight sync
    LaunchedEffect(selectedPoi, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        MapPoiRenderer.highlightPoi(context, ms.second, selectedPoi?.poi)
    }

    // Interval overlay sync
    LaunchedEffect(showIntervalOverlay, intervalGroups, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        MapIntervalRenderer.removeIntervalOverlay(ms.second)
        if (showIntervalOverlay) {
            MapIntervalRenderer.renderRepeatedIntervals(ms.second, intervalGroups)
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

    // Map click listener for POI and interval tap interaction
    LaunchedEffect(mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        ms.first.addOnMapClickListener { latLng ->
            val screenPoint = ms.first.projection.toScreenLocation(latLng)

            // POI cluster tap → zoom in
            if (currentShowPoi) {
                val clusterFeatures = ms.first.queryRenderedFeatures(screenPoint, MapPoiRenderer.POI_CLUSTER_LAYER)
                if (clusterFeatures.isNotEmpty()) {
                    val feature = clusterFeatures[0]
                    val geo = feature.geometry()
                    val lat = if (geo is Point) geo.latitude() else latLng.latitude
                    val lon = if (geo is Point) geo.longitude() else latLng.longitude
                    ms.first.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), ms.first.cameraPosition.zoom + 2.0), 500
                    )
                    return@addOnMapClickListener true
                }

                // Individual POI tap → popup
                val poiFeatures = ms.first.queryRenderedFeatures(screenPoint, MapPoiRenderer.POI_LAYER)
                if (poiFeatures.isNotEmpty()) {
                    val poiId = poiFeatures[0].getStringProperty("poiId")
                    val poi = currentVisiblePois.find { it.poiId == poiId }
                    if (poi != null) {
                        viewModel.selectPoiFromMap(poi)
                        ms.first.animateCamera(
                            CameraUpdateFactory.newLatLng(LatLng(poi.lat, poi.lon))
                        )
                        return@addOnMapClickListener true
                    }
                }
            }

            if (!currentShowInterval) {
                viewModel.dismissPoi()
                return@addOnMapClickListener false
            }

            // Query repeated-interval layer
            val groupedFeatures = ms.first.queryRenderedFeatures(screenPoint, "interval-grouped-layer")
            if (groupedFeatures.isNotEmpty()) {
                val repeatedIntervalIdStr = groupedFeatures[0].getStringProperty("repeatedIntervalId")
                val group = currentGroups.find { it.id.toString() == repeatedIntervalIdStr }
                if (group != null) { viewModel.selectGroup(group) }
                return@addOnMapClickListener true
            }

            viewModel.clearSelection()
            viewModel.dismissPoi()
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
                viewModel.updateViewportBounds(map.projection.visibleRegion.latLngBounds)
                scaleBarInfo = computeScaleBarInfo(map, density)
                map.addOnCameraIdleListener {
                    viewModel.updateViewportBounds(map.projection.visibleRegion.latLngBounds)
                    scaleBarInfo = computeScaleBarInfo(map, density)
                }
                map.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        followMode = false
                    }
                }
            }
        )

        // Chip rows stacked at top of map
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
        ) {
            // POI category chip row — horizontally scrollable, single-select
            LazyRow(
                modifier = Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                item {
                    FilterChip(
                        selected = activePoiChip == MapViewViewModel.ALL_POIS_CHIP,
                        onClick = { viewModel.selectPoiChip(MapViewViewModel.ALL_POIS_CHIP) },
                        label = { Text(MapViewViewModel.ALL_POIS_CHIP) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        )
                    )
                }
                items(availablePoiCategories) { category ->
                    FilterChip(
                        selected = activePoiChip == category,
                        onClick = { viewModel.selectPoiChip(category) },
                        label = { Text(FormatUtils.categoryDisplayName(category)) },
                        leadingIcon = {
                            Icon(
                                imageVector = PoiIcons.forCategory(category),
                                contentDescription = null,
                                tint = Color.White
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        )
                    )
                }
            }

            // Layers FAB — top-aligned, right-of-row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                SmallFloatingActionButton(
                    onClick = { showLayersPanel = true },
                    modifier = Modifier.padding(end = 16.dp)
                ) {
                    Icon(Icons.Default.Layers, contentDescription = "Toggle layers")
                }
            }

            // POI popup card — sits below the chip rows
            selectedPoi?.let { poiWD ->
                PoiPopupCard(
                    poiWithDistances = poiWD,
                    onOpenInMaps = { openPoiInGoogleMaps(context, poiWD) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        // Distance scale bar - bottom left, stacked above the MapLibre attribution control
        scaleBarInfo?.let { info ->
            MapScaleBar(
                widthDp = (info.widthPx / density).dp,
                distanceLabel = info.label,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, bottom = 40.dp)
            )
        }

        // Stacked FABs - bottom right
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (showLocatingIndicator) {
                AssistChip(
                    onClick = {},
                    label = { Text("Locating…") },
                    leadingIcon = {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                )
            }
            FloatingActionButton(
                onClick = {
                    followMode = true
                    val loc = currentLocation
                    val ms = mapAndStyle
                    if (loc != null && ms != null) {
                        ms.first.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(loc, DEFAULT_MAP_ZOOM + 2.0)
                        )
                    }
                }
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Locate me")
            }
        }

        // Layers panel overlay — scrim + centered card, confined to this content area so the
        // bottom navigation bar (outside MapViewScreen) remains tappable while it's open.
        if (showLayersPanel) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { showLayersPanel = false }
            ) {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {}
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Layers",
                                style = MaterialTheme.typography.titleMedium
                            )
                            IconButton(onClick = { showLayersPanel = false }) {
                                Icon(Icons.Default.Close, contentDescription = "Close layers panel")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

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

                        // Flow segments toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Flow segments", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = showFlowSegments,
                                onCheckedChange = { viewModel.toggleFlowSegments() }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrototypeGroupSheet(
    group: RepeatedInterval,
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
                text = "${group.name} — ${group.intervals.size} intervals",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Avg: ${MapOverlayUtils.formatDurationMinSec(MapOverlayUtils.avgDurationNormalizedSec(group))} min / ${FormatUtils.formatPower(MapOverlayUtils.avgPower(group))}",
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

private const val SCALE_BAR_MAX_WIDTH_DP = 80f

/** Computes the scale bar's bar width and label from the map's current zoom and latitude. */
private fun computeScaleBarInfo(map: MapLibreMap, density: Float): ScaleBarInfo {
    val centerY = map.height / 2f
    val left = map.projection.fromScreenLocation(PointF(0f, centerY))
    val right = map.projection.fromScreenLocation(PointF(100f, centerY))
    val metersPerPixel = left.distanceTo(right) / 100.0
    val maxWidthPx = SCALE_BAR_MAX_WIDTH_DP * density
    return ScaleBarUtils.computeScaleBar(metersPerPixel, maxWidthPx.toDouble())
}

private const val USER_LOCATION_SOURCE = "user-location-source"
private const val USER_LOCATION_HEADING_LAYER = "user-location-heading"
private const val USER_HEADING_ARROW_ICON = "user-heading-arrow-icon"

private fun renderUserMarker(
    context: Context,
    map: MapLibreMap,
    style: Style,
    location: LatLng,
    accuracyM: Float,
    heading: Float?
) {
    val sourceId = USER_LOCATION_SOURCE
    val outerLayerId = "user-location-outer"
    val innerLayerId = "user-location-inner"

    val feature = Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude))
    val source = GeoJsonSource(sourceId, feature)

    // Remove existing layers/source if present (heading layer must go before its source)
    if (style.getLayer(USER_LOCATION_HEADING_LAYER) != null) style.removeLayer(USER_LOCATION_HEADING_LAYER)
    if (style.getLayer(outerLayerId) != null) style.removeLayer(outerLayerId)
    if (style.getLayer(innerLayerId) != null) style.removeLayer(innerLayerId)
    if (style.getSource(sourceId) != null) style.removeSource(sourceId)

    style.addSource(source)

    // Location.getAccuracy() is a 68%-confidence radius by definition, so ~1-in-3 fixes
    // legitimately land outside it with no bug. Doubling it approximates a ~95%-confidence
    // radius so the dot reliably reads as "inside the circle".
    val displayRadiusM = accuracyM.toDouble() * 2.0

    // Web Mercator tiles double in resolution with each zoom level, so the screen-pixel
    // radius for a constant ground radius is `radiusAtZoom0 * 2^zoom`. Express that as an
    // exponential (base 2) zoom interpolation so the circle keeps representing the same
    // real-world accuracy radius — and visibly grows/shrinks — as the map is zoomed,
    // mirroring the Google Maps "my location" accuracy circle.
    val latRad = Math.toRadians(location.latitude)
    val radiusAtZoom0 = (displayRadiusM * 256.0 /
            (2 * Math.PI * com.velometrics.app.util.GeoUtils.EARTH_RADIUS_M * cos(latRad))).toFloat()
    val outerRadius = Expression.interpolate(
        Expression.exponential(2f),
        Expression.zoom(),
        Expression.stop(0f, radiusAtZoom0),
        Expression.stop(20f, radiusAtZoom0 * 2f.pow(20))
    )

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

    if (heading != null) {
        registerHeadingArrowIcon(context, style)
        val headingLayer = SymbolLayer(USER_LOCATION_HEADING_LAYER, sourceId).apply {
            setProperties(
                PropertyFactory.iconImage(USER_HEADING_ARROW_ICON),
                PropertyFactory.iconSize(USER_HEADING_ARROW_ICON_SIZE),
                PropertyFactory.iconRotate(heading),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        }
        style.addLayer(headingLayer)
    }
}

private fun registerHeadingArrowIcon(context: Context, style: Style) {
    if (style.getImage(USER_HEADING_ARROW_ICON) != null) return
    val drawable = ContextCompat.getDrawable(context, R.drawable.ic_heading_arrow) ?: return
    style.addImage(USER_HEADING_ARROW_ICON, drawable.toBitmap())
}

/** Cheaply updates the heading arrow's rotation, creating the layer if it doesn't exist yet. */
private fun updateHeadingArrow(context: Context, style: Style, heading: Float) {
    if (style.getSource(USER_LOCATION_SOURCE) == null) return

    val existing = style.getLayer(USER_LOCATION_HEADING_LAYER) as? SymbolLayer
    if (existing != null) {
        existing.setProperties(PropertyFactory.iconRotate(heading))
        return
    }

    registerHeadingArrowIcon(context, style)
    val headingLayer = SymbolLayer(USER_LOCATION_HEADING_LAYER, USER_LOCATION_SOURCE).apply {
        setProperties(
            PropertyFactory.iconImage(USER_HEADING_ARROW_ICON),
            PropertyFactory.iconSize(USER_HEADING_ARROW_ICON_SIZE),
            PropertyFactory.iconRotate(heading),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true)
        )
    }
    style.addLayer(headingLayer)
}

