package com.velometrics.app.ui.screens.mapview

import android.Manifest
import android.content.Context
import android.graphics.PointF
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Checkbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.R
import com.velometrics.app.domain.model.GpxPoiItem
import com.velometrics.app.domain.model.GpxTrack
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.PoiWithDistances
import com.velometrics.app.ui.components.ComposableMapView
import com.velometrics.app.ui.components.MapScaleBar
import com.velometrics.app.ui.components.MapIntervalRenderer
import com.velometrics.app.ui.components.MapOverlayRenderer
import com.velometrics.app.ui.components.MapPoiRenderer
import com.velometrics.app.ui.components.MapTrackRenderer
import com.velometrics.app.ui.components.PoiIcons
import com.velometrics.app.ui.components.PoiPopupCard
import com.velometrics.app.ui.components.openPoiInGoogleMaps
import com.velometrics.app.ui.intent.GpxIntentViewModel
import com.velometrics.app.ui.shared.DiscoveryScoreResult
import com.velometrics.app.ui.shared.GpxSharedViewModel
import com.velometrics.app.ui.shared.RouteCoverage
import com.velometrics.app.ui.shared.SpeedPowerEstimateResult
import com.velometrics.app.util.FormatUtils
import com.velometrics.app.util.OpeningHoursUtils
import com.velometrics.app.util.CyclingConstants.DEFAULT_MAP_ZOOM
import com.velometrics.app.util.CyclingConstants.FAST_WAY_HOME_TRACK_COLOR
import com.velometrics.app.util.CyclingConstants.FAST_WAY_HOME_TRACK_WIDTH
import com.velometrics.app.util.CyclingConstants.PLAN_A_RIDE_CORRIDOR_COLOR
import com.velometrics.app.util.CyclingConstants.PLAN_A_RIDE_DISCOVERY_COLOR
import com.velometrics.app.util.CyclingConstants.PLAN_A_RIDE_TRACK_COLORS
import com.velometrics.app.util.CyclingConstants.PLAN_A_RIDE_TRACK_WIDTH
import com.velometrics.app.util.CyclingConstants.PLAN_A_RIDE_DEFAULT_DISTANCE_KM
import com.velometrics.app.util.CyclingConstants.PLAN_A_RIDE_DEFAULT_EXPLORE_WEIGHT
import com.velometrics.app.util.CyclingConstants.PLAN_A_RIDE_MAX_EXPLORE_WEIGHT
import com.velometrics.app.domain.service.RankedCandidate
import com.velometrics.app.domain.service.RideDirection
import com.velometrics.app.util.CyclingConstants.NAV_TRACK_COLOR
import com.velometrics.app.util.CyclingConstants.NAV_TRACK_WIDTH
import com.velometrics.app.util.CyclingConstants.TRACK_COLORS
import com.velometrics.app.util.CyclingConstants.TRACK_FIT_PADDING
import com.velometrics.app.util.CyclingConstants.USER_HEADING_ARROW_ICON_SIZE
import com.velometrics.app.util.GpxAnalysisUtils
import com.velometrics.app.util.GpsTrackParser
import com.velometrics.app.util.HeadingSensor
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.util.MapOverlayUtils
import com.velometrics.app.util.PolylineDecoder
import com.velometrics.app.util.ScaleBarInfo
import com.velometrics.app.util.ScaleBarUtils
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import java.text.NumberFormat
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
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

