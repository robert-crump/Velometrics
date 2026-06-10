package com.velometrics.app.ui.screens.mapview

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Checkbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.domain.model.GpxPoiItem
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.ui.components.ComposableMapView
import com.velometrics.app.ui.components.MapIntervalRenderer
import com.velometrics.app.ui.components.MapOverlayRenderer
import com.velometrics.app.ui.components.MapPoiRenderer
import com.velometrics.app.ui.components.MapTrackRenderer
import com.velometrics.app.ui.components.PoiPopupCard
import com.velometrics.app.ui.components.openPoiInGoogleMaps
import com.velometrics.app.ui.intent.GpxIntentViewModel
import com.velometrics.app.ui.shared.GpxSharedViewModel
import com.velometrics.app.util.FormatUtils
import com.velometrics.app.util.OpeningHoursUtils
import com.velometrics.app.util.CyclingConstants.DEFAULT_MAP_ZOOM
import com.velometrics.app.util.CyclingConstants.INTERVAL_DURATION_COLOR_RAMP
import com.velometrics.app.util.CyclingConstants.FAST_WAY_HOME_TRACK_COLOR
import com.velometrics.app.util.CyclingConstants.FAST_WAY_HOME_TRACK_WIDTH
import com.velometrics.app.util.CyclingConstants.NAV_TRACK_COLOR
import com.velometrics.app.util.CyclingConstants.NAV_TRACK_WIDTH
import com.velometrics.app.util.CyclingConstants.SPEED_COLOR_MAP
import com.velometrics.app.util.CyclingConstants.STOP_COLOR_LONG
import com.velometrics.app.util.CyclingConstants.STOP_COLOR_MEDIUM
import com.velometrics.app.util.CyclingConstants.STOP_COLOR_SHORT
import com.velometrics.app.util.CyclingConstants.TRACK_COLORS
import com.velometrics.app.util.CyclingConstants.TRACK_FIT_PADDING
import com.velometrics.app.util.GpsTrackParser
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.util.MapOverlayUtils
import com.velometrics.app.util.PolylineDecoder
import kotlin.math.cos
import kotlin.math.pow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
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
    gpxSharedViewModel: GpxSharedViewModel = hiltViewModel(LocalContext.current as ComponentActivity),
    gpxIntentViewModel: GpxIntentViewModel = hiltViewModel(LocalContext.current as ComponentActivity)
) {
    val edges by viewModel.allEdges.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val visibleSessionIds by viewModel.visibleSessionIds.collectAsState()
    val showAllRidesLayer by viewModel.showAllRidesLayer.collectAsState()
    val showSpeedOverlay by viewModel.showSpeedOverlay.collectAsState()
    val selectedSpeedCategories by viewModel.selectedSpeedCategories.collectAsState()
    val showStopSpots by viewModel.showStopSpots.collectAsState()

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
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startLocationUpdates()
    }

    val gpxTrack by gpxSharedViewModel.gpxTrack.collectAsState()
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

    // Load a GPX file opened from another app (ACTION_VIEW / ACTION_SEND)
    LaunchedEffect(pendingGpxUri) {
        val uri = pendingGpxUri ?: return@LaunchedEffect
        gpxSharedViewModel.loadGpxFromUri(uri, context.contentResolver)
        gpxIntentViewModel.consumePendingUri()
    }

    var showLoadGpxConfirmDialog by remember { mutableStateOf(false) }
    var showGpxPoisSheet by remember { mutableStateOf(false) }
    val gpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) gpxSharedViewModel.loadGpxFromUri(uri, context.contentResolver)
    }

    var mapAndStyle by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var renderedTrackIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var gpxTrackRendered by remember { mutableStateOf(false) }
    var gpxSegmentRendered by remember { mutableStateOf(false) }
    var fastWayHomeRendered by remember { mutableStateOf(false) }
    var fineLocationZoomedIn by remember { mutableStateOf(false) }

    val gpxPoisListState = rememberLazyListState()

    val peekHeightDp = (LocalConfiguration.current.screenHeightDp * 0.10f).dp
    val gpxBottomSheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.Hidden,
        skipHiddenState = false
    )
    val gpxScaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = gpxBottomSheetState)

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

    // Camera fit when a POI is selected
    LaunchedEffect(selectedPoiItem, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        val item = selectedPoiItem ?: return@LaunchedEffect
        val refLoc = currentLocation ?: gpxTrack?.points?.firstOrNull() ?: return@LaunchedEffect
        val poiLoc = LatLng(item.poiWD.poi.lat, item.poiWD.poi.lon)
        val bounds = LatLngBounds.Builder().include(refLoc).include(poiLoc).build()
        ms.first.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, TRACK_FIT_PADDING), 800)
    }

    // GPX POIs sheet scaffold state control
    LaunchedEffect(showGpxPoisSheet, selectedPoiItem) {
        when {
            !showGpxPoisSheet -> gpxBottomSheetState.hide()
            selectedPoiItem != null -> gpxBottomSheetState.partialExpand()
            else -> gpxBottomSheetState.expand()
        }
    }

    // Detect sheet state changes driven by user gestures
    val gpxSheetValue by remember { derivedStateOf { gpxBottomSheetState.currentValue } }
    LaunchedEffect(gpxSheetValue) {
        when (gpxSheetValue) {
            SheetValue.Hidden -> if (showGpxPoisSheet) {
                showGpxPoisSheet = false
                gpxSharedViewModel.selectPoi(null)
            }
            SheetValue.Expanded -> gpxSharedViewModel.selectPoi(null)
            else -> {}
        }
    }

    // POI layer sync
    LaunchedEffect(showPoiLayer, visiblePois, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        MapPoiRenderer.removePois(ms.second)
        if (showPoiLayer && visiblePois.isNotEmpty()) {
            MapPoiRenderer.addPois(ms.second, visiblePois)
        }
    }

    // POI highlight sync
    LaunchedEffect(selectedPoi, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        MapPoiRenderer.highlightPoi(ms.second, selectedPoi?.poi)
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

    BottomSheetScaffold(
        scaffoldState = gpxScaffoldState,
        sheetPeekHeight = peekHeightDp,
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetContent = {
            if (showGpxPoisSheet) {
                GpxPoisSheetContent(
                    poiItems = gpxPoiItems,
                    isLoading = isLoadingPois,
                    locationAvailable = locationAvailable,
                    lazyListState = gpxPoisListState,
                    onPoiSelected = { item ->
                        gpxSharedViewModel.selectPoi(item)
                    },
                    context = context
                )
            }
        },
        containerColor = Color.Transparent
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Full-screen map
        ComposableMapView(
            modifier = Modifier.fillMaxSize(),
            gesturesEnabled = true,
            onMapReady = { map, style ->
                mapAndStyle = Pair(map, style)
                viewModel.updateViewportBounds(map.projection.visibleRegion.latLngBounds)
                map.addOnCameraIdleListener {
                    viewModel.updateViewportBounds(map.projection.visibleRegion.latLngBounds)
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
                        label = { Text(category) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        )
                    )
                }
            }

            // GPX POI chip — only visible when a track is loaded
            if (gpxTrack != null) {
                Row(modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)) {
                    FilterChip(
                        selected = false,
                        onClick = { showGpxPoisSheet = true },
                        label = { Text("POIs along .gpx") },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        )
                    )
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
        }

        // POI popup card (hidden while Fast Way Home card is showing)
        if (!showFastWayHomeCard) {
            selectedPoi?.let { poiWD ->
                PoiPopupCard(
                    poiWithDistances = poiWD,
                    onOpenInMaps = { openPoiInGoogleMaps(context, poiWD) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 80.dp)
                )
            }
        }

        // Stacked FABs - bottom right: locate-me, fast-way-home, layers
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
                Icon(Icons.Default.MyLocation, contentDescription = "Locate me")
            }
            SmallFloatingActionButton(
                onClick = {
                    fastWayHomeViewModel.findFastWayHome(viewModel.currentLocation, viewModel.locationAccuracy)
                }
            ) {
                Icon(Icons.Default.Home, contentDescription = "Find fast way home")
            }
            FloatingActionButton(onClick = { showBottomSheet = true }) {
                Icon(Icons.Default.Layers, contentDescription = "Toggle layers")
            }
        }
    }
    } // BottomSheetScaffold content

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

                // .gpx track toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(".gpx track", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = gpxTrack != null,
                        onCheckedChange = { checked ->
                            if (checked) {
                                showLoadGpxConfirmDialog = true
                            } else {
                                gpxSharedViewModel.clearGpx()
                            }
                        }
                    )
                }

                if (showAllRidesLayer || showSpeedOverlay || showStopSpots || showIntervalOverlay) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LegendCard(
                        showAllRides = showAllRidesLayer,
                        showSpeed = showSpeedOverlay,
                        showStops = showStopSpots,
                        showIntervals = showIntervalOverlay,
                        selectedSpeedCategories = selectedSpeedCategories,
                        onSpeedCategoryClick = { viewModel.toggleSpeedCategory(it) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showLoadGpxConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLoadGpxConfirmDialog = false },
            title = { Text("Load .gpx file?") },
            text = { Text("Browse for a .gpx file to load onto the map.") },
            confirmButton = {
                TextButton(onClick = {
                    showLoadGpxConfirmDialog = false
                    gpxLauncher.launch(arrayOf("application/gpx+xml", "application/xml", "*/*"))
                }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { showLoadGpxConfirmDialog = false }) { Text("Cancel") }
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
    lazyListState: LazyListState,
    onPoiSelected: (GpxPoiItem) -> Unit,
    context: android.content.Context
) {
    var includePassed by remember { mutableStateOf(false) }
    var lookBackKm by remember { mutableStateOf<Int?>(5) }

    val aheadItems = remember(poiItems) { poiItems.filter { it.isAhead }.sortedBy { it.distanceM } }
    val behindItems = remember(poiItems, lookBackKm) {
        poiItems.filter { !it.isAhead }
            .filter { lookBackKm == null || it.distanceM <= lookBackKm!! * 1000 }
            .sortedBy { it.distanceM }
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = "POIs along .gpx",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
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
                    "Whole track" to null
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
            else -> LazyColumn(state = lazyListState) {
                if (!locationAvailable) {
                    item {
                        Text(
                            text = "Your location could not be determined. Distances are measured from the GPX starting point.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                if (aheadItems.isEmpty() && !isLoading) {
                    item {
                        Text(
                            text = "No POIs found along this track",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 32.dp)
                        )
                    }
                } else {
                    items(aheadItems, key = { it.poiWD.poi.poiId }) { item ->
                        GpxPoiRow(item = item, isBehind = false, context = context, onClick = { onPoiSelected(item) })
                    }
                }
                if (includePassed && behindItems.isNotEmpty()) {
                    items(behindItems, key = { "behind_${it.poiWD.poi.poiId}" }) { item ->
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
                text = FormatUtils.categoryDisplayName(poiWD.poi.category),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = FormatUtils.formatGpxPoiDistance(item.distanceM),
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

            if ((showSpeed || showStops) && showIntervals) {
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
}

