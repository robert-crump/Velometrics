package com.velometrics.app.ui.screens.sessiondetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.maplibre.android.maps.Style
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.domain.model.CardiacDriftBand
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.PowerCurvePoint
import com.velometrics.app.domain.model.energy
import com.velometrics.app.domain.service.SessionComparison
import com.velometrics.app.domain.service.RouteRecap
import com.velometrics.app.domain.service.SessionNarrative
import com.velometrics.app.ui.components.*
import com.velometrics.app.util.FormatUtils
import com.velometrics.app.util.GpsTrackParser
import com.velometrics.app.util.MapOverlayUtils
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SessionDetailScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToRepeatedInterval: (Long) -> Unit = {},
    onNavigateToRepeatedRoute: (Long) -> Unit = {},
    viewModel: SessionDetailViewModel = hiltViewModel()
) {
    val session by viewModel.session.collectAsState()
    val intervals by viewModel.intervals.collectAsState()
    val repeatedIntervalNames by viewModel.repeatedIntervalNames.collectAsState()
    val comparison by viewModel.comparison.collectAsState()
    val narrative by viewModel.narrative.collectAsState()
    val routeRecap by viewModel.routeRecap.collectAsState()
    val powerCurve by viewModel.powerCurve.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val powerZoneAverages by viewModel.powerZoneAverages.collectAsState()
    val hrZoneAverages by viewModel.hrZoneAverages.collectAsState()
    val speedHistogram by viewModel.speedHistogram.collectAsState()
    val speedHistogramAverages by viewModel.speedHistogramAverages.collectAsState()
    val deleteError by viewModel.deleteError.collectAsState()
    val maxHr by viewModel.maxHr.collectAsState()

    var overflowMenuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.navigateBack.collect { onNavigateBack() }
    }

    LaunchedEffect(deleteError) {
        deleteError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearDeleteError()
        }
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "Delete ride?",
            text = "This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                showDeleteConfirm = false
                viewModel.deleteRide()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    RideDetailTheme {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (isLoading) {
            LoadingBox(modifier = Modifier.padding(padding))
            HeaderControls(onNavigateBack, overflowMenuExpanded, { overflowMenuExpanded = it }) { showDeleteConfirm = true }
        } else if (session == null) {
            NotFoundBox(text = "Session not found", modifier = Modifier.padding(padding))
            HeaderControls(onNavigateBack, overflowMenuExpanded, { overflowMenuExpanded = it }) { showDeleteConfirm = true }
        } else {
            val s = session!!
            var drawerFraction by remember { mutableStateOf(0.5f) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding())
            ) {
                // Full-screen map background (interactive)
                SessionDetailMap(
                    gpsTrack = s.gpsTrack,
                    intervals = intervals,
                    drawerFraction = drawerFraction
                )

                // Section membership per #188: which cards each group would render, computed up
                // front so an empty section's header can be hidden entirely rather than shown
                // with nothing to expand into.
                val showPowerZones = s.hasPower && s.powerZoneDistribution != null
                val showPowerPlaceholder = !s.hasPower
                val showPowerCurve = s.hasPower && powerCurve.any { it.watts != null }
                val showSprint = s.hasPower && s.sprintCount > 0 && s.sprintHistogram != null

                val showHrZones = s.hrZoneDistribution != null
                val showCardiacDrift = s.cardiacDriftBuckets != null && s.cardiacDriftPercent != null
                val showHrDistance = s.hrDistanceSeries != null
                val intervalsSectionVisible = s.hasPower && intervals.isNotEmpty()

                val cardiacEfficiency: Double? = if (s.hasPower) {
                    val power = s.averagePower
                    val hr = s.avgHeartRate
                    if (power != null && hr != null && hr != 0) power.toDouble() / hr else null
                } else null

                val powerLines = if (s.hasPower) listOfNotNull(
                    s.averagePower?.let { StatLineData("Average Power", FormatUtils.formatPower(it)) },
                    s.normalizedPower?.let { StatLineData("Normalized Power", FormatUtils.formatPower(it)) },
                    s.maxPower?.let { StatLineData("Max Power", FormatUtils.formatPower(it)) }
                ) else emptyList()
                val heartRateLines = listOfNotNull(
                    s.avgHeartRate?.let { StatLineData("Average Heart Rate", "$it bpm") },
                    s.maxHeartRate?.let { StatLineData("Max Heart Rate", "$it bpm") },
                    cardiacEfficiency?.let { StatLineData("Cardiac Efficiency", FormatUtils.formatCardiacEfficiency(it)) },
                    s.cardiacDriftPercent?.let {
                        StatLineData("Cardiac Drift", "${FormatUtils.formatCardiacDriftPercent(it)} (${CardiacDriftBand.fromPercent(it).name.lowercase()})")
                    },
                    s.energy?.let { StatLineData("Fat / Carbs burned", it.formatFatCarbGrams()) },
                    s.fatEfficiencyScore?.let { StatLineData("Fat Efficiency Factor", "$it", showInfo = true) }
                )
                val heartRateSectionVisible = showHrDistance || showHrZones || showCardiacDrift || heartRateLines.isNotEmpty()

                // Pull-up drawer with all statistics; opens at 50%
                PullUpDrawer(
                    initialFraction = 0.5f,
                    onFractionSnapped = { drawerFraction = it },
                    headerStart = { docked -> BackControl(docked, onNavigateBack) },
                    headerEnd = { docked ->
                        OverflowControl(docked, overflowMenuExpanded, { overflowMenuExpanded = it }) { showDeleteConfirm = true }
                    }
                ) {
                    RideSummaryGrid(
                        session = s, comparison = comparison, narrative = narrative,
                        routeRecap = routeRecap, onRouteRecapClick = onNavigateToRepeatedRoute
                    )

                    SectionCard(title = "Power") {
                        if (showPowerZones) {
                            PowerZoneChart(
                                powerZones = s.powerZoneDistribution!!,
                                averagePercentages = powerZoneAverages
                            )
                        } else if (showPowerPlaceholder) {
                            Text(
                                text = "No power data available",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                            )
                        }
                        if (showPowerCurve) {
                            PowerCurveChart(points = powerCurve)
                        }
                        StatLines(powerLines)
                    }

                    SectionCard(title = "Speed") {
                        SpeedHistogramChart(
                            percentages = speedHistogram,
                            allRidesAveragePercentages = speedHistogramAverages
                        )
                        if (showSprint) {
                            SprintCard(sprintHistogram = s.sprintHistogram!!)
                        }
                        StatLines(
                            listOfNotNull(
                                s.maxSpeedKmh?.let { StatLineData("Max Speed", FormatUtils.formatSpeed(it)) }
                            )
                        )
                        if (intervalsSectionVisible) {
                            IntervalListCard(
                                intervals = intervals,
                                onIntervalClick = {},
                                repeatedIntervalNames = repeatedIntervalNames,
                                onRepeatedIntervalClick = onNavigateToRepeatedInterval
                            )
                            Hrr60Card(intervals = intervals)
                        }
                    }

                    if (heartRateSectionVisible) {
                        SectionCard(title = "Heart Rate and Metabolism") {
                            if (showHrDistance) {
                                HrDistanceChart(points = s.hrDistanceSeries!!, maxHr = maxHr)
                            }
                            if (showHrZones) {
                                HeartRateZoneChart(
                                    hrZones = s.hrZoneDistribution!!,
                                    averagePercentages = hrZoneAverages
                                )
                            }
                            if (showCardiacDrift) {
                                CardiacDriftChart(buckets = s.cardiacDriftBuckets!!)
                            }
                            StatLines(heartRateLines)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

    }
    }
}

/** Floating back/overflow controls for the loading / not-found states (no drawer to dock into). */
@Composable
private fun HeaderControls(
    onBack: () -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Box(modifier = Modifier.align(Alignment.TopStart)) { BackControl(false, onBack) }
        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            OverflowControl(false, menuExpanded, onMenuExpandedChange, onDelete)
        }
    }
}