private const val GPX_TRACK_LAYER_ID = "gpx-shared-track"
private const val GPX_SEGMENT_HIGHLIGHT_ID = "gpx-segment-highlight"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapViewScreen(
    viewModel: MapViewViewModel = hiltViewModel(),
    fastWayHomeViewModel: FastWayHomeViewModel = hiltViewModel(),
    planARideViewModel: PlanARideViewModel = hiltViewModel(),
    gpxSharedViewModel: GpxSharedViewModel = hiltViewModel(LocalActivity.current as ComponentActivity),
    gpxIntentViewModel: GpxIntentViewModel = hiltViewModel(LocalActivity.current as ComponentActivity)
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

    // Fast Way Home state
    val fastWayHomeResult by fastWayHomeViewModel.fastWayHomeResult.collectAsState()
    val fastWayHomeMessage by fastWayHomeViewModel.fastWayHomeMessage.collectAsState()
    val isFindingFastWayHome by fastWayHomeViewModel.isFindingFastWayHome.collectAsState()
    val fastWayHomeLocation by fastWayHomeViewModel.homeLocation.collectAsState()
    val showFastWayHomeCard = isFindingFastWayHome || fastWayHomeResult != null || fastWayHomeMessage != null

    // Plan a ride state
    val planCandidate by planARideViewModel.candidate.collectAsState()
    val isGeneratingPlan by planARideViewModel.isGenerating.collectAsState()
    val planMessage by planARideViewModel.message.collectAsState()
    val showPlanARideCard = isGeneratingPlan || planCandidate != null || planMessage != null
    var showPlanDistanceDialog by remember { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startLocationUpdates()
    }

    val gpxTrack by gpxSharedViewModel.gpxTrack.collectAsState()
    val gpxFileName by gpxSharedViewModel.gpxFileName.collectAsState()
    val showGpxPoisOverlay by gpxSharedViewModel.showGpxPoisOverlay.collectAsState()
    val gpxDiscoveryScore by gpxSharedViewModel.discoveryScore.collectAsState()
    val gpxSpeedPowerEstimate by gpxSharedViewModel.speedPowerEstimate.collectAsState()
    val gpxPois by gpxSharedViewModel.gpxPois.collectAsState()
    val isLoadingPois by gpxSharedViewModel.isLoadingPois.collectAsState()
    val gpxPoiItems by gpxSharedViewModel.gpxPoiItems.collectAsState()
    val locationAvailable by gpxSharedViewModel.locationAvailable.collectAsState()
    val selectedPoiItem by gpxSharedViewModel.selectedPoiItem.collectAsState()
    val gpxSegmentPoints by gpxSharedViewModel.gpxSegmentPoints.collectAsState()
    val pendingGpxUri by gpxIntentViewModel.pendingGpxUri.collectAsState()

    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    LaunchedEffect(currentLocation) {
        gpxSharedViewModel.updateUserLocation(currentLocation)
    }

    val context = LocalContext.current

    LaunchedEffect(planARideViewModel) {
        planARideViewModel.shareIntent.collect { context.startActivity(it) }
    }

    // Load a GPX file opened from another app (ACTION_VIEW / ACTION_SEND)
    LaunchedEffect(pendingGpxUri) {
        val uri = pendingGpxUri ?: return@LaunchedEffect
        gpxSharedViewModel.loadGpxFromUri(uri, context.contentResolver)
        gpxIntentViewModel.consumePendingUri()
    }

    var showRemoveGpxConfirmDialog by remember { mutableStateOf(false) }
    var showGpxAnalysisOverlay by remember { mutableStateOf(false) }
    var gpxToggleActive by remember { mutableStateOf(false) }
    var gpxPoiMode by remember { mutableStateOf(false) }
    var showLayersPanel by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val gpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val success = gpxSharedViewModel.loadGpxFromUri(uri, context.contentResolver)
                if (success) {
                    showLayersPanel = false
                } else {
                    gpxToggleActive = false
                    Toast.makeText(context, "Failed to load GPX file", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            gpxToggleActive = false
        }
    }

    // Reset gpx-related UI state when the track is cleared
    LaunchedEffect(gpxTrack) {
        if (gpxTrack != null) {
            gpxToggleActive = true
        } else {
            gpxSharedViewModel.setGpxPoisOverlayVisible(false)
            gpxPoiMode = false
            showGpxAnalysisOverlay = false
        }
    }

    // Turns off "POIs along .gpx" mode and clears its map state (markers, route segment,
    // and the POI list overlay), e.g. when a category chip is selected instead.
    fun deactivateGpxPoiMode() {
        gpxPoiMode = false
        gpxSharedViewModel.setGpxPoisOverlayVisible(false)
        gpxSharedViewModel.selectPoi(null)
    }

    var mapAndStyle by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    var scaleBarInfo by remember { mutableStateOf<ScaleBarInfo?>(null) }
    val density = LocalDensity.current.density
    var renderedTrackIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var gpxTrackRendered by remember { mutableStateOf(false) }
    var gpxSegmentRendered by remember { mutableStateOf(false) }
    var fastWayHomeRendered by remember { mutableStateOf(false) }
    var planARideRenderedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var fineLocationZoomedIn by remember { mutableStateOf(false) }

    // Device heading (compass direction the phone is facing), shown as an arrow on the
    // user location marker. Null if the rotation vector sensor is unavailable.
    var currentHeading by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val headingSensor = HeadingSensor(context) { heading -> currentHeading = heading }
        headingSensor.start()
        onDispose { headingSensor.stop() }
    }

    // Render user location marker; re-center once a fine fix (accuracy ≤ 50 m) is obtained
    LaunchedEffect(currentLocation, locationAccuracy, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        val loc = currentLocation ?: return@LaunchedEffect
        val accuracy = locationAccuracy ?: 1000f
        try {
            renderUserMarker(context, ms.first, ms.second, loc, accuracy, currentHeading)
        } catch (_: IllegalStateException) {
            return@LaunchedEffect
        }
        if (!fineLocationZoomedIn && accuracy <= 50f) {
            fineLocationZoomedIn = true
            ms.first.animateCamera(
                CameraUpdateFactory.newLatLngZoom(loc, DEFAULT_MAP_ZOOM + 2.0)
            )
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

    // GPX shared track sync
    LaunchedEffect(gpxTrack, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        if (gpxTrackRendered) {
            try { MapTrackRenderer.removeTrack(ms.second, GPX_TRACK_LAYER_ID) } catch (_: Exception) {}
            gpxTrackRendered = false
        }
        val track = gpxTrack ?: return@LaunchedEffect
        if (track.points.size < 2) return@LaunchedEffect
        MapTrackRenderer.addTrack(ms.second, GPX_TRACK_LAYER_ID, track.points, NAV_TRACK_COLOR, NAV_TRACK_WIDTH)
        gpxTrackRendered = true
        val bounds = LatLngBounds.Builder().apply { track.points.forEach { include(it) } }.build()
        ms.first.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, TRACK_FIT_PADDING), 500)
    }

    // Orange segment highlight sync
    LaunchedEffect(gpxSegmentPoints, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        if (gpxSegmentRendered) {
            try { MapTrackRenderer.removeTrack(ms.second, GPX_SEGMENT_HIGHLIGHT_ID) } catch (_: Exception) {}
            gpxSegmentRendered = false
        }
        if (gpxSegmentPoints.size >= 2) {
            MapTrackRenderer.addTrack(ms.second, GPX_SEGMENT_HIGHLIGHT_ID, gpxSegmentPoints, FAST_WAY_HOME_TRACK_COLOR, NAV_TRACK_WIDTH)
            gpxSegmentRendered = true
        }
    }

    // Fast Way Home overlay sync (route line + home marker)
    LaunchedEffect(fastWayHomeResult, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        if (fastWayHomeRendered) {
            try { MapTrackRenderer.removeTrack(ms.second, FAST_WAY_HOME_TRACK_ID) } catch (_: Exception) {}
            removeHomeMarker(ms.second)
            fastWayHomeRendered = false
        }
        val result = fastWayHomeResult ?: return@LaunchedEffect
        val points = result.path.flatMap { PolylineDecoder.decode(it.geometryEncoded) }
        if (points.size < 2) return@LaunchedEffect
        MapTrackRenderer.addTrack(
            ms.second, FAST_WAY_HOME_TRACK_ID, points,
            FAST_WAY_HOME_TRACK_COLOR, FAST_WAY_HOME_TRACK_WIDTH
        )
        fastWayHomeLocation?.let { renderHomeMarker(ms.second, it) }
        fastWayHomeRendered = true
        val bounds = LatLngBounds.Builder().apply {
            points.forEach { include(it) }
            fastWayHomeLocation?.let { include(it) }
        }.build()
        ms.first.easeCamera(
            CameraUpdateFactory.newLatLngBounds(bounds, TRACK_FIT_PADDING), 600
        )
    }

    // Plan-a-ride track sync
    LaunchedEffect(planCandidate, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        for (id in planARideRenderedIds) {
            try { MapTrackRenderer.removeTrack(ms.second, id) } catch (_: Exception) {}
        }
        planARideRenderedIds = emptySet()

        val candidate = planCandidate ?: return@LaunchedEffect
        val newIds = mutableSetOf<String>()

        // Full route in green
        val routePoints = candidate.refinedRoute.edges.flatMap { PolylineDecoder.decode(it.geometryEncoded) }
        if (routePoints.size >= 2) {
            val routeId = "${PLAN_A_RIDE_TRACK_ID_PREFIX}route"
            MapTrackRenderer.addTrack(ms.second, routeId, routePoints, PLAN_A_RIDE_TRACK_COLORS[0], PLAN_A_RIDE_TRACK_WIDTH)
            newIds.add(routeId)
            val bounds = LatLngBounds.Builder().apply { routePoints.forEach { include(it) } }.build()
            ms.first.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, TRACK_FIT_PADDING), 600)
        }

        // Flow corridor segments overlaid in amber (each segment drawn independently)
        val corridorSegments = candidate.corridorEdges.map { PolylineDecoder.decode(it.geometryEncoded) }
        if (corridorSegments.any { it.size >= 2 }) {
            val corridorId = "${PLAN_A_RIDE_TRACK_ID_PREFIX}corridors"
            MapTrackRenderer.addMultiLineTrack(ms.second, corridorId, corridorSegments, PLAN_A_RIDE_CORRIDOR_COLOR, PLAN_A_RIDE_TRACK_WIDTH)
            newIds.add(corridorId)
        }

        // Un-ridden discovery edges overlaid in purple (on top of route and corridor layers)
        val discoverySegments = candidate.refinedRoute.edges
            .filter { !it.isTraversed }
            .map { PolylineDecoder.decode(it.geometryEncoded) }
        if (discoverySegments.any { it.size >= 2 }) {
            val discoveryId = "${PLAN_A_RIDE_TRACK_ID_PREFIX}discovery"
            MapTrackRenderer.addMultiLineTrack(ms.second, discoveryId, discoverySegments, PLAN_A_RIDE_DISCOVERY_COLOR, PLAN_A_RIDE_TRACK_WIDTH)
            newIds.add(discoveryId)
        }

        planARideRenderedIds = newIds
    }

    // Finish-line marker for selected GPX POI — rendered at the highest layer
    LaunchedEffect(selectedPoiItem, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        try { ms.second.removeLayer("poi-finish-layer") } catch (_: Exception) {}
        try { ms.second.removeSource("poi-finish-source") } catch (_: Exception) {}
        val item = selectedPoiItem ?: return@LaunchedEffect
        val point = Point.fromLngLat(item.poiWD.poi.lon, item.poiWD.poi.lat)
        val feature = Feature.fromGeometry(point)
        val source = GeoJsonSource("poi-finish-source", feature)
        ms.second.addSource(source)
        val layer = SymbolLayer("poi-finish-layer", "poi-finish-source").withProperties(
            PropertyFactory.textField("🏁"),
            PropertyFactory.textSize(32f),
            PropertyFactory.textAnchor("bottom"),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true)
        )
        ms.second.addLayer(layer)
    }

    // Camera fit when a POI is selected — includes the user's location, the POI, and the
    // .gpx segment between them so the whole route to the POI is visible.
    LaunchedEffect(selectedPoiItem, gpxSegmentPoints, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        val item = selectedPoiItem ?: return@LaunchedEffect
        val refLoc = currentLocation ?: gpxTrack?.points?.firstOrNull() ?: return@LaunchedEffect
        val poiLoc = LatLng(item.poiWD.poi.lat, item.poiWD.poi.lon)
        val bounds = LatLngBounds.Builder().apply {
            include(refLoc)
            include(poiLoc)
            gpxSegmentPoints.forEach { include(it) }
        }.build()
        ms.first.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, TRACK_FIT_PADDING), 800)
    }

    // POI layer sync — gpx-POI mode (only POIs along the loaded .gpx track) takes
    // precedence over the regular category-chip-filtered POI layer.
    LaunchedEffect(showPoiLayer, visiblePois, gpxPoiMode, gpxPois, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        MapPoiRenderer.removePois(ms.second)
        when {
            gpxPoiMode -> if (gpxPois.isNotEmpty()) {
                MapPoiRenderer.addPois(context, ms.second, gpxPois.map { it.poi })
            }
            showPoiLayer && visiblePois.isNotEmpty() -> MapPoiRenderer.addPois(context, ms.second, visiblePois)
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
                        onClick = {
                            deactivateGpxPoiMode()
                            viewModel.selectPoiChip(MapViewViewModel.ALL_POIS_CHIP)
                        },
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
                        onClick = {
                            deactivateGpxPoiMode()
                            viewModel.selectPoiChip(category)
                        },
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

            // GPX POI chip (left) and Layers FAB (right) — top-aligned in a shared row so
            // the FAB stays vertically aligned with the chip when it's shown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (gpxTrack != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
                        ) {
                            FilterChip(
                                selected = gpxPoiMode,
                                onClick = {
                                    if (gpxPoiMode) {
                                        deactivateGpxPoiMode()
                                    } else {
                                        gpxPoiMode = true
                                        viewModel.clearPoiChip()
                                    }
                                },
                                label = { Text("POIs along .gpx") },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                )
                            )
                            if (gpxPoiMode) {
                                FilterChip(
                                    selected = false,
                                    onClick = { gpxSharedViewModel.setGpxPoisOverlayVisible(true) },
                                    enabled = !isLoadingPois,
                                    label = { Text("List") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    )
                                )
                            }
                            FilterChip(
                                selected = false,
                                onClick = { showGpxAnalysisOverlay = true },
                                label = { Text("Analysis") },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                )
                            )
                        }
                    }
                }
                SmallFloatingActionButton(
                    onClick = { showLayersPanel = true },
                    modifier = Modifier.padding(end = 16.dp)
                ) {
                    Icon(Icons.Default.Layers, contentDescription = "Toggle layers")
                }
            }

            // Fast Way Home result card — sits below the chip rows
            if (showFastWayHomeCard) {
                FastWayHomeCard(
                    result = fastWayHomeResult,
                    message = fastWayHomeMessage,
                    isLoading = isFindingFastWayHome,
                    onDismiss = { fastWayHomeViewModel.clearFastWayHome() },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Plan a ride result card
            if (showPlanARideCard) {
                PlanARideCard(
                    candidate = planCandidate,
                    message = planMessage,
                    isLoading = isGeneratingPlan,
                    onExportGpx = { planARideViewModel.exportGpx() },
                    onDismiss = { planARideViewModel.clearPlan() },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // POI popup card — sits below the chip rows (and the GPX POI button, if shown)
            if (!showFastWayHomeCard && !showPlanARideCard) {
                selectedPoi?.let { poiWD ->
                    PoiPopupCard(
                        poiWithDistances = poiWD,
                        onOpenInMaps = { openPoiInGoogleMaps(context, poiWD) },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }

        // "POIs along .gpx" loading indicator — centered on the map while POIs are fetched
        if (gpxPoiMode && isLoadingPois) {
            Card(modifier = Modifier.align(Alignment.Center)) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text("Loading POIs")
                }
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

        // Stacked FABs - bottom right: fast-way-home above locate-me
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SmallFloatingActionButton(
                onClick = { showPlanDistanceDialog = true }
            ) {
                Icon(Icons.AutoMirrored.Filled.DirectionsBike, contentDescription = "Plan a ride")
            }
            SmallFloatingActionButton(
                onClick = {
                    fastWayHomeViewModel.findFastWayHome(viewModel.currentLocation, viewModel.locationAccuracy)
                }
            ) {
                Icon(Icons.Default.Home, contentDescription = "Find fast way home")
            }
            FloatingActionButton(
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

                        // .gpx toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(".gpx", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = gpxToggleActive,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        gpxToggleActive = true
                                        gpxLauncher.launch(arrayOf("application/gpx+xml", "application/xml", "*/*"))
                                    } else {
                                        showRemoveGpxConfirmDialog = true
                                    }
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }

        // "POIs along .gpx" list overlay — scrim + centered card, leaving the map visible
        // around the edges (24dp padding on all sides).
        if (showGpxPoisOverlay) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { gpxSharedViewModel.setGpxPoisOverlayVisible(false) }
            ) {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                        .fillMaxSize()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {}
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = GpxAnalysisUtils.truncateFileName(gpxFileName ?: "GPX track"),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1
                            )
                            IconButton(onClick = { gpxSharedViewModel.setGpxPoisOverlayVisible(false) }) {
                                Icon(Icons.Default.Close, contentDescription = "Close POI list")
                            }
                        }
                        GpxPoisSheetContent(
                            poiItems = gpxPoiItems,
                            isLoading = isLoadingPois,
                            locationAvailable = locationAvailable,
                            onPoiSelected = { item ->
                                gpxSharedViewModel.selectPoi(item)
                                gpxSharedViewModel.setGpxPoisOverlayVisible(false)
                            },
                            context = context,
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }

    if (showGpxAnalysisOverlay) {
        GpxAnalysisOverlay(
            fileName = gpxFileName,
            gpxTrack = gpxTrack,
            gpxPois = gpxPois,
            discoveryScore = gpxDiscoveryScore,
            speedPowerEstimate = gpxSpeedPowerEstimate,
            onClose = { showGpxAnalysisOverlay = false }
        )
    }

    if (showPlanDistanceDialog) {
        var distanceText by remember { mutableStateOf(PLAN_A_RIDE_DEFAULT_DISTANCE_KM.toInt().toString()) }
        var selectedDirection by remember { mutableStateOf<RideDirection?>(null) }
        var exploreWeight by remember { mutableStateOf(PLAN_A_RIDE_DEFAULT_EXPLORE_WEIGHT.toFloat()) }
        AlertDialog(
            onDismissRequest = { showPlanDistanceDialog = false },
            title = { Text("Plan a ride") },
            text = {
                Column {
                    Text("Target distance (km):")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = distanceText,
                        onValueChange = { distanceText = it.filter { c -> c.isDigit() } },
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Direction:")
                    Spacer(modifier = Modifier.height(8.dp))
                    val directions = RideDirection.entries
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        directions.take(2).forEach { dir ->
                            FilterChip(
                                selected = selectedDirection == dir,
                                onClick = {
                                    selectedDirection = if (selectedDirection == dir) null else dir
                                },
                                label = { Text(dir.label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        directions.drop(2).forEach { dir ->
                            FilterChip(
                                selected = selectedDirection == dir,
                                onClick = {
                                    selectedDirection = if (selectedDirection == dir) null else dir
                                },
                                label = { Text(dir.label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Familiar", style = MaterialTheme.typography.bodySmall)
                        Text("Explore", style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = exploreWeight,
                        onValueChange = { exploreWeight = it },
                        valueRange = 0f..PLAN_A_RIDE_MAX_EXPLORE_WEIGHT.toFloat(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPlanDistanceDialog = false
                    val km = distanceText.toDoubleOrNull() ?: PLAN_A_RIDE_DEFAULT_DISTANCE_KM
                    planARideViewModel.planARide(km, selectedDirection, exploreWeight.toDouble())
                }) { Text("Generate") }
            },
            dismissButton = {
                TextButton(onClick = { showPlanDistanceDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showRemoveGpxConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                showRemoveGpxConfirmDialog = false
            },
            title = { Text("Remove .gpx track?") },
            text = { Text("This will remove the loaded .gpx track and its POIs from the map.") },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveGpxConfirmDialog = false
                    gpxToggleActive = false
                    gpxSharedViewModel.clearGpx()
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRemoveGpxConfirmDialog = false
                }) { Text("Cancel") }
            }
        )
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

@Preview
@Composable
private fun GpxAnalysisOverlayPreview() {
    GpxAnalysisOverlay(
        fileName = null,
        gpxTrack = null,
        gpxPois = emptyList(),
        discoveryScore = null,
        speedPowerEstimate = null,
        onClose = {}
    )
}

/**
 * Full-screen overlay hosting the .gpx track analysis sections (discovery score, elevation
 * profile, etc., added by follow-up issues).
 */
@Composable
private fun GpxAnalysisOverlay(
    fileName: String?,
    gpxTrack: GpxTrack?,
    gpxPois: List<PoiWithDistances>,
    discoveryScore: DiscoveryScoreResult?,
    speedPowerEstimate: SpeedPowerEstimateResult?,
    onClose: () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = GpxAnalysisUtils.truncateFileName(fileName ?: "GPX track"),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close .gpx analysis")
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (gpxTrack != null) {
                        DiscoveryScoreSection(discoveryScore = discoveryScore)
                        SpeedPowerEstimateSection(speedPowerEstimate = speedPowerEstimate)
                        ElevationProfileSection(gpxTrack = gpxTrack)
                        PoiDensitySection(gpxTrack = gpxTrack, gpxPois = gpxPois)
                    }
                }
            }
        }
    }
}

/** "Based on 18 km of 25 km route (72%) — 7 km outside graph coverage" */
private fun routeCoverageNote(coverage: RouteCoverage): String {
    val matchedKm = (coverage.matchedDistanceM / 1000.0).roundToInt()
    val totalKm = (coverage.totalDistanceM / 1000.0).roundToInt()
    val outsideKm = totalKm - matchedKm
    return "Based on $matchedKm km of $totalKm km route (${coverage.percent}%) — $outsideKm km outside graph coverage"
}

@Composable
private fun DiscoveryScoreSection(discoveryScore: DiscoveryScoreResult?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Discovery score", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            when (discoveryScore) {
                null -> Text(
                    text = "Analyzing route...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is DiscoveryScoreResult.Unavailable -> Text(
                    text = "Couldn't analyze this route",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is DiscoveryScoreResult.OutsideCoverage -> Text(
                    text = "Route is entirely outside the graph's coverage area",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is DiscoveryScoreResult.Score -> {
                    Text(
                        text = "Discovery Score: ${discoveryScore.value}/100",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val description = "The percentage of this route's roads you haven't ridden before."
                    val text = if (discoveryScore.routeCoverage.isFull) {
                        description
                    } else {
                        "$description ${routeCoverageNote(discoveryScore.routeCoverage)}"
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedPowerEstimateSection(speedPowerEstimate: SpeedPowerEstimateResult?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Speed & power estimate", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            when (speedPowerEstimate) {
                null -> Text(
                    text = "Analyzing route...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is SpeedPowerEstimateResult.Unavailable -> Text(
                    text = "Couldn't analyze this route",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is SpeedPowerEstimateResult.OutsideCoverage -> Text(
                    text = "Route is entirely outside the graph's coverage area",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is SpeedPowerEstimateResult.NoRideHistory -> Text(
                    text = "No ride history on this route",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is SpeedPowerEstimateResult.Estimate -> {
                    val coverageText = if (speedPowerEstimate.totalCoveragePercent > speedPowerEstimate.coveragePercent) {
                        "${speedPowerEstimate.totalCoveragePercent}% data coverage (${speedPowerEstimate.coveragePercent}% direct, rest from nearby parallel roads)"
                    } else {
                        "${speedPowerEstimate.coveragePercent}% data coverage"
                    }
                    Text(
                        text = "Avg ${speedPowerEstimate.avgSpeedKmh} km/h, ${speedPowerEstimate.avgPowerW}W - based on $coverageText",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun ElevationProfileSection(gpxTrack: GpxTrack) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Elevation profile", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            val elevations = gpxTrack.elevations
            val profile = remember(gpxTrack) {
                elevations?.let { GpxAnalysisUtils.elevationProfile(gpxTrack.points, it) }
            }

            if (profile == null || profile.size < 2) {
                Text(
                    text = "No elevation data in this file",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val smoothed = remember(profile) {
                    GpxAnalysisUtils.smoothElevations(profile.map { it.second })
                }
                val (gainM, lossM) = remember(smoothed) { GpxAnalysisUtils.elevationGainLoss(smoothed) }
                val gainPer100km = remember(gainM, profile) {
                    GpxAnalysisUtils.elevationGainPer100km(gainM, profile.last().first)
                }
                val minEle = smoothed.min()
                val maxEle = smoothed.max()
                val axisStep = GpxAnalysisUtils.elevationAxisStep(minEle, maxEle)
                val axisMin = floor(minEle / axisStep) * axisStep
                val axisMax = (ceil(maxEle / axisStep) * axisStep).let { if (it <= axisMin) axisMin + axisStep else it }
                val axisRange = axisMax - axisMin
                val maxDistance = profile.last().first.coerceAtLeast(1.0)
                val lineColor = MaterialTheme.colorScheme.primary
                val referenceLineColor = MaterialTheme.colorScheme.onSurfaceVariant
                val axisTickCount = (axisRange / axisStep).roundToInt() + 1
                val axisLabels = remember(axisMin, axisMax, axisStep) {
                    (0 until axisTickCount).map { i -> (axisMax - axisStep * i).roundToInt() }
                }
                val numberFormat = remember { NumberFormat.getIntegerInstance() }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(end = 4.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        axisLabels.forEach { label ->
                            Text(
                                text = numberFormat.format(label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        axisLabels.forEach { label ->
                            val y = size.height - ((label - axisMin) / axisRange).toFloat() * size.height
                            drawLine(
                                color = referenceLineColor.copy(alpha = 0.3f),
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                        val path = Path()
                        smoothed.forEachIndexed { index, elevation ->
                            val x = (profile[index].first / maxDistance).toFloat() * size.width
                            val y = size.height - ((elevation - axisMin) / axisRange).toFloat() * size.height
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path = path, color = lineColor, style = Stroke(width = 2.dp.toPx()))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Up: ${numberFormat.format(gainM)}m, Down: ${numberFormat.format(lossM)}m, " +
                        "Per 100km: ${numberFormat.format(gainPer100km)}m",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** 0 POIs = red, 1-5 = orange, >5 = green, regardless of bucket size. */
private fun poiBucketColor(count: Int): Color = when {
    count == 0 -> Color(0xFFE53935)
    count <= 5 -> Color(0xFFFF9800)
    else -> Color(0xFF4CAF50)
}

@Composable
private fun PoiDensitySection(gpxTrack: GpxTrack, gpxPois: List<PoiWithDistances>) {
    val totalDistanceM = remember(gpxTrack) { GpxAnalysisUtils.totalTrackDistanceM(gpxTrack.points) }
    val bucketSizeM = remember(totalDistanceM) { GpxAnalysisUtils.poiDensityBucketSizeM(totalDistanceM) }
    val bucketSizeKm = (bucketSizeM / 1000.0).roundToInt()
    val counts = remember(gpxPois, totalDistanceM, bucketSizeM) {
        GpxAnalysisUtils.poiCountsPerBucket(gpxPois, totalDistanceM, bucketSizeM)
    }
    val maxCount = (counts.maxOrNull() ?: 0).coerceAtLeast(1)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "POI density (${bucketSizeKm}km bins)", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                counts.forEachIndexed { index, count ->
                    val fraction = count.toFloat() / maxCount.toFloat()

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((fraction * 80).dp.coerceAtLeast(2.dp))
                        ) {
                            drawRect(color = poiBucketColor(count))
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${(index + 1) * bucketSizeKm}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Distance along track (km)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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

@Composable
private fun GpxPoisSheetContent(
    poiItems: List<GpxPoiItem>,
    isLoading: Boolean,
    locationAvailable: Boolean,
    onPoiSelected: (GpxPoiItem) -> Unit,
    context: android.content.Context,
    modifier: Modifier = Modifier
) {
    var includePassed by remember { mutableStateOf(false) }
    var lookBackKm by remember { mutableStateOf<Int?>(5) }

    val aheadItems = remember(poiItems) { poiItems.filter { it.isAhead }.sortedBy { it.distanceM } }
    val behindItems = remember(poiItems, lookBackKm) {
        poiItems.filter { !it.isAhead }
            .filter { lookBackKm == null || it.distanceM <= lookBackKm!! * 1000 }
            .sortedBy { it.distanceM }
    }

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Include POIs you have passed",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = includePassed,
                onCheckedChange = { includePassed = it }
            )
        }
        if (includePassed) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Look-back distance:",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val lookBackOptions: List<Pair<String, Int?>> = listOf(
                    "5 km" to 5,
                    "10 km" to 10,
                    "25 km" to 25,
                    "All" to null
                )
                lookBackOptions.forEach { (label, value) ->
                    FilterChip(
                        selected = lookBackKm == value,
                        onClick = { lookBackKm = value },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        )
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        when {
            isLoading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            else -> Column {
                if (!locationAvailable) {
                    Text(
                        text = "Your location could not be determined. Distances are measured from the GPX starting point.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                if (aheadItems.isEmpty()) {
                    Text(
                        text = "No POIs found along this track",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 32.dp)
                    )
                } else {
                    aheadItems.forEach { item ->
                        GpxPoiRow(item = item, isBehind = false, context = context, onClick = { onPoiSelected(item) })
                    }
                }
                if (includePassed) {
                    behindItems.forEach { item ->
                        GpxPoiRow(item = item, isBehind = true, context = context, onClick = { onPoiSelected(item) })
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun GpxPoiRow(
    item: GpxPoiItem,
    isBehind: Boolean,
    context: android.content.Context,
    onClick: () -> Unit = {}
) {
    val poiWD = item.poiWD
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = poiWD.poi.name.ifEmpty { "Unnamed" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f, fill = false)
                )
                OpenClosedBadge(poiWD.poi.openingHours)
            }
            Text(
                text = "${FormatUtils.formatGpxPoiDistance(item.distanceM)} | ${FormatUtils.categoryDisplayName(poiWD.poi.category)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = poiWD.poi.openingHours ?: "No opening hours available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isBehind) {
                Text(
                    text = "Behind you",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = { openPoiInGoogleMaps(context, poiWD) }) {
            Icon(
                imageVector = Icons.Default.OpenInNew,
                contentDescription = "Open in Google Maps",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun OpenClosedBadge(openingHours: String?) {
    if (openingHours == null) return
    val isOpen = OpeningHoursUtils.isOpenNow(openingHours) ?: return
    val badgeColor = if (isOpen) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
    val label = if (isOpen) "Open" else "Closed"
    Surface(
        color = badgeColor.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = label,
            color = badgeColor,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
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

    // Bin the GPS accuracy to reduce visual jitter
    val binRadiusM = when {
        accuracyM <= 10f  -> 10.0
        accuracyM <= 20f  -> 20.0
        accuracyM <= 50f  -> 50.0
        accuracyM <= 100f -> 100.0
        else              -> 200.0
    }

    // Web Mercator tiles double in resolution with each zoom level, so the screen-pixel
    // radius for a constant ground radius is `radiusAtZoom0 * 2^zoom`. Express that as an
    // exponential (base 2) zoom interpolation so the circle keeps representing the same
    // real-world accuracy radius — and visibly grows/shrinks — as the map is zoomed,
    // mirroring the Google Maps "my location" accuracy circle.
    val latRad = Math.toRadians(location.latitude)
    val radiusAtZoom0 = (binRadiusM * 256.0 /
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

