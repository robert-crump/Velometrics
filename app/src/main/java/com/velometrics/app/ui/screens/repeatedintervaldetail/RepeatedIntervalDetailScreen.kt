package com.velometrics.app.ui.screens.repeatedintervaldetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.ui.components.ComposableMapView
import com.velometrics.app.ui.components.EditableTopBarActions
import com.velometrics.app.ui.components.EditableTopBarTitle
import com.velometrics.app.ui.components.LoadingBox
import com.velometrics.app.ui.components.MapTrackRenderer
import com.velometrics.app.ui.components.MetricCell
import com.velometrics.app.ui.components.NotFoundBox
import com.velometrics.app.ui.components.PullUpDrawer
import com.velometrics.app.ui.components.rememberEditableTopBarTitleState
import com.velometrics.app.util.CyclingConstants.TRACK_FIT_PADDING
import com.velometrics.app.util.FormatUtils
import com.velometrics.app.util.GpsTrackParser
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeatedIntervalDetailScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToSession: (Long) -> Unit = {},
    viewModel: RepeatedIntervalDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val titleState = rememberEditableTopBarTitleState(uiState.repeatedInterval?.name)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    EditableTopBarTitle(
                        state = titleState,
                        displayName = uiState.repeatedInterval?.name ?: "Interval",
                        onRename = { viewModel.rename(it) }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    EditableTopBarActions(
                        state = titleState,
                        currentName = uiState.repeatedInterval?.name,
                        renameContentDescription = "Rename interval",
                        onRename = { viewModel.rename(it) }
                    )
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            LoadingBox()
            return@Scaffold
        }

        val repeatedInterval = uiState.repeatedInterval
        if (repeatedInterval == null) {
            NotFoundBox(text = "Interval not found", modifier = Modifier.padding(padding))
            return@Scaffold
        }

        var drawerFraction by remember { mutableStateOf(0.5f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Full-screen map background (interactive)
            RepeatedIntervalDetailMap(
                trackPoints = uiState.trackPoints,
                drawerFraction = drawerFraction
            )

            // Pull-up drawer with all statistics; opens at 50%
            PullUpDrawer(
                initialFraction = 0.5f,
                onFractionSnapped = { drawerFraction = it }
            ) {
                RepeatedIntervalSummaryGrid(
                    timesCount = repeatedInterval.intervals.size,
                    distanceM = repeatedInterval.distanceM,
                    avgDurationSec = uiState.avgDurationSec,
                    avgSpeedKmh = uiState.avgSpeedKmh,
                    avgPowerW = uiState.avgPowerW
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("History", style = MaterialTheme.typography.titleMedium)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        repeatedInterval.intervals.sortedByDescending { it.startTimestamp }.forEach { interval ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToSession(interval.cyclingSessionId) }
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    FormatUtils.formatDate(interval.startTimestamp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    FormatUtils.formatDuration(interval.durationNormalizedSec),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun RepeatedIntervalDetailMap(
    trackPoints: List<LatLng>,
    drawerFraction: Float
) {
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val boundsRef = remember { mutableStateOf<LatLngBounds?>(null) }
    val density = LocalDensity.current

    if (trackPoints.isEmpty()) {
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
                    MapTrackRenderer.addTrack(style, "repeated-interval-detail", trackPoints, "#2196F3")
                    val bounds = GpsTrackParser.computeBounds(trackPoints)
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
                }
            )
        }
    }
}

@Composable
private fun RepeatedIntervalSummaryGrid(
    timesCount: Int,
    distanceM: Double,
    avgDurationSec: Int,
    avgSpeedKmh: Double,
    avgPowerW: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                MetricCell(label = "Times", value = "$timesCount")
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(label = "Distance", value = FormatUtils.formatDistance(distanceM / 1000.0))
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(label = "Avg duration", value = FormatUtils.formatDuration(avgDurationSec))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                MetricCell(label = "Avg speed", value = FormatUtils.formatSpeed(avgSpeedKmh))
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(label = "Avg power", value = FormatUtils.formatPower(avgPowerW))
            }
        }
    }
}