/** Circle-on-map button while floating; plain icon button once docked into the drawer's header. */
@Composable
private fun HeaderButton(docked: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    if (docked) IconButton(onClick = onClick) { content() }
    else FloatingCircleButton(onClick = onClick, content = content)
}

@Composable
private fun BackControl(docked: Boolean, onBack: () -> Unit) {
    HeaderButton(docked, onBack) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun OverflowControl(
    docked: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Box {
        HeaderButton(docked, { onExpandedChange(true) }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "More options",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            DropdownMenuItem(
                text = { Text("Delete ride") },
                onClick = {
                    onExpandedChange(false)
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun FloatingCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .border(1.dp, Color.White, CircleShape)
    ) {
        content()
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

    LaunchedEffect(mapStyleRef.value, intervals) {
        val style = mapStyleRef.value ?: return@LaunchedEffect
        MapIntervalRenderer.removeIntervalOverlay(style)
        if (intervals.isNotEmpty()) {
            MapIntervalRenderer.renderUngroupedIntervals(style, intervals)
        }
    }

    TrackMapWithDrawer(
        points = points,
        drawerFraction = drawerFraction,
        trackId = "session-detail",
        onMapReady = { _, style -> mapStyleRef.value = style },
        overlayContent = {
            // Interval duration legend overlay
            if (intervals.isNotEmpty()) {
                IntervalMapLegend(
                    intervals = intervals,
                    modifier = Modifier.align(Alignment.TopStart)
                )
            }
        }
    )
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
        Triple("< 3:30 min", 165, intervals.any { it.durationSec < 210 }),
        Triple("3:30–5 min",  255, intervals.any { it.durationSec in 210..299 }),
        Triple("5–6:30 min",  345, intervals.any { it.durationSec in 300..389 }),
        Triple("> 6:30 min",  435, intervals.any { it.durationSec >= 390 })
    ).filter { it.third }

    if (bands.isEmpty()) return

    Surface(
        modifier = modifier.padding(8.dp),
        shape = MaterialTheme.shapes.small,
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


/** Temporarily hides the comparison triangles and the "vs. last 5" / "vs. all" toggle. */
private const val SHOW_COMPARISON_TRIANGLES = false

/** Which comparison pool the Session Detail triangles are currently measured against. */
private enum class ComparisonMode(val label: String) {
    LAST_5("vs. last 5"),
    ALL_PREVIOUS("vs. all")
}

@Composable
private fun ComparisonModeToggle(mode: ComparisonMode, onModeChange: (ComparisonMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ComparisonMode.entries.forEach { option ->
            FilterChip(
                selected = mode == option,
                onClick = { onModeChange(option) },
                label = { Text(option.label) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                )
            )
        }
    }
}

private val CardEdgePadding = 2.dp

/** Vertical gap between the six top metrics and the block above / the card below. */
private val SUMMARY_GAP = 24.dp

/** Dark-theme card fill: only a touch lighter than the pure-black drawer behind it. */
private val DarkCardColor = Color(0xFF1C1C1C)

/**
 * Ride Detail's dark look: pure-black drawer, all text white, cards a very dark gray. Light theme
 * is left untouched.
 */
@Composable
private fun RideDetailTheme(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    MaterialTheme(
        colorScheme = if (dark) {
            scheme.copy(
                background = Color.Black,
                surface = Color.Black,
                onBackground = Color.White,
                onSurface = Color.White,
                onSurfaceVariant = Color.White
            )
        } else scheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}

/**
 * A single Strava-style card per section: headline top-left, then the content separated by small
 * vertical spacing rather than dividers. Not collapsible.
 */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CardEdgePadding, vertical = 4.dp),
        colors = if (dark) {
            CardDefaults.cardColors(containerColor = DarkCardColor, contentColor = Color.White)
        } else CardDefaults.cardColors()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

/** ~21% larger than bodyMedium (14sp), for both label and value of a stat line. */
private val StatLineTextStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontSize = 17.sp)

private data class StatLineData(val label: String, val value: String, val showInfo: Boolean = false)

private const val FAT_EFFICIENCY_EXPLANATION =
    "The Fat Efficiency Factor (0-100) shows how much of your ride was spent near your body's " +
        "peak fat-burning intensity (FatMax, roughly 150-220 W).\n\n" +
        "For every second of pedaling, fat burn is compared to that peak and averaged over the " +
        "ride. Riding at FatMax scores 100; high-power efforts count for less because fuel use " +
        "shifts to carbohydrates, and very low power burns little fat in absolute terms.\n\n" +
        "Higher = a steadier, fat-burning-friendly endurance ride. Lower = a hard or very easy ride."

/** Strava-style stat rows: muted label on the left, bold value on the right. */
@Composable
private fun StatLines(lines: List<StatLineData>) {
    if (lines.isEmpty()) return
    var showInfo by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        lines.forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = line.label,
                    style = StatLineTextStyle.copy(fontWeight = FontWeight.Normal),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (line.showInfo) {
                    IconButton(onClick = { showInfo = true }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = "About ${line.label}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = line.value,
                    style = StatLineTextStyle.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("Fat Efficiency Factor") },
            text = { Text(FAT_EFFICIENCY_EXPLANATION) },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Got it") } }
        )
    }
}

/** A "vs. [TAG]" / "vs. [Repeated Route]" headline over one stat line per metric, in metric-value size. */
@Composable
private fun RecapLines(headline: String, lines: List<String>, topPadding: Dp = 8.dp) {
    Text(
        text = headline,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = topPadding)
    )
    lines.forEach { line ->
        Text(
            text = line,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun RideSummaryGrid(
    session: CyclingSession,
    comparison: SessionComparison?,
    narrative: SessionNarrative?,
    routeRecap: RouteRecap?,
    onRouteRecapClick: (Long) -> Unit
) {
    var comparisonMode by remember { mutableStateOf(ComparisonMode.LAST_5) }
    val avgSpeed = if (session.netDurationSec > 0)
        session.distanceKm / session.netDurationSec * 3600 else 0.0

    val dateFormatter = remember {
        // Device default locale, matching FormatUtils.formatDate elsewhere — was hardcoded to
        // Locale("de") before, which formatted this header in German regardless of the device's
        // actual locale.
        DateTimeFormatter.ofPattern("d. MMM yyyy")
            .withZone(ZoneId.systemDefault())
    }

    val totalKcal: Double? = session.energy?.totalKcal?.toDouble()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Bottom = SUMMARY_GAP minus the card's own 4 dp top margin, so the six metrics sit
            // SUMMARY_GAP from the recap block above and from the first card below.
            .padding(start = CardEdgePadding + 8.dp, end = CardEdgePadding + 8.dp, top = 8.dp, bottom = SUMMARY_GAP - 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    text = dateFormatter.format(session.sessionStart),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                )
            }
            if (SHOW_COMPARISON_TRIANGLES) {
                ComparisonModeToggle(mode = comparisonMode, onModeChange = { comparisonMode = it })
            }
        }
        if (narrative != null) {
            RecapLines(headline = narrative.headline, lines = narrative.lines, topPadding = 24.dp)
        }
        if (routeRecap != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRouteRecapClick(routeRecap.routeId) }
            ) {
                RecapLines(
                    headline = routeRecap.headline,
                    lines = routeRecap.lines,
                    topPadding = if (narrative != null) 8.dp else 24.dp
                )
            }
        }
        Spacer(modifier = Modifier.height(SUMMARY_GAP))

        fun <T> pooled(last5: T, allPrevious: T): T? = when {
            !SHOW_COMPARISON_TRIANGLES -> null
            comparisonMode == ComparisonMode.LAST_5 -> last5
            else -> allPrevious
        }

        // Strava-simple headline set (#188) — 6 fields, 2 columns x 3 rows. The other 5 fields
        // that used to live here moved into their matching section's card header; Elev. gain /
        // 100km was dropped entirely as redundant with raw Elevation gain.
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                MetricCell(
                    label = "Distance",
                    value = FormatUtils.formatDistance(session.distanceKm),
                    current = session.distanceKm,
                    reference = pooled(comparison?.medianDistanceKmLast5, comparison?.medianDistanceKmAllPrevious),
                    higherIsBetter = true,
                    prominent = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Net time",
                    value = FormatUtils.formatDuration(session.netDurationSec),
                    current = session.netDurationSec.toDouble(),
                    reference = pooled(
                        comparison?.medianNetDurationSecLast5?.toDouble(),
                        comparison?.medianNetDurationSecAllPrevious?.toDouble()
                    ),
                    higherIsBetter = true,  // longer net duration is better
                    prominent = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Avg Speed",
                    value = FormatUtils.formatSpeed(avgSpeed),
                    current = avgSpeed,
                    reference = pooled(
                        comparison?.medianAvgSpeedKmhLast5,
                        comparison?.medianAvgSpeedKmhAllPrevious
                    ),
                    higherIsBetter = true,
                    prominent = true
                )
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                MetricCell(
                    label = "Elevation gain",
                    value = session.elevationGainM?.let { FormatUtils.formatElevationGain(it) } ?: "—",
                    current = session.elevationGainM,
                    reference = pooled(
                        comparison?.medianElevationGainMLast5,
                        comparison?.medianElevationGainMAllPrevious
                    ),
                    higherIsBetter = true,  // more climbing is an achievement, not a cost
                    prominent = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Avg Power",
                    value = if (session.hasPower && session.averagePower != null)
                        FormatUtils.formatPower(session.averagePower) else "—",
                    current = session.averagePower?.toDouble(),
                    reference = pooled(
                        comparison?.medianAvgPowerLast5?.toDouble(),
                        comparison?.medianAvgPowerAllPrevious?.toDouble()
                    ),
                    higherIsBetter = true,
                    prominent = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Calories",
                    value = session.energy?.formatTotalKcal() ?: "—",
                    current = totalKcal,
                    reference = pooled(comparison?.medianTotalKcalLast5, comparison?.medianTotalKcalAllPrevious),
                    higherIsBetter = true,  // more calories burned = better workout
                    prominent = true
                )
            }
        }
    }
}

