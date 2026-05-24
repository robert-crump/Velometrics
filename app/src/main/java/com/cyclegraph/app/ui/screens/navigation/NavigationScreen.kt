package com.cyclegraph.app.ui.screens.navigation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.graphics.toColorInt
import androidx.hilt.navigation.compose.hiltViewModel
import com.cyclegraph.app.domain.model.PoiWithDistances
import com.cyclegraph.app.domain.service.TrackGeometryUtils
import com.cyclegraph.app.ui.components.ComposableMapView
import com.cyclegraph.app.ui.components.MapPoiRenderer
import com.cyclegraph.app.ui.components.MapTrackRenderer
import com.cyclegraph.app.util.CyclingConstants
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// Discrete option lists (metres for radius/corridor, km for look-ahead)
private val NEARBY_RADIUS_OPTIONS   = listOf(200.0, 500.0, 1000.0, 2000.0, 5000.0)
private val LOOK_AHEAD_OPTIONS      = listOf(1.0, 2.0, 5.0, 10.0, 20.0)   // km
private val SEARCH_CORRIDOR_OPTIONS = listOf(50.0, 200.0, 500.0, 1000.0, 5000.0) // metres

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(
    viewModel: NavigationViewModel = hiltViewModel(),
    onNavigateToHome: () -> Unit = {}
) {
    val gpxTrack        by viewModel.gpxTrack.collectAsState()
    val userPosition    by viewModel.userPosition.collectAsState()
    val pois            by viewModel.pois.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val isLoadingGpx    by viewModel.isLoadingGpx.collectAsState()
    val isLoadingPois   by viewModel.isLoadingPois.collectAsState()
    val errorMessage    by viewModel.errorMessage.collectAsState()
    val poiSelection    by viewModel.poiSelection.collectAsState()
    val selectedPoi    = poiSelection.selected?.poi
    val popupPoiWD     = poiSelection.selected?.popup
    val pendingZoomTo  = poiSelection.pendingZoomTo
    val lookAheadKm     by viewModel.lookAheadKm.collectAsState()
    val selectedMode    by viewModel.selectedMode.collectAsState()
    val selectedTab     by viewModel.selectedTab.collectAsState()
    val nearbyRadiusM   by viewModel.nearbyRadiusM.collectAsState()
    val searchCorridorM     by viewModel.searchCorridorM.collectAsState()
    val availableCategories by viewModel.availableCategories.collectAsState()

    var mapAndStyle by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    var trackRendered by remember { mutableStateOf(false) }

    // One-shot camera fit: set to true when settings change; consumed once map tab is open
    var pendingCameraFit by remember { mutableStateOf(false) }

    // Slider draft indices (0..4)
    var radiusSliderIndex by remember {
        mutableStateOf(NEARBY_RADIUS_OPTIONS.indexOf(nearbyRadiusM).let { if (it < 0) 1 else it }.toFloat())
    }
    var lookAheadSliderIndex by remember {
        mutableStateOf(LOOK_AHEAD_OPTIONS.indexOf(lookAheadKm).let { if (it < 0) 1 else it }.toFloat())
    }
    var corridorSliderIndex by remember {
        mutableStateOf(SEARCH_CORRIDOR_OPTIONS.indexOf(searchCorridorM).let { if (it < 0) 1 else it }.toFloat())
    }

    val listState = rememberLazyListState()
    val context = LocalContext.current

    // Android system back from any mode → return to mode-selection screen
    BackHandler(enabled = selectedMode != null) { viewModel.resetMode() }

    val gpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.loadGpxFromUri(uri, context.contentResolver) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setUserPositionFromPermission(granted)
        if (granted) {
            when (viewModel.selectedMode.value) {
                PoiMode.NEARBY      -> viewModel.fetchPoisNearbyByRadius()
                PoiMode.ALONG_TRACK -> if (viewModel.gpxTrack.value != null) viewModel.fetchPoisAlongTrack()
                null                -> {}
            }
        }
    }

    // Sync slider drafts and request permission when mode is selected
    LaunchedEffect(selectedMode) {
        when (selectedMode) {
            null -> {
                mapAndStyle = null
                trackRendered = false
            }
            PoiMode.NEARBY -> {
                radiusSliderIndex = NEARBY_RADIUS_OPTIONS.indexOf(viewModel.nearbyRadiusM.value)
                    .let { if (it < 0) 1 else it }.toFloat()
                pendingCameraFit = true
                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            PoiMode.ALONG_TRACK -> {
                lookAheadSliderIndex = LOOK_AHEAD_OPTIONS.indexOf(viewModel.lookAheadKm.value)
                    .let { if (it < 0) 1 else it }.toFloat()
                corridorSliderIndex = SEARCH_CORRIDOR_OPTIONS.indexOf(viewModel.searchCorridorM.value)
                    .let { if (it < 0) 1 else it }.toFloat()
                pendingCameraFit = true
            }
        }
    }

    // When GPX track loads in ALONG_TRACK → request permission + schedule camera fit
    LaunchedEffect(gpxTrack, selectedMode) {
        if (selectedMode == PoiMode.ALONG_TRACK && gpxTrack != null) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            pendingCameraFit = true
        }
    }

    // Schedule camera fit when search settings change
    LaunchedEffect(lookAheadKm, nearbyRadiusM) {
        if (selectedMode != null) pendingCameraFit = true
    }

    // ---- Camera fit (fires once per pendingCameraFit=true, only when MAP tab is active) ----
    val latestPois         by rememberUpdatedState(pois)
    val latestUserPos      by rememberUpdatedState(userPosition)
    val latestGpxTrack     by rememberUpdatedState(gpxTrack)
    val latestLookAheadKm  by rememberUpdatedState(lookAheadKm)

    LaunchedEffect(pendingCameraFit, selectedTab, mapAndStyle, selectedMode) {
        if (!pendingCameraFit || selectedTab != PoiTab.MAP) return@LaunchedEffect
        val ms = mapAndStyle ?: return@LaunchedEffect
        when (selectedMode) {
            PoiMode.NEARBY -> {
                val pos = latestUserPos ?: return@LaunchedEffect
                val builder = LatLngBounds.Builder().apply {
                    include(pos)
                    latestPois.forEach { include(LatLng(it.poi.lat, it.poi.lon)) }
                }
                try {
                    ms.first.easeCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 80), 500)
                } catch (_: Exception) {
                    ms.first.easeCamera(
                        CameraUpdateFactory.newLatLngZoom(pos, CyclingConstants.DEFAULT_MAP_ZOOM), 300
                    )
                }
            }
            PoiMode.ALONG_TRACK -> {
                val track = latestGpxTrack
                if (track == null || track.points.size < 2) return@LaunchedEffect
                val pos = latestUserPos
                if (pos != null) {
                    val projection = TrackGeometryUtils.projectPointOntoTrack(pos, track.points)
                    val sub = TrackGeometryUtils.extractSubTrack(
                        track.points, projection,
                        lookAheadM = latestLookAheadKm * 1000.0,
                        lookBackM  = latestLookAheadKm * 1000.0
                    )
                    if (sub.size >= 2) {
                        val b = LatLngBounds.Builder().apply { sub.forEach { include(it) } }
                        ms.first.easeCamera(
                            CameraUpdateFactory.newLatLngBounds(b.build(), CyclingConstants.TRACK_FIT_PADDING), 500
                        )
                    }
                } else {
                    val b = LatLngBounds.Builder().apply { track.points.forEach { include(it) } }
                    ms.first.easeCamera(
                        CameraUpdateFactory.newLatLngBounds(b.build(), CyclingConstants.TRACK_FIT_PADDING), 500
                    )
                }
            }
            null -> {}
        }
        pendingCameraFit = false
    }

    // Render GPX track on map (ALONG_TRACK only)
    LaunchedEffect(gpxTrack, mapAndStyle, selectedMode) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        if (trackRendered) {
            try { MapTrackRenderer.removeTrack(ms.second, "nav-track") } catch (_: Exception) {}
            removeUserMarker(ms.second)
            MapPoiRenderer.removePois(ms.second)
            trackRendered = false
        }
        if (selectedMode != PoiMode.ALONG_TRACK) return@LaunchedEffect
        val track = gpxTrack ?: return@LaunchedEffect
        if (track.points.size < 2) return@LaunchedEffect
        MapTrackRenderer.addTrack(
            ms.second, "nav-track", track.points,
            CyclingConstants.NAV_TRACK_COLOR,
            CyclingConstants.NAV_TRACK_WIDTH
        )
        trackRendered = true
    }

    // Render user position marker
    LaunchedEffect(userPosition, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        val pos = userPosition ?: return@LaunchedEffect
        renderUserMarker(ms.second, pos)
    }

    // Render POIs on map; re-apply highlight afterwards (addPois clears it via removePois)
    val latestSelectedPoiForRender by rememberUpdatedState(selectedPoi)
    LaunchedEffect(pois, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        MapPoiRenderer.addPois(ms.second, pois.map { it.poi })
        MapPoiRenderer.highlightPoi(ms.second, latestSelectedPoiForRender)
    }

    // Keep selected POI highlighted on map
    LaunchedEffect(selectedPoi, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        MapPoiRenderer.highlightPoi(ms.second, selectedPoi)
    }

    // Fly to POI selected from list at ~500 m zoom (zoom 15)
    LaunchedEffect(pendingZoomTo, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        val poi = pendingZoomTo ?: return@LaunchedEffect
        ms.first.easeCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(poi.lat, poi.lon), 15.0), 400
        )
        viewModel.consumePoiCameraMove()
    }

    // Map click listener — cluster expand or POI popup
    val latestPoisForClick by rememberUpdatedState(pois)
    val latestPopupPoiWD   by rememberUpdatedState(popupPoiWD)
    LaunchedEffect(mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        ms.first.addOnMapClickListener { latLng ->
            val screenPoint = ms.first.projection.toScreenLocation(latLng)

            // Cluster tap → zoom in
            val clusterFeatures = ms.first.queryRenderedFeatures(screenPoint, MapPoiRenderer.POI_CLUSTER_LAYER)
            if (clusterFeatures.isNotEmpty()) {
                val feature = clusterFeatures[0]
                val geo = feature.geometry()
                val lat = if (geo is Point) geo.latitude() else latLng.latitude
                val lon = if (geo is Point) geo.longitude() else latLng.longitude
                val zoom = ms.first.cameraPosition.zoom
                ms.first.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom + 2.0), 500
                )
                return@addOnMapClickListener true
            }

            // Individual POI tap → popup
            val poiFeatures = ms.first.queryRenderedFeatures(screenPoint, MapPoiRenderer.POI_LAYER)
            if (poiFeatures.isNotEmpty()) {
                val poiId = poiFeatures[0].getStringProperty("poiId")
                val poiWD = latestPoisForClick.find { it.poi.poiId == poiId }
                if (poiWD != null) {
                    viewModel.pickPoiFromMap(poiWD)
                    return@addOnMapClickListener true
                }
            }

            if (latestPopupPoiWD != null) viewModel.dismissPoi()
            false
        }
    }

    // ---- UI Layout ----
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(when (selectedMode) {
                    PoiMode.NEARBY      -> "POIs nearby"
                    PoiMode.ALONG_TRACK -> "POIs (GPX track)"
                    null                -> "Navigate"
                })
            },
            navigationIcon = {
                if (selectedMode != null) {
                    IconButton(onClick = { viewModel.resetMode() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            }
        )

        if (selectedMode == null) {
            ModeSelectionScreen(onModeSelected = { mode ->
                viewModel.setMode(mode)
                if (mode == PoiMode.ALONG_TRACK) {
                    gpxLauncher.launch(arrayOf("application/gpx+xml", "application/xml", "*/*"))
                }
            })
        } else {
            val isListTab = selectedTab == PoiTab.LIST

            // Tab row at the top, just below the app bar
            TabRow(selectedTabIndex = if (isListTab) 0 else 1) {
                Tab(
                    selected = isListTab,
                    onClick = { viewModel.setSelectedTab(PoiTab.LIST) },
                    text = { Text("List") }
                )
                Tab(
                    selected = !isListTab,
                    onClick = { viewModel.setSelectedTab(PoiTab.MAP) },
                    text = { Text("Map") }
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {

                // ---- LIST PANEL ----
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (isListTab) 1f else 0f }
                        .zIndex(if (isListTab) 1f else 0f)
                ) {
                    // Mode-specific controls
                    when (selectedMode) {
                        PoiMode.NEARBY -> {
                            val labelIdx = radiusSliderIndex.roundToInt()
                                .coerceIn(0, NEARBY_RADIUS_OPTIONS.lastIndex)
                            Text(
                                text = "Search radius: ${formatDistanceM(NEARBY_RADIUS_OPTIONS[labelIdx])}",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                            )
                            Slider(
                                value = radiusSliderIndex,
                                onValueChange = { radiusSliderIndex = it },
                                onValueChangeFinished = {
                                    val idx = radiusSliderIndex.roundToInt()
                                        .coerceIn(0, NEARBY_RADIUS_OPTIONS.lastIndex)
                                    viewModel.setNearbyRadius(NEARBY_RADIUS_OPTIONS[idx])
                                    viewModel.fetchPoisNearbyByRadius()
                                },
                                valueRange = 0f..4f,
                                steps = 3,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                        PoiMode.ALONG_TRACK -> {
                            if (gpxTrack != null) {
                                // Search radius (look-ahead + look-back) slider
                                val laIdx = lookAheadSliderIndex.roundToInt()
                                    .coerceIn(0, LOOK_AHEAD_OPTIONS.lastIndex)
                                Text(
                                    text = "Search radius: ${formatDistanceM(LOOK_AHEAD_OPTIONS[laIdx] * 1000.0)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                                )
                                Slider(
                                    value = lookAheadSliderIndex,
                                    onValueChange = { lookAheadSliderIndex = it },
                                    onValueChangeFinished = {
                                        val idx = lookAheadSliderIndex.roundToInt()
                                            .coerceIn(0, LOOK_AHEAD_OPTIONS.lastIndex)
                                        viewModel.setLookAheadKm(LOOK_AHEAD_OPTIONS[idx])
                                    },
                                    valueRange = 0f..4f,
                                    steps = 3,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )

                                // Search corridor slider
                                val corrIdx = corridorSliderIndex.roundToInt()
                                    .coerceIn(0, SEARCH_CORRIDOR_OPTIONS.lastIndex)
                                Text(
                                    text = "Search corridor: ${formatDistanceM(SEARCH_CORRIDOR_OPTIONS[corrIdx])}",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)
                                )
                                Slider(
                                    value = corridorSliderIndex,
                                    onValueChange = { corridorSliderIndex = it },
                                    onValueChangeFinished = {
                                        val idx = corridorSliderIndex.roundToInt()
                                            .coerceIn(0, SEARCH_CORRIDOR_OPTIONS.lastIndex)
                                        viewModel.setSearchCorridorM(SEARCH_CORRIDOR_OPTIONS[idx])
                                        viewModel.fetchPoisAlongTrack()
                                    },
                                    valueRange = 0f..4f,
                                    steps = 3,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                        null -> {}
                    }

                    // Error message
                    errorMessage?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                    }

                    // Category filter dropdown
                    CategoryFilterDropdown(
                        availableCategories = availableCategories,
                        selectedCategory = selectedCategory,
                        onFilter = { viewModel.setCategoryFilter(it) }
                    )
                    // Live result count
                    Text(
                        text = "Total: ${pois.size} results",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )

                    // POI list
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                            items(pois, key = { it.poi.poiId }) { poiWD ->
                                PoiRow(
                                    poiWD = poiWD,
                                    onClick = { viewModel.pickPoiFromList(poiWD) }
                                )
                            }

                            if (pois.isEmpty() && isLoadingPois) {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 32.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = "Determining GPS position...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            if (pois.isEmpty() && !isLoadingPois) {
                                item {
                                    val hint = when (selectedMode) {
                                        PoiMode.NEARBY      -> "No POIs found within ${formatDistanceM(nearbyRadiusM)}."
                                        PoiMode.ALONG_TRACK -> if (gpxTrack != null) "No POIs found along this route." else null
                                        else                -> null
                                    }
                                    hint?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(vertical = 16.dp)
                                        )
                                    }
                                }
                            }
                        }

                }

                // ---- MAP PANEL ----
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (!isListTab) 1f else 0f }
                        .zIndex(if (!isListTab) 1f else 0f)
                ) {
                    ComposableMapView(
                        modifier = Modifier.fillMaxSize(),
                        gesturesEnabled = true,
                        onMapReady = { map, style -> mapAndStyle = Pair(map, style) }
                    )

                    // Edge indicator: points toward user location when off-screen
                    UserLocationEdgeIndicator(
                        mapAndStyle = mapAndStyle,
                        userPosition = userPosition
                    )

                    // GPS re-center FAB
                    val latestMapForFab by rememberUpdatedState(mapAndStyle)
                    val latestUserPosForFab by rememberUpdatedState(userPosition)
                    SmallFloatingActionButton(
                        onClick = {
                            val pos = latestUserPosForFab ?: return@SmallFloatingActionButton
                            val ms = latestMapForFab ?: return@SmallFloatingActionButton
                            ms.first.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(pos, 16.0)
                            )
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Center on my location")
                    }

                    // POI popup — bottom-centre, raised above the FAB area
                    popupPoiWD?.let { poiWD ->
                        PoiPopupCard(
                            poiWithDistances = poiWD,
                            onOpenInMaps = { openInGoogleMaps(context, poiWD) },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 72.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSelectionScreen(onModeSelected: (PoiMode) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "How do you want to find POIs?",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onModeSelected(PoiMode.NEARBY) },
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Column {
                        Text("POIs nearby", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Search by radius from your current\nGPS position",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onModeSelected(PoiMode.ALONG_TRACK) },
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Route,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Column {
                        Text("POIs along GPX track", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Load a GPX route from your files and find stops along the way",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryFilterDropdown(
    availableCategories: List<String>,
    selectedCategory: String?,
    onFilter: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = if (selectedCategory == null) "All categories"
                        else categoryDisplayName(selectedCategory)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("All categories") },
                onClick = { onFilter(null); expanded = false }
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            availableCategories
                .sortedBy { categoryDisplayName(it) }
                .forEach { category ->
                    DropdownMenuItem(
                        text = { Text(categoryDisplayName(category)) },
                        onClick = { onFilter(category); expanded = false }
                    )
                }
        }
    }
}

@Composable
private fun PoiRow(
    poiWD: PoiWithDistances,
    onClick: () -> Unit
) {
    val poi = poiWD.poi
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color(categoryColor(poi.category).toColorInt()))
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = poi.name.ifEmpty { "Unnamed ${poi.category}" },
                style = MaterialTheme.typography.bodyMedium
            )
            val details = buildString {
                append(poi.category)
                poi.cuisine?.let { append(" ($it)") }
                poi.openingHours?.let { append(" · $it") }
            }
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Air distance only
        poiWD.airDistanceM?.let { m ->
            Text(
                text = formatDistanceM(m),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PoiPopupCard(
    poiWithDistances: PoiWithDistances,
    onOpenInMaps: () -> Unit,
    modifier: Modifier = Modifier
) {
    val poi = poiWithDistances.poi
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = poi.name.ifEmpty { "Unnamed" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = categoryDisplayName(poi.category),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                poi.openingHours?.let { hours ->
                    Text(
                        text = hours.replace("; ", "\n").replace(";", "\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                poiWithDistances.airDistanceM?.let { m ->
                    Text(
                        text = "${formatDistanceM(m)} away",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onOpenInMaps) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = "Open in Google Maps",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun openInGoogleMaps(context: Context, poiWD: PoiWithDistances) {
    val poi = poiWD.poi
    val encodedName = Uri.encode(poi.name.ifEmpty { categoryDisplayName(poi.category) })
    val uri = Uri.parse("geo:${poi.lat},${poi.lon}?q=${poi.lat},${poi.lon}($encodedName)")
    try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
}

/**
 * Format a distance in metres.
 * < 1 km  → nearest 10 m, minimum 10 m  (e.g. "140 m")
 * ≥ 1 km  → one decimal, comma separator  (e.g. "1,2 km")
 */
private fun formatDistanceM(m: Double): String {
    return if (m < 1000.0) {
        val rounded = ((m / 10.0).roundToInt() * 10).coerceAtLeast(10)
        "$rounded m"
    } else {
        val km = m / 1000.0
        "${"%.1f".format(km).replace('.', ',')} km"
    }
}

private fun categoryDisplayName(category: String): String = when (category) {
    "cafe"       -> "Café"
    "bakery"     -> "Bakery"
    "restaurant" -> "Restaurant"
    "fast_food"  -> "Fast food"
    "fuel"       -> "Fuel station"
    "friture"    -> "Friture"
    else         -> category.replaceFirstChar { it.uppercase() }
}

private fun categoryColor(category: String): String = when (category) {
    "fuel"       -> CyclingConstants.POI_COLOR_FUEL
    "cafe"       -> CyclingConstants.POI_COLOR_CAFE
    "bakery"     -> CyclingConstants.POI_COLOR_BAKERY
    "fast_food"  -> CyclingConstants.POI_COLOR_FAST_FOOD
    "friture"    -> CyclingConstants.POI_COLOR_FRITURE
    "restaurant" -> "#E64A19"
    else         -> "#607D8B"
}

private const val USER_MARKER_SOURCE    = "user-position-source"
private const val USER_MARKER_HALO_LAYER = "user-position-halo-layer"
private const val USER_MARKER_LAYER      = "user-position-layer"

private fun renderUserMarker(style: Style, position: LatLng) {
    removeUserMarker(style)
    val point = Point.fromLngLat(position.longitude, position.latitude)
    val source = GeoJsonSource(USER_MARKER_SOURCE, Feature.fromGeometry(point))
    style.addSource(source)

    // Translucent outer ring (accuracy halo)
    val halo = CircleLayer(USER_MARKER_HALO_LAYER, USER_MARKER_SOURCE).withProperties(
        PropertyFactory.circleColor(CyclingConstants.NAV_USER_MARKER_COLOR),
        PropertyFactory.circleRadius(26f),
        PropertyFactory.circleOpacity(0.2f),
        PropertyFactory.circleStrokeWidth(0f)
    )
    style.addLayer(halo)

    // Solid GPS dot on top
    val dot = CircleLayer(USER_MARKER_LAYER, USER_MARKER_SOURCE).withProperties(
        PropertyFactory.circleColor(CyclingConstants.NAV_USER_MARKER_COLOR),
        PropertyFactory.circleRadius(CyclingConstants.NAV_USER_MARKER_RADIUS),
        PropertyFactory.circleStrokeWidth(CyclingConstants.POI_MARKER_STROKE_WIDTH),
        PropertyFactory.circleStrokeColor("#FFFFFF")
    )
    style.addLayer(dot)
}

private fun removeUserMarker(style: Style) {
    try { style.removeLayer(USER_MARKER_HALO_LAYER) } catch (_: Exception) {}
    try { style.removeLayer(USER_MARKER_LAYER) } catch (_: Exception) {}
    try { style.removeSource(USER_MARKER_SOURCE) } catch (_: Exception) {}
}

/**
 * Draws a directional indicator at the screen edge pointing toward the user's GPS position
 * whenever that position is off-screen. Updates live as the camera moves.
 */
@Composable
private fun UserLocationEdgeIndicator(
    mapAndStyle: Pair<MapLibreMap, Style>?,
    userPosition: LatLng?
) {
    var userScreenPoint by remember { mutableStateOf<PointF?>(null) }
    val indicatorColor = MaterialTheme.colorScheme.primary

    DisposableEffect(mapAndStyle?.first, userPosition) {
        val map = mapAndStyle?.first
        val pos = userPosition
        if (map != null && pos != null) {
            fun update() { userScreenPoint = map.projection.toScreenLocation(pos) }
            update()
            val moveListener  = MapLibreMap.OnCameraMoveListener  { update() }
            val idleListener  = MapLibreMap.OnCameraIdleListener  { update() }
            map.addOnCameraMoveListener(moveListener)
            map.addOnCameraIdleListener(idleListener)
            onDispose {
                map.removeOnCameraMoveListener(moveListener)
                map.removeOnCameraIdleListener(idleListener)
            }
        } else {
            onDispose {}
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val sp = userScreenPoint ?: return@Canvas
        val w = size.width
        val h = size.height

        // Only show indicator when user is off-screen (with a small inset buffer)
        val buffer = 20f
        if (sp.x >= buffer && sp.x <= w - buffer && sp.y >= buffer && sp.y <= h - buffer) return@Canvas

        val cx = w / 2f
        val cy = h / 2f
        val dx = sp.x - cx
        val dy = sp.y - cy
        if (abs(dx) < 0.001f && abs(dy) < 0.001f) return@Canvas

        // Find intersection of the center→user ray with the screen boundary
        val edgePadding = 48f
        val halfW = w / 2f - edgePadding
        val halfH = h / 2f - edgePadding
        val scaleX = if (abs(dx) > 0.001f) halfW / abs(dx) else Float.MAX_VALUE
        val scaleY = if (abs(dy) > 0.001f) halfH / abs(dy) else Float.MAX_VALUE
        val scale = min(scaleX, scaleY)
        val ex = cx + dx * scale
        val ey = cy + dy * scale

        // Draw circle background
        drawCircle(color = indicatorColor, radius = 22f, center = Offset(ex, ey), alpha = 0.88f)

        // Draw arrow triangle pointing toward the user
        val angle = atan2(dy.toDouble(), dx.toDouble())
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()
        val tipX  = ex + cosA * 12f
        val tipY  = ey + sinA * 12f
        val w1X   = ex - cosA * 5f + (-sinA) * 8f
        val w1Y   = ey - sinA * 5f +   cosA  * 8f
        val w2X   = ex - cosA * 5f - (-sinA) * 8f
        val w2Y   = ey - sinA * 5f -   cosA  * 8f
        drawPath(
            path = Path().apply { moveTo(tipX, tipY); lineTo(w1X, w1Y); lineTo(w2X, w2Y); close() },
            color = Color.White
        )
    }
}
