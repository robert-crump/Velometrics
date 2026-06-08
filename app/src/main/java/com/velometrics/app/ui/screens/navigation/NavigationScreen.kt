package com.velometrics.app.ui.screens.navigation

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.domain.model.PoiWithDistances
import com.velometrics.app.domain.service.FastWayHomeResult
import com.velometrics.app.domain.service.RideEstimate
import com.velometrics.app.ui.components.ComposableMapView
import com.velometrics.app.ui.components.MapPoiRenderer
import com.velometrics.app.ui.components.MapTrackRenderer
import com.velometrics.app.ui.components.PoiPopupCard
import com.velometrics.app.ui.components.PullUpDrawer
import com.velometrics.app.ui.components.openPoiInGoogleMaps
import com.velometrics.app.ui.intent.GpxIntentViewModel
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.FormatUtils
import com.velometrics.app.util.OpeningHoursUtils
import com.velometrics.app.util.PolylineDecoder
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
fun NavigationScreen(
    viewModel: NavigationViewModel = hiltViewModel(),
    intentViewModel: GpxIntentViewModel = hiltViewModel(LocalContext.current as ComponentActivity),
    onNavigateToHome: () -> Unit = {}
) {
    val gpxTrack        by viewModel.gpxTrack.collectAsState()
    val userPosition    by viewModel.userPosition.collectAsState()
    val pois            by viewModel.pois.collectAsState()
    val isLoadingPois   by viewModel.isLoadingPois.collectAsState()
    val errorMessage    by viewModel.errorMessage.collectAsState()
    val poiSelection    by viewModel.poiSelection.collectAsState()
    val lookAheadOption by viewModel.lookAheadOption.collectAsState()
    val pendingCameraBounds by viewModel.pendingCameraBounds.collectAsState()
    val offTrackDialogKm    by viewModel.offTrackDialogKm.collectAsState()
    val fastWayHomeResult   by viewModel.fastWayHomeResult.collectAsState()
    val fastWayHomeMessage  by viewModel.fastWayHomeMessage.collectAsState()
    val isFindingFastWayHome by viewModel.isFindingFastWayHome.collectAsState()
    val homeLocation        by viewModel.homeLocation.collectAsState()

    val context = LocalContext.current
    val pendingUri by intentViewModel.pendingGpxUri.collectAsState()
    LaunchedEffect(pendingUri) {
        val uri = pendingUri ?: return@LaunchedEffect
        viewModel.loadGpxFromUri(uri, context.contentResolver)
        intentViewModel.consumePendingUri()
    }

    val selectedPoi  = poiSelection.selected?.poi
    val popupPoiWD   = poiSelection.selected?.popup
    val pendingZoomTo = poiSelection.pendingZoomTo

    var mapAndStyle by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    var trackRendered by remember { mutableStateOf(false) }
    var fastWayHomeRendered by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val peekHeight = (screenHeightDp * 0.25f).dp

    val gpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.loadGpxFromUri(uri, context.contentResolver) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        if (viewModel.gpxTrack.value != null) viewModel.refreshUserPosition()
    }

    LaunchedEffect(gpxTrack) {
        if (gpxTrack != null) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // Camera fit — fires once per GPX load, fits full track into view
    LaunchedEffect(gpxTrack, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        val track = gpxTrack ?: return@LaunchedEffect
        if (track.points.size < 2) return@LaunchedEffect
        val bounds = org.maplibre.android.geometry.LatLngBounds.Builder()
            .apply { track.points.forEach { include(it) } }.build()
        ms.first.easeCamera(
            CameraUpdateFactory.newLatLngBounds(bounds, CyclingConstants.TRACK_FIT_PADDING), 500
        )
    }

    // Look-ahead camera animation — fires when ViewModel sets pending bounds
    LaunchedEffect(pendingCameraBounds, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        val bounds = pendingCameraBounds ?: return@LaunchedEffect
        ms.first.easeCamera(
            CameraUpdateFactory.newLatLngBounds(bounds, CyclingConstants.TRACK_FIT_PADDING), 600
        )
        viewModel.consumeCameraFit()
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

    // Render Fast Way Home overlay (route line + home marker), distinct from a loaded GPX track
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
            CyclingConstants.FAST_WAY_HOME_TRACK_COLOR,
            CyclingConstants.FAST_WAY_HOME_TRACK_WIDTH
        )
        homeLocation?.let { renderHomeMarker(ms.second, it) }
        fastWayHomeRendered = true
        val bounds = org.maplibre.android.geometry.LatLngBounds.Builder().apply {
            points.forEach { include(it) }
            homeLocation?.let { include(it) }
        }.build()
        ms.first.easeCamera(
            CameraUpdateFactory.newLatLngBounds(bounds, CyclingConstants.TRACK_FIT_PADDING), 600
        )
    }

    // Render POIs on map; re-apply highlight afterwards
    val latestSelectedPoiForRender by rememberUpdatedState(selectedPoi)
    LaunchedEffect(pois, mapAndStyle) {
        val ms = mapAndStyle ?: return@LaunchedEffect
        MapPoiRenderer.addPois(ms.second, pois.map { it.poi })
        MapPoiRenderer.highlightPoi(ms.second, latestSelectedPoiForRender)
    }

    // Keep selected POI highlighted
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
                ms.first.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), ms.first.cameraPosition.zoom + 2.0), 500
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

    // Off-track dialog
    offTrackDialogKm?.let { km ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissOffTrackDialog() },
            title = { Text("Off Track") },
            text = { Text("You are currently off-track by %.1f km.".format(km)) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissOffTrackDialog() }) { Text("Dismiss") }
            }
        )
    }

    // ---- UI Layout ----
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Navigate") },
                actions = {
                    if (gpxTrack != null) {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Load new .gpx") },
                                    onClick = {
                                        menuExpanded = false
                                        gpxLauncher.launch(arrayOf("application/gpx+xml", "application/xml", "*/*"))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear map") },
                                    onClick = {
                                        menuExpanded = false
                                        viewModel.clearGpx()
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { outerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(outerPadding)
        ) {
            ComposableMapView(
                modifier = Modifier.fillMaxSize(),
                gesturesEnabled = true,
                onMapReady = { map, style -> mapAndStyle = Pair(map, style) }
            )

            // Action FABs — Add .gpx and Fast Way Home stay visible regardless of track state;
            // Locate-me only appears once a track is loaded. Stacked so the map stays visible underneath.
            val fabBottomPadding = if (gpxTrack != null) peekHeight + 16.dp else 16.dp
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = fabBottomPadding)
            ) {
                if (gpxTrack != null) {
                    SmallFloatingActionButton(onClick = { viewModel.refreshUserPosition() }) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Locate me on track")
                    }
                }
                SmallFloatingActionButton(onClick = { viewModel.findFastWayHome() }) {
                    Icon(Icons.Default.Home, contentDescription = "Find fast way home")
                }
                SmallFloatingActionButton(onClick = {
                    gpxLauncher.launch(arrayOf("application/gpx+xml", "application/xml", "*/*"))
                }) {
                    Icon(Icons.Default.Route, contentDescription = "Add .gpx")
                }
            }

            // Fast Way Home result card — shown after tapping the house FAB
            if (isFindingFastWayHome || fastWayHomeResult != null || fastWayHomeMessage != null) {
                FastWayHomeCard(
                    result = fastWayHomeResult,
                    message = fastWayHomeMessage,
                    isLoading = isFindingFastWayHome,
                    onDismiss = { viewModel.clearFastWayHome() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // POI popup card
            popupPoiWD?.let { poiWD ->
                PoiPopupCard(
                    poiWithDistances = poiWD,
                    onOpenInMaps = { openPoiInGoogleMaps(context, poiWD) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = peekHeight + 8.dp)
                )
            }

            // Drawer — only present when a GPX is loaded; resets on new GPX load
            key(gpxTrack) {
                if (gpxTrack != null) {
                    PullUpDrawer(
                        snapFractions = listOf(0.25f, 0.50f, 1.00f),
                        initialFraction = 0.25f
                    ) {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            LookAheadOption.entries.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = lookAheadOption == option,
                                    onClick = { viewModel.setLookAheadOption(option) },
                                    shape = SegmentedButtonDefaults.itemShape(index, LookAheadOption.entries.size),
                                    label = { Text("Next ${option.km}km") }
                                )
                            }
                        }

                        HorizontalDivider()

                        errorMessage?.let { msg ->
                            Text(
                                text = msg,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        }

                        if (isLoadingPois && pois.isEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Finding POIs...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else if (pois.isEmpty() && !isLoadingPois) {
                            Text(
                                text = "No POIs found along this route.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            pois.forEach { poiWD ->
                                PoiRow(
                                    poiWD = poiWD,
                                    onClick = { viewModel.pickPoiFromList(poiWD) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FastWayHomeCard(
    result: FastWayHomeResult?,
    message: String?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Fast Way Home", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss")
                }
            }

            when {
                isLoading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Finding fastest way home…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                result != null -> {
                    FastWayHomeEstimateRow("Fast", result.fast)
                    FastWayHomeEstimateRow("Avg", result.avg)
                    FastWayHomeEstimateRow("Slow", result.slow)
                    if (result.anySegmentsEstimated) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Some segments use estimated speeds",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                message != null -> {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FastWayHomeEstimateRow(label: String, estimate: RideEstimate) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "${FormatUtils.formatDuration(estimate.durationSec.toInt())} · ${FormatUtils.formatPower(estimate.avgPowerW.toInt())}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PoiRow(poiWD: PoiWithDistances, onClick: () -> Unit) {
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = FormatUtils.categoryDisplayName(poi.category),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OpenClosedBadge(poi.openingHours)
            }
        }

        poiWD.trackDistanceM?.takeIf { it >= 0.0 }?.let { m ->
            Text(
                text = FormatUtils.formatPoiDistance(m),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OpenClosedBadge(openingHours: String?) {
    val isOpen = openingHours?.let { OpeningHoursUtils.isOpenNow(it) } ?: return
    val bgColor = if (isOpen) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
    val label = if (isOpen) "Open" else "Closed"
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
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

private const val USER_MARKER_SOURCE     = "user-position-source"
private const val USER_MARKER_HALO_LAYER = "user-position-halo-layer"
private const val USER_MARKER_LAYER      = "user-position-layer"

private fun renderUserMarker(style: Style, position: LatLng) {
    removeUserMarker(style)
    val point  = Point.fromLngLat(position.longitude, position.latitude)
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
    try { style.removeLayer(USER_MARKER_LAYER) }      catch (_: Exception) {}
    try { style.removeSource(USER_MARKER_SOURCE) }    catch (_: Exception) {}
}

private const val FAST_WAY_HOME_TRACK_ID = "fast-way-home-track"
private const val HOME_MARKER_SOURCE     = "fast-way-home-marker-source"
private const val HOME_MARKER_LAYER      = "fast-way-home-marker-layer"

private fun renderHomeMarker(style: Style, position: LatLng) {
    removeHomeMarker(style)
    val point  = Point.fromLngLat(position.longitude, position.latitude)
    val source = GeoJsonSource(HOME_MARKER_SOURCE, Feature.fromGeometry(point))
    style.addSource(source)

    val marker = CircleLayer(HOME_MARKER_LAYER, HOME_MARKER_SOURCE).withProperties(
        PropertyFactory.circleColor(CyclingConstants.FAST_WAY_HOME_TRACK_COLOR),
        PropertyFactory.circleRadius(CyclingConstants.NAV_USER_MARKER_RADIUS),
        PropertyFactory.circleStrokeWidth(CyclingConstants.POI_MARKER_STROKE_WIDTH),
        PropertyFactory.circleStrokeColor("#FFFFFF")
    )
    style.addLayer(marker)
}

private fun removeHomeMarker(style: Style) {
    try { style.removeLayer(HOME_MARKER_LAYER) }   catch (_: Exception) {}
    try { style.removeSource(HOME_MARKER_SOURCE) } catch (_: Exception) {}
}
