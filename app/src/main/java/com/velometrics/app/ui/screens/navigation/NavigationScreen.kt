package com.velometrics.app.ui.screens.navigation

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.domain.service.FastWayHomeResult
import com.velometrics.app.domain.service.RideEstimate
import com.velometrics.app.ui.components.ComposableMapView
import com.velometrics.app.ui.components.MapTrackRenderer
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.FormatUtils
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
    onNavigateToHome: () -> Unit = {}
) {
    val offTrackDialogKm    by viewModel.offTrackDialogKm.collectAsState()
    val fastWayHomeResult   by viewModel.fastWayHomeResult.collectAsState()
    val fastWayHomeMessage  by viewModel.fastWayHomeMessage.collectAsState()
    val isFindingFastWayHome by viewModel.isFindingFastWayHome.collectAsState()
    val homeLocation        by viewModel.homeLocation.collectAsState()

    var mapAndStyle by remember { mutableStateOf<Pair<MapLibreMap, Style>?>(null) }
    var fastWayHomeRendered by remember { mutableStateOf(false) }

    // Render Fast Way Home overlay (route line + home marker)
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

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Navigate") })
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

            SmallFloatingActionButton(
                onClick = { viewModel.findFastWayHome() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
            ) {
                Icon(Icons.Default.Home, contentDescription = "Find fast way home")
            }

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
