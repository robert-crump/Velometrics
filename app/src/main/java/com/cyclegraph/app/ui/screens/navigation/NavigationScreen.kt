package com.cyclegraph.app.ui.screens.navigation

import android.graphics.PointF
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
import androidx.compose.material.icons.filled.MyLocation
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.graphics.toColorInt
import androidx.hilt.navigation.compose.hiltViewModel
import com.cyclegraph.app.domain.model.PoiWithDistances
import com.cyclegraph.app.domain.service.TrackGeometryUtils
import com.cyclegraph.app.ui.components.ComposableMapView
import com.cyclegraph.app.ui.components.MapPoiRenderer
import com.cyclegraph.app.ui.components.MapTrackRenderer
import com.cyclegraph.app.ui.components.PoiPopupCard
import com.cyclegraph.app.ui.components.openPoiInGoogleMaps
import com.cyclegraph.app.util.CyclingConstants
import com.cyclegraph.app.util.FormatUtils
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
    val isLoadingPois   by viewModel.isLoadingPois.collectAsState()
    val errorMessage    by viewModel.errorMessage.collectAsState()
    val poiSelection    by viewModel.poiSelection.collectAsState()
    val selectedPoi    = poiSelection.selected?.poi
    val popupPoiWD     = poiSelection.selected?.popup
    val pendingZoomTo  = poiSelection.pendingZoomTo
    val lookAheadKm     by viewModel.lookAheadKm.collectAsState()
    val selectedTab     by viewModel.selectedTab.collectAsState()
    val searchCorridorM     by viewModel.searchCorridorM.collectAsState()
    val availableCategories by viewModel.availableCategories.collectAsState()

    var mapAndStyle by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    var trackRendered by remember { mutableStateOf(false) }

    var pendingCameraFit by remember { mutableStateOf(false) }

    var lookAheadSliderIndex by remember {
        mutableStateOf(LOOK_AHEAD_OPTIONS.indexOf(lookAheadKm).let { if (it < 0) 1 else it }.toFloat())
    }
    var corridorSliderIndex by remember {
        mutableStateOf(SEARCH_CORRIDOR_OPTIONS.indexOf(searchCorridorM).let { if (it < 0) 1 else it }.toFloat())
    }

    val listState = rememberLazyListState()
    val context = LocalContext.current

    val gpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.loadGpxFromUri(uri, context.contentResolver) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setUserPositionFromPermission(granted)
        if (granted && viewModel.gpxTrack.value != null) {
            viewModel.fetchPoisAlongTrack()
        }
    }

    // Initialise slider positions on first composition
    LaunchedEffect(Unit) {
        lookAheadSliderIndex = LOOK_AHEAD_OPTIONS.indexOf(viewModel.lookAheadKm.value)
            .let { if (it < 0) 1 else it }.toFloat()
        corridorSliderIndex = SEARCH_CORRIDOR_OPTIONS.indexOf(viewModel.searchCorridorM.value)
            .let { if (it < 0) 1 else it }.toFloat()
        pendingCameraFit = true
    }

    // When GPX track loads → request permission + schedule camera fit
    LaunchedEffect(gpxTrack) {
        if (gpxTrack != null) {
            permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
            pendingCameraFit = true
        }
    }

    // Re-fit camera when look-ahead changes
    LaunchedEffect(lookAheadKm) { pendingCameraFit = true }

    val latestPois         by rememberUpdatedState(pois)
    val latestUserPos      by rememberUpdatedState(userPosition)
    val latestGpxTrack     by rememberUpdatedState(gpxTrack)
    val latestLookAheadKm  by rememberUpdatedState(lookAheadKm)

    // Camera fit — fires once per pendingCameraFit=true, only when MAP tab is active
    LaunchedEffect(pendingCameraFit, selectedTab, mapAndStyle) {
        if (!pendingCameraFit || selectedTab != PoiTab.MAP) return@LaunchedEffect
        val ms = mapAndStyle ?: return@LaunchedEffect
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
        pendingCameraFit = false
    }

    // Render GPX track on map
    LaunchedEffect(gpxTrack, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        if (trackRendered) {
            try { MapTrackRenderer.removeTrack(ms.second, "nav-track") } catch (_: Exception) {}
            removeUserMarker(ms.second)
            MapPoiRenderer.removePois(ms.second)
            trackRendered = false
        }
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

    // Render POIs on map; re-apply highlight afterwards
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

    // Fly to POI selected from list
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
    val isListTab = selectedTab == PoiTab.LIST

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Navigate") },
            actions = {
                IconButton(onClick = {
                    gpxLauncher.launch(arrayOf("application/gpx+xml", "application/xml", "*/*"))
                }) {
                    Icon(Icons.Default.Route, contentDescription = "Load GPX route")
                }
            }
        )

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
                if (gpxTrack != null) {
                    val laIdx = lookAheadSliderIndex.roundToInt()
                        .coerceIn(0, LOOK_AHEAD_OPTIONS.lastIndex)
                    Text(
                        text = "Search radius: ${FormatUtils.formatPoiDistance(LOOK_AHEAD_OPTIONS[laIdx] * 1000.0)}",
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

                    val corrIdx = corridorSliderIndex.roundToInt()
                        .coerceIn(0, SEARCH_CORRIDOR_OPTIONS.lastIndex)
                    Text(
                        text = "Search corridor: ${FormatUtils.formatPoiDistance(SEARCH_CORRIDOR_OPTIONS[corrIdx])}",
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

                errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }

                CategoryFilterDropdown(
                    availableCategories = availableCategories,
                    selectedCategory = selectedCategory,
                    onFilter = { viewModel.setCategoryFilter(it) }
                )
                Text(
                    text = "Total: ${pois.size} results",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )

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
                            val hint = if (gpxTrack != null) "No POIs found along this route." else null
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

                UserLocationEdgeIndicator(
                    mapAndStyle = mapAndStyle,
                    userPosition = userPosition
                )

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

                popupPoiWD?.let { poiWD ->
                    PoiPopupCard(
                        poiWithDistances = poiWD,
                        onOpenInMaps = { openPoiInGoogleMaps(context, poiWD) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 72.dp)
                    )
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
                        else FormatUtils.categoryDisplayName(selectedCategory)

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
                .sortedBy { FormatUtils.categoryDisplayName(it) }
                .forEach { category ->
                    DropdownMenuItem(
                        text = { Text(FormatUtils.categoryDisplayName(category)) },
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
                append(FormatUtils.categoryDisplayName(poi.category))
                poi.cuisine?.let { append(" ($it)") }
                poi.openingHours?.let { append(" · $it") }
            }
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        poiWD.airDistanceM?.let { m ->
            Text(
                text = FormatUtils.formatPoiDistance(m),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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

    val halo = CircleLayer(USER_MARKER_HALO_LAYER, USER_MARKER_SOURCE).withProperties(
        PropertyFactory.circleColor(CyclingConstants.NAV_USER_MARKER_COLOR),
        PropertyFactory.circleRadius(26f),
        PropertyFactory.circleOpacity(0.2f),
        PropertyFactory.circleStrokeWidth(0f)
    )
    style.addLayer(halo)

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

        val buffer = 20f
        if (sp.x >= buffer && sp.x <= w - buffer && sp.y >= buffer && sp.y <= h - buffer) return@Canvas

        val cx = w / 2f
        val cy = h / 2f
        val dx = sp.x - cx
        val dy = sp.y - cy
        if (abs(dx) < 0.001f && abs(dy) < 0.001f) return@Canvas

        val edgePadding = 48f
        val halfW = w / 2f - edgePadding
        val halfH = h / 2f - edgePadding
        val scaleX = if (abs(dx) > 0.001f) halfW / abs(dx) else Float.MAX_VALUE
        val scaleY = if (abs(dy) > 0.001f) halfH / abs(dy) else Float.MAX_VALUE
        val scale = min(scaleX, scaleY)
        val ex = cx + dx * scale
        val ey = cy + dy * scale

        drawCircle(color = indicatorColor, radius = 22f, center = Offset(ex, ey), alpha = 0.88f)

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
