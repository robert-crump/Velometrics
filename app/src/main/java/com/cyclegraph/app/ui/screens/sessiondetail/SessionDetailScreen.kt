package com.cyclegraph.app.ui.screens.sessiondetail

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cyclegraph.app.domain.model.CyclingSession
import com.cyclegraph.app.domain.model.IntervalSession
import com.cyclegraph.app.domain.service.SessionComparison
import com.cyclegraph.app.ui.components.*
import com.cyclegraph.app.util.CyclingConstants.TRACK_FIT_PADDING
import com.cyclegraph.app.util.FormatUtils
import com.cyclegraph.app.util.GpsTrackParser
import com.cyclegraph.app.util.MapOverlayUtils
import kotlinx.coroutines.launch
import kotlin.math.abs
import org.maplibre.android.camera.CameraUpdateFactory
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: SessionDetailViewModel = hiltViewModel()
) {
    val session by viewModel.session.collectAsState()
    val intervals by viewModel.intervals.collectAsState()
    val comparison by viewModel.comparison.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ride details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (session == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Session not found")
            }
        } else {
            val s = session!!
            var drawerFraction by remember { mutableStateOf(0.5f) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Full-screen map background (interactive)
                SessionDetailMap(
                    gpsTrack = s.gpsTrack,
                    intervals = intervals,
                    drawerFraction = drawerFraction
                )

                // Pull-up drawer with all statistics; opens at 50%
                PullUpDrawer(
                    initialFraction = 0.5f,
                    onFractionSnapped = { drawerFraction = it }
                ) {
                    RideSummaryGrid(session = s, comparison = comparison)

                    if (s.hasPower && s.powerZoneDistribution != null) {
                        PowerZoneChart(powerZones = s.powerZoneDistribution!!)
                    } else if (!s.hasPower) {
                        // Session has no power data — show a placeholder card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Power Zones",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No power data available",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    if (s.hasPower && s.fatEfficiencyHistogram != null) {
                        FatEfficiencyHistogram(histogram = s.fatEfficiencyHistogram!!)
                    }

                    if (s.hasPower && s.sprintCount > 0 && s.sprintHistogram != null) {
                        SprintCard(sprintHistogram = s.sprintHistogram!!)
                    }

                    if (s.hasPower && intervals.isNotEmpty()) {
                        IntervalListCard(
                            intervals = intervals,
                            onIntervalClick = {}
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SessionDetailMap(
    gpsTrack: String?,
    intervals: List<IntervalSession>,
    drawerFraction: Float
) {
    val points = remember(gpsTrack) { GpsTrackParser.parse(gpsTrack) }
    val mapStyleRef = remember { mutableStateOf<Style?>(null) }
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val boundsRef = remember { mutableStateOf<LatLngBounds?>(null) }
    val density = LocalDensity.current

    LaunchedEffect(mapStyleRef.value, intervals) {
        val style = mapStyleRef.value ?: return@LaunchedEffect
        MapIntervalRenderer.removeIntervalOverlay(style)
        if (intervals.isNotEmpty()) {
            MapIntervalRenderer.renderUngroupedIntervals(style, intervals)
        }
    }

    if (points.isEmpty()) {
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
                    MapTrackRenderer.addTrack(style, "session-detail", points, "#2196F3")
                    val bounds = GpsTrackParser.computeBounds(points)
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
                    mapStyleRef.value = style
                }
            )

            // Interval duration legend overlay
            if (intervals.isNotEmpty()) {
                IntervalMapLegend(
                    intervals = intervals,
                    modifier = Modifier.align(Alignment.TopStart)
                )
            }
        }
    }
}

private fun hexToComposeColor(hex: String): Color {
    val clean = hex.removePrefix("#")
    val r = clean.substring(0, 2).toInt(16)
    val g = clean.substring(2, 4).toInt(16)
    val b = clean.substring(4, 6).toInt(16)
    return Color(r, g, b)
}

/**
 * Overlay legend showing which duration range bands are present in the session.
 * Bands are listed shortest-first; only bands with at least one matching interval are shown.
 */
@Composable
private fun IntervalMapLegend(intervals: List<IntervalSession>, modifier: Modifier = Modifier) {
    // (label, representative duration for color, presence predicate) — ascending duration order
    val bands = listOf(
        Triple("< 3:30 min", 165, intervals.any { it.durationNormalizedSec < 210 }),
        Triple("3:30–5 min",  255, intervals.any { it.durationNormalizedSec in 210..299 }),
        Triple("5–6:30 min",  345, intervals.any { it.durationNormalizedSec in 300..389 }),
        Triple("> 6:30 min",  435, intervals.any { it.durationNormalizedSec >= 390 })
    ).filter { it.third }

    if (bands.isEmpty()) return

    Surface(
        modifier = modifier.padding(8.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = "Intervals",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(2.dp))
            bands.forEach { (label, midSec, _) ->
                val color = hexToComposeColor(MapOverlayUtils.normalizedDurationToColor(midSec))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(color, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

/**
 * A snappy pull-up drawer with three snap positions: 15%, 50%, and 100%.
 *
 * Behaviour:
 * - The 44dp handle at the top can always be dragged to resize.
 * - When the drawer is below 100%, the entire content area also acts as a drag handle
 *   (upward drags expand the drawer instead of scrolling). Scrolling is only enabled
 *   once the drawer is fully expanded to 100%.
 * - Early snap: dragging upward from the 50% position snaps to 100% once 55% is reached.
 * - At 100% with scroll at top, downward body drag collapses the drawer (same as handle).
 * - After body drag snaps to 100%, scrolling is blocked until a new touch gesture begins.
 */
@Composable
private fun PullUpDrawer(
    modifier: Modifier = Modifier,
    initialFraction: Float = 0.5f,
    onFractionSnapped: (Float) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val snapFractions = listOf(0.15f, 0.50f, 1.00f)
    // Hold the MutableState reference explicitly so the NestedScrollConnection can close over it
    val currentFractionState = remember { mutableStateOf(initialFraction) }
    var currentFraction by currentFractionState
    val animatedFraction = remember { Animatable(initialFraction) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val scrollState = rememberScrollState()

    // When a body drag snaps the drawer to 100%, block content scrolling until a new
    // touch gesture starts. This prevents the snap-motion from immediately scrolling content.
    val blockScrollUntilNewTouch = remember { mutableStateOf(false) }

    // Track whether the current gesture started as a downward collapse from 100%.
    // Needed because the fraction drops below 1.0 after the first event, but we must
    // keep collapsing for the remainder of the gesture.
    val isCollapsingFromFull = remember { mutableStateOf(false) }

    // Snap target: early snap from 50% to 100% at 55%; normal midpoints otherwise
    fun computeSnapTarget(fraction: Float): Float = when {
        fraction >= 0.55f -> 1.00f
        fraction >= 0.325f -> 0.50f
        else -> 0.15f
    }

    val snapSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxHeightPx = with(density) { maxHeight.toPx() }

        // NestedScrollConnection: intercepts upward scrolls when below 100% (body = drag handle)
        // and downward scrolls when at 100% with scroll at top (body = collapse handle).
        val drawerNestedScrollConnection = remember(maxHeightPx) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val fraction = currentFractionState.value

                    // Finger moving up → expand drawer when below 100%
                    if (fraction < 1.0f && available.y < 0f) {
                        val delta = -available.y / maxHeightPx
                        val newFraction = (fraction + delta).coerceIn(
                            snapFractions.first(), snapFractions.last()
                        )
                        currentFractionState.value = newFraction
                        scope.launch { animatedFraction.snapTo(newFraction) }
                        // If we just reached 100%, block content scroll for this gesture
                        if (newFraction >= 1.0f) {
                            blockScrollUntilNewTouch.value = true
                        }
                        return available // consume the entire scroll event
                    }

                    // At 100%: start collapsing when scroll is at top and finger moves down
                    if (fraction >= 1.0f && available.y > 0f && scrollState.value == 0) {
                        isCollapsingFromFull.value = true
                        val delta = -available.y / maxHeightPx
                        val newFraction = (fraction + delta).coerceIn(
                            snapFractions.first(), snapFractions.last()
                        )
                        currentFractionState.value = newFraction
                        scope.launch { animatedFraction.snapTo(newFraction) }
                        return available
                    }

                    // Continue collapsing even though fraction already dropped below 1.0
                    if (fraction < 1.0f && available.y > 0f && isCollapsingFromFull.value) {
                        val delta = -available.y / maxHeightPx
                        val newFraction = (fraction + delta).coerceIn(
                            snapFractions.first(), snapFractions.last()
                        )
                        currentFractionState.value = newFraction
                        scope.launch { animatedFraction.snapTo(newFraction) }
                        return available
                    }

                    // Finger moving down at 50% (or any position between min and 100%):
                    // the whole visible body acts as a drag handle — collapse the drawer.
                    if (fraction < 1.0f && fraction > snapFractions.first() &&
                        available.y > 0f && !isCollapsingFromFull.value) {
                        val delta = -available.y / maxHeightPx
                        val newFraction = (fraction + delta).coerceIn(
                            snapFractions.first(), snapFractions.last()
                        )
                        currentFractionState.value = newFraction
                        scope.launch { animatedFraction.snapTo(newFraction) }
                        return available
                    }

                    // Block upward content scroll until the user starts a fresh gesture
                    if (fraction >= 1.0f && available.y < 0f && blockScrollUntilNewTouch.value) {
                        return available
                    }

                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    val fraction = currentFractionState.value

                    // Downward fling during an active collapse-from-full gesture:
                    // snap to next lower position (below the current fraction).
                    if (isCollapsingFromFull.value && available.y > 0f) {
                        isCollapsingFromFull.value = false
                        val target = if (fraction > 0.50f) 0.50f else 0.15f
                        currentFractionState.value = target
                        onFractionSnapped(target)
                        animatedFraction.animateTo(target, animationSpec = snapSpec)
                        return available
                    }

                    // Fling while below 100% (expanding or partially dragged)
                    if (fraction < 1.0f) {
                        isCollapsingFromFull.value = false
                        // Choose snap direction based on fling velocity direction
                        val target = if (available.y > 0f && fraction > snapFractions.first()) {
                            // Downward fling → snap to next lower stop
                            if (fraction > 0.5f) 0.50f else 0.15f
                        } else {
                            computeSnapTarget(fraction)
                        }
                        currentFractionState.value = target
                        onFractionSnapped(target)
                        animatedFraction.animateTo(target, animationSpec = snapSpec)
                        if (target >= 1.0f) {
                            blockScrollUntilNewTouch.value = true
                        }
                        return available // consume fling so scroll doesn't also fling
                    }

                    // At 100%, downward fling with scroll at top → collapse to 50%
                    if (fraction >= 1.0f && available.y > 0f && scrollState.value == 0) {
                        val target = 0.50f
                        currentFractionState.value = target
                        onFractionSnapped(target)
                        animatedFraction.animateTo(target, animationSpec = snapSpec)
                        return available
                    }

                    return Velocity.Zero
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { (maxHeightPx * animatedFraction.value).toDp() })
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Drag handle — 44dp tall for a comfortable touch target
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    // Finger moving up (dragAmount.y < 0) expands the sheet
                                    val delta = -dragAmount.y / maxHeightPx
                                    val newFraction = (currentFraction + delta)
                                        .coerceIn(snapFractions.first(), snapFractions.last())
                                    currentFraction = newFraction
                                    scope.launch { animatedFraction.snapTo(newFraction) }
                                },
                                onDragEnd = {
                                    val target = computeSnapTarget(currentFraction)
                                    currentFraction = target
                                    onFractionSnapped(target)
                                    scope.launch {
                                        animatedFraction.animateTo(target, animationSpec = snapSpec)
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Spacer(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                CircleShape
                            )
                    )
                }

                // Scrollable statistics content.
                // nestedScroll intercepts upward drags when not at 100% so the body acts
                // as a drag handle; verticalScroll only takes over when at 100%.
                // A fresh DOWN event clears the block-scroll-until-new-touch flag.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                blockScrollUntilNewTouch.value = false
                                isCollapsingFromFull.value = false
                            }
                        }
                        .nestedScroll(drawerNestedScrollConnection)
                        .verticalScroll(scrollState),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun RideSummaryGrid(session: CyclingSession, comparison: SessionComparison?) {
    val avgSpeed = if (session.netDurationSec > 0)
        session.distanceKm / session.netDurationSec * 3600 else 0.0

    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("d. MMM yyyy", Locale("de"))
            .withZone(ZoneId.systemDefault())
    }

    val fatEffScore: Int? = session.fatEfficiencyScore

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = dateFormatter.format(session.sessionStart),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                MetricCell(
                    label = "Distance",
                    value = FormatUtils.formatDistance(session.distanceKm),
                    current = session.distanceKm,
                    median = comparison?.medianDistanceKm,
                    higherIsBetter = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Net time",
                    value = FormatUtils.formatDuration(session.netDurationSec),
                    current = session.netDurationSec.toDouble(),
                    median = comparison?.medianNetDurationSec?.toDouble(),
                    higherIsBetter = true  // longer net duration is better
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Avg Power",
                    value = if (session.hasPower && session.averagePower != null)
                        FormatUtils.formatPower(session.averagePower) else "—",
                    current = session.averagePower?.toDouble(),
                    median = comparison?.medianAvgPower?.toDouble(),
                    higherIsBetter = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Calories",
                    value = if (session.fatBurnedGrams != null && session.carbsBurnedGrams != null) {
                        val totalKcal = maxOf(0.0, session.fatBurnedGrams!!) * 9.3 + maxOf(0.0, session.carbsBurnedGrams!!) * 4.1
                        val kcalInt = totalKcal.toLong()
                        // Format with '.' as thousands separator (e.g. 1.234 kcal)
                        val formatted = kcalInt.toString().reversed()
                            .chunked(3).joinToString(".").reversed()
                        "$formatted kcal"
                    } else "—",
                    current = null,
                    median = null,
                    higherIsBetter = false
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Fat efficiency score replaces total duration
                MetricCell(
                    label = "Fat Eff.",
                    value = if (fatEffScore != null) "$fatEffScore" else "—",
                    current = fatEffScore?.toDouble(),
                    median = comparison?.medianFatEfficiency,
                    higherIsBetter = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Avg Speed",
                    value = FormatUtils.formatSpeed(avgSpeed),
                    current = avgSpeed,
                    median = comparison?.medianAvgSpeedKmh,
                    higherIsBetter = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Norm. Power",
                    value = if (session.hasPower && session.normalizedPower != null)
                        FormatUtils.formatPower(session.normalizedPower) else "—",
                    current = session.normalizedPower?.toDouble(),
                    median = comparison?.medianNormalizedPower?.toDouble(),
                    higherIsBetter = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Fat / Carbs",
                    value = if (session.fatBurnedGrams != null && session.carbsBurnedGrams != null)
                        "%.0fg / %.0fg".format(maxOf(0.0, session.fatBurnedGrams!!), maxOf(0.0, session.carbsBurnedGrams!!))
                    else "—",
                    current = null,
                    median = null,
                    higherIsBetter = false
                )
            }
        }
    }
}

@Composable
private fun MetricCell(
    label: String,
    value: String,
    current: Double?,
    median: Double?,
    higherIsBetter: Boolean
) {
    val (arrowIcon, arrowColor) = remember(current, median, higherIsBetter) {
        getArrow(current, median, higherIsBetter)
    }

    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
            if (arrowIcon != null && current != null && median != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = arrowIcon,
                    contentDescription = null,
                    tint = arrowColor,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

private fun getArrow(
    current: Double?,
    median: Double?,
    higherIsBetter: Boolean
): Pair<ImageVector?, Color> {
    if (current == null || median == null || median == 0.0) return Pair(null, Color.Gray)
    val relDiff = (current - median) / median
    return when {
        abs(relDiff) < 0.05 ->
            Pair(Icons.Default.ArrowForward, Color.Gray)
        relDiff > 0 && higherIsBetter -> Pair(Icons.Default.ArrowUpward, Color(0xFF4CAF50))
        relDiff < 0 && higherIsBetter -> Pair(Icons.Default.ArrowDownward, Color(0xFFF44336))
        relDiff > 0 && !higherIsBetter -> Pair(Icons.Default.ArrowUpward, Color(0xFFF44336))
        else -> Pair(Icons.Default.ArrowDownward, Color(0xFF4CAF50))
    }
}
