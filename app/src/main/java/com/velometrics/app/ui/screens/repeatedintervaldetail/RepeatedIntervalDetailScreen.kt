package com.velometrics.app.ui.screens.repeatedintervaldetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.ui.components.ComposableMapView
import com.velometrics.app.ui.components.MapTrackRenderer
import com.velometrics.app.ui.components.MetricCell
import com.velometrics.app.ui.components.PullUpDrawer
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
    var isEditing by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(uiState.repeatedInterval?.name) {
        if (!isEditing) {
            uiState.repeatedInterval?.name?.let { editName = TextFieldValue(it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isEditing) {
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                viewModel.rename(editName.text)
                                isEditing = false
                            }),
                            textStyle = MaterialTheme.typography.titleMedium
                        )
                    } else {
                        Text(uiState.repeatedInterval?.name ?: "Interval")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isEditing) {
                        IconButton(onClick = {
                            viewModel.rename(editName.text)
                            isEditing = false
                        }) {
                            Icon(Icons.Default.Check, contentDescription = "Save name")
                        }
                    } else {
                        IconButton(onClick = {
                            val name = uiState.repeatedInterval?.name ?: ""
                            editName = TextFieldValue(name, TextRange(name.length))
                            isEditing = true
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename interval")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val repeatedInterval = uiState.repeatedInterval
        if (repeatedInterval == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Interval not found")
            }
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

    LaunchedEffect(isEditing) {
        if (isEditing) focusRequester.requestFocus()
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
