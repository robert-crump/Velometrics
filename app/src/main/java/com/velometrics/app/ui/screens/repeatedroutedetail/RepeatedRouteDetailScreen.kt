package com.velometrics.app.ui.screens.repeatedroutedetail

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.ui.components.ComposableMapView
import com.velometrics.app.ui.components.MapTrackRenderer
import com.velometrics.app.ui.components.ScatterPlotChart
import com.velometrics.app.ui.components.ScatterPoint
import com.velometrics.app.ui.components.SpeedHistogramChartAvg
import com.velometrics.app.util.FormatUtils
import com.velometrics.app.util.GpsTrackParser
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeatedRouteDetailScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToSession: (Long) -> Unit = {},
    viewModel: RepeatedRouteDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var isEditing by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // Slider index for the power → duration estimator (local UI state)
    var sliderIndex by remember { mutableIntStateOf(1) }
    val model = uiState.powerDurationModel
    LaunchedEffect(model) {
        if (model != null) sliderIndex = model.defaultIndex
    }

    LaunchedEffect(uiState.route?.name) {
        uiState.route?.name?.let { editName = it }
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
                                viewModel.renameRoute(editName)
                                isEditing = false
                            }),
                            textStyle = MaterialTheme.typography.titleMedium
                        )
                    } else {
                        Text(uiState.route?.name ?: "Route")
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
                            viewModel.renameRoute(editName)
                            isEditing = false
                        }) {
                            Icon(Icons.Default.Check, contentDescription = "Save name")
                        }
                    } else {
                        IconButton(onClick = {
                            editName = uiState.route?.name ?: ""
                            isEditing = true
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename route")
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

        val route = uiState.route
        if (route == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Route not found")
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
            // ─── Representative track map ───
            val repTrack = route.representativeTrack
            if (repTrack != null) {
                val points = remember(repTrack) {
                    repTrack.mapNotNull { coords ->
                        if (coords.size >= 2) LatLng(coords[0], coords[1]) else null
                    }
                }
                if (points.isNotEmpty()) {
                    RoutePreviewMap(
                        points = points,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .padding(horizontal = 16.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ─── Statistics: 2×3 grid, no card, same style as session detail ───
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        MetricStatCell(
                            label = "Times",
                            value = "${route.sessions.size}",
                            modifier = Modifier.weight(1f)
                        )
                        MetricStatCell(
                            label = "Avg duration",
                            value = FormatUtils.formatDuration(uiState.avgNetDurationSec),
                            modifier = Modifier.weight(1f)
                        )
                        MetricStatCell(
                            label = "Avg distance",
                            value = FormatUtils.formatDistance(uiState.avgDistanceKm),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        MetricStatCell(
                            label = "Avg speed",
                            value = FormatUtils.formatSpeed(uiState.avgSpeedKmh),
                            modifier = Modifier.weight(1f)
                        )
                        MetricStatCell(
                            label = "Avg power",
                            value = uiState.avgPowerW?.let { FormatUtils.formatPower(it) } ?: "--",
                            modifier = Modifier.weight(1f)
                        )
                        MetricStatCell(
                            label = "Avg NP",
                            value = uiState.avgNormalizedPowerW?.let { FormatUtils.formatPower(it) } ?: "--",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // ─── Speed distribution (average across all sessions) ───
                if (uiState.avgSpeedHistogram.isNotEmpty()) {
                    SpeedHistogramChartAvg(percentages = uiState.avgSpeedHistogram)
                }

                // ─── Duration estimator (only when ≥2 sessions with power data) ───
                if (model != null) {
                    val safeIndex = sliderIndex.coerceIn(0, model.sliderValues.size - 1)
                    val selectedPower = model.sliderValues[safeIndex]
                    val estimatedSec = model.estimatedSec(selectedPower)
                    val estimatedSpeedKmh = if (estimatedSec != null && estimatedSec > 0)
                        model.medianDistanceKm / estimatedSec * 3600.0
                    else null

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Duration Estimate", style = MaterialTheme.typography.titleMedium)
                            HorizontalDivider()

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${selectedPower} W",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    estimatedSec?.let { FormatUtils.formatDurationHhMm(it) } ?: "--",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (estimatedSpeedKmh != null) {
                                    Text(
                                        FormatUtils.formatSpeed(estimatedSpeedKmh),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Slider(
                                value = safeIndex.toFloat(),
                                onValueChange = { sliderIndex = it.roundToInt() },
                                valueRange = 0f..(model.sliderValues.size - 1).toFloat(),
                                steps = (model.sliderValues.size - 2).coerceAtLeast(0)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${model.sliderValues.first()} W",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "${model.sliderValues.last()} W",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // ─── Scatter plots (only when power data exists) ───
                if (uiState.showPowerPlots) {
                    if (uiState.speedPowerPoints.isNotEmpty()) {
                        val xMin = floor(uiState.speedPowerPoints.minOf { it.first } / 10f) * 10f
                        val xMax = ceil(uiState.speedPowerPoints.maxOf { it.first } / 10f) * 10f
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Avg Power vs Speed",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(Modifier.height(8.dp))
                                ScatterPlotChart(
                                    points = uiState.speedPowerPoints.map { (x, y) ->
                                        ScatterPoint(x, y)
                                    },
                                    xLabel = "Avg Power (W)",
                                    yLabel = "Avg Speed (km/h)",
                                    xMin = xMin,
                                    xMax = xMax,
                                    xTickFormat = "%.0f"
                                )
                            }
                        }
                    }

                    if (uiState.speedNpPoints.isNotEmpty()) {
                        val xMin = floor(uiState.speedNpPoints.minOf { it.first } / 10f) * 10f
                        val xMax = ceil(uiState.speedNpPoints.maxOf { it.first } / 10f) * 10f
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Normalized Power vs Speed",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(Modifier.height(8.dp))
                                ScatterPlotChart(
                                    points = uiState.speedNpPoints.map { (x, y) ->
                                        ScatterPoint(x, y)
                                    },
                                    xLabel = "NP (W)",
                                    yLabel = "Avg Speed (km/h)",
                                    xMin = xMin,
                                    xMax = xMax,
                                    xTickFormat = "%.0f"
                                )
                            }
                        }
                    }
                }

                // ─── Sessions list ───
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Sessions", style = MaterialTheme.typography.titleMedium)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        route.sessions.sortedByDescending { it.sessionStart }.forEach { session ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToSession(session.id) }
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    FormatUtils.formatDate(session.sessionStart),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    FormatUtils.formatDistance(session.distanceKm),
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
private fun RoutePreviewMap(
    points: List<LatLng>,
    modifier: Modifier = Modifier
) {
    val shapes = MaterialTheme.shapes

    ComposableMapView(
        modifier = modifier.clip(shapes.extraSmall),
        gesturesEnabled = false,
        onMapReady = { map, style ->
            MapTrackRenderer.addTrack(style, "route-preview", points, "#2196F3")
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
