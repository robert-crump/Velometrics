package com.velometrics.app.ui.screens.repeatedintervaldetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.ui.components.ComposableMapView
import com.velometrics.app.ui.components.MapTrackRenderer
import com.velometrics.app.util.FormatUtils
import com.velometrics.app.util.GpsTrackParser
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeatedIntervalDetailScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToSession: (Long) -> Unit = {},
    viewModel: RepeatedIntervalDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var isEditing by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(uiState.repeatedInterval?.name) {
        uiState.repeatedInterval?.name?.let { editName = it }
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
                                viewModel.rename(editName)
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
                            viewModel.rename(editName)
                            isEditing = false
                        }) {
                            Icon(Icons.Default.Check, contentDescription = "Save name")
                        }
                    } else {
                        IconButton(onClick = {
                            editName = uiState.repeatedInterval?.name ?: ""
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // ─── Matched edge geometry map ───
            if (uiState.trackPoints.isNotEmpty()) {
                IntervalPreviewMap(
                    points = uiState.trackPoints,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .padding(horizontal = 16.dp)
                )
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ─── Statistics: 2x2 grid, no card, same style as route detail ───
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        MetricStatCell(
                            label = "Times",
                            value = "${repeatedInterval.intervals.size}",
                            modifier = Modifier.weight(1f)
                        )
                        MetricStatCell(
                            label = "Distance",
                            value = FormatUtils.formatDistance(repeatedInterval.distanceM / 1000.0),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        MetricStatCell(
                            label = "Avg duration",
                            value = FormatUtils.formatDuration(uiState.avgDurationSec),
                            modifier = Modifier.weight(1f)
                        )
                        MetricStatCell(
                            label = "Avg speed",
                            value = FormatUtils.formatSpeed(uiState.avgSpeedKmh),
                            modifier = Modifier.weight(1f)
                        )
                        MetricStatCell(
                            label = "Avg power",
                            value = FormatUtils.formatPower(uiState.avgPowerW),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // ─── Raw intervals list ───
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Occurrences", style = MaterialTheme.typography.titleMedium)
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
            }
        }
    }

    LaunchedEffect(isEditing) {
        if (isEditing) focusRequester.requestFocus()
    }
}

@Composable
private fun IntervalPreviewMap(
    points: List<LatLng>,
    modifier: Modifier = Modifier
) {
    val shapes = MaterialTheme.shapes

    ComposableMapView(
        modifier = modifier.clip(shapes.extraSmall),
        gesturesEnabled = false,
        onMapReady = { map, style ->
            MapTrackRenderer.addTrack(style, "interval-preview", points, "#2196F3")
            val bounds = GpsTrackParser.computeBounds(points)
            if (bounds != null) {
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 48))
            }
        }
    )
}

@Composable
private fun MetricStatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
