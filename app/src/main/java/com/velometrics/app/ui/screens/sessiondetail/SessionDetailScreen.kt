package com.velometrics.app.ui.screens.sessiondetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.PowerCurvePoint
import com.velometrics.app.domain.model.energy
import com.velometrics.app.domain.service.SessionComparison
import com.velometrics.app.ui.components.*
import com.velometrics.app.util.FormatUtils
import com.velometrics.app.util.GpsTrackParser
import com.velometrics.app.util.MapOverlayUtils
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToRepeatedInterval: (Long) -> Unit = {},
    viewModel: SessionDetailViewModel = hiltViewModel()
) {
    val session by viewModel.session.collectAsState()
    val intervals by viewModel.intervals.collectAsState()
    val repeatedIntervalNames by viewModel.repeatedIntervalNames.collectAsState()
    val comparison by viewModel.comparison.collectAsState()
    val tagNarrative by viewModel.tagNarrative.collectAsState()
    val powerCurve by viewModel.powerCurve.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val powerZoneAverages by viewModel.powerZoneAverages.collectAsState()
    val hrZoneAverages by viewModel.hrZoneAverages.collectAsState()
    val speedHistogram by viewModel.speedHistogram.collectAsState()
    val speedHistogramAverages by viewModel.speedHistogramAverages.collectAsState()

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
            LoadingBox(modifier = Modifier.padding(padding))
        } else if (session == null) {
            NotFoundBox(text = "Session not found", modifier = Modifier.padding(padding))
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

                // Section membership per #188: which cards each group would render, computed up
                // front so an empty section's header can be hidden entirely rather than shown
                // with nothing to expand into.
                val showPowerZones = s.hasPower && s.powerZoneDistribution != null
                val showPowerPlaceholder = !s.hasPower
                val showPowerCurve = s.hasPower && powerCurve.any { it.watts != null }
                val showSprint = s.hasPower && s.sprintCount > 0 && s.sprintHistogram != null
                val powerSectionVisible = showPowerZones || showPowerPlaceholder || showPowerCurve || showSprint

                val showHrZones = s.hrZoneDistribution != null
                val showCardiacDrift = s.cardiacDriftBuckets != null && s.cardiacDriftPercent != null
                val showFatEfficiency = s.hasPower && s.fatEfficiencyHistogram != null
                val heartRateSectionVisible = showHrZones || showCardiacDrift || showFatEfficiency

                val intervalsSectionVisible = s.hasPower && intervals.isNotEmpty()

                // Fields relocated out of the summary grid (#188) — surfaced via a ChapterStatsCard
                // as the first card of their new section, with no trend triangle (plain values only).
                val normalizedPowerWatts: Int? = if (s.hasPower) s.normalizedPower else null
                val cardiacEfficiency: Double? = if (s.hasPower) {
                    val power = s.averagePower
                    val hr = s.avgHeartRate
                    if (power != null && hr != null && hr != 0) power.toDouble() / hr else null
                } else null
                val fatEffScore: Int? = s.fatEfficiencyScore
                val fatCarbText: String? = s.energy?.formatFatCarbGrams()

                var powerExpanded by remember { mutableStateOf(false) }
                var heartRateExpanded by remember { mutableStateOf(false) }
                var speedExpanded by remember { mutableStateOf(false) }
                var intervalsExpanded by remember { mutableStateOf(false) }

                // Pull-up drawer with all statistics; opens at 50%
                PullUpDrawer(
                    initialFraction = 0.5f,
                    onFractionSnapped = { drawerFraction = it }
                ) {
                    RideSummaryGrid(session = s, comparison = comparison, tagNarrative = tagNarrative)

                    if (powerSectionVisible) {
                        CollapsibleSection(
                            title = "Power",
                            expanded = powerExpanded,
                            onToggle = { powerExpanded = !powerExpanded }
                        ) {
                            ChapterStatsCard(
                                metrics = listOfNotNull(
                                    normalizedPowerWatts?.let { "Norm. Power" to FormatUtils.formatPower(it) }
                                )
                            )

                            if (showPowerZones) {
                                PowerZoneChart(
                                    powerZones = s.powerZoneDistribution!!,
                                    averagePercentages = powerZoneAverages
                                )
                            } else if (showPowerPlaceholder) {
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

                            if (showPowerCurve) {
                                SessionPowerCurveCard(points = powerCurve)
                            }

                            if (showSprint) {
                                SprintCard(sprintHistogram = s.sprintHistogram!!)
                            }
                        }
                    }

                    CollapsibleSection(
                        title = "Speed",
                        expanded = speedExpanded,
                        onToggle = { speedExpanded = !speedExpanded }
                    ) {
                        SpeedHistogramChart(
                            percentages = speedHistogram,
                            allRidesAveragePercentages = speedHistogramAverages
                        )
                    }

                    if (heartRateSectionVisible) {
                        CollapsibleSection(
                            title = "Heart Rate",
                            expanded = heartRateExpanded,
                            onToggle = { heartRateExpanded = !heartRateExpanded }
                        ) {
                            ChapterStatsCard(
                                metrics = listOfNotNull(
                                    cardiacEfficiency?.let { "Cardiac Eff." to FormatUtils.formatCardiacEfficiency(it) },
                                    fatEffScore?.let { "Fat Eff." to "$it" },
                                    fatCarbText?.let { "Fat / Carbs" to it }
                                )
                            )

                            if (showHrZones) {
                                HeartRateZoneChart(
                                    hrZones = s.hrZoneDistribution!!,
                                    averagePercentages = hrZoneAverages
                                )
                            }

                            if (showCardiacDrift) {
                                CardiacDriftChart(
                                    buckets = s.cardiacDriftBuckets!!,
                                    decouplingPercent = s.cardiacDriftPercent!!
                                )
                            }

                            if (showFatEfficiency) {
                                FatEfficiencyHistogram(histogram = s.fatEfficiencyHistogram!!)
                            }
                        }
                    }

                    if (intervalsSectionVisible) {
                        CollapsibleSection(
                            title = "Intervals & Recovery",
                            expanded = intervalsExpanded,
                            onToggle = { intervalsExpanded = !intervalsExpanded }
                        ) {
                            IntervalListCard(
                                intervals = intervals,
                                onIntervalClick = {},
                                repeatedIntervalNames = repeatedIntervalNames,
                                onRepeatedIntervalClick = onNavigateToRepeatedInterval
                            )
                            Hrr60Card(intervals = intervals)
                        }
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

/** This ride's own best-effort power curve (#173), placed right below Power Zones. */
@Composable
private fun SessionPowerCurveCard(points: List<PowerCurvePoint>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Power curve",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            PowerCurveChart(points = points)
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

/**
 * Rule-based classification label (#169, thresholds/category set replaced in #170), e.g.
 * "Zone 2" or "Intervals" — tapping it expands the tag-scoped comparison narrative (#171). The
 * chevron rotates 90° when expanded to read as a "collapse" affordance, matching the label's own
 * clickable row rather than a separate expand icon button.
 */
@Composable
private fun RideTagLabel(tag: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(16.dp)
                .rotate(if (expanded) 90f else 0f)
        )
    }
}

/**
 * A collapsed-by-default group of cards (#188), toggled by tapping its header row — chevron
 * rotates 90° when expanded, matching [RideTagLabel]'s affordance. Sections are independent: more
 * than one can be open at once, so e.g. Power and Heart Rate can be compared side by side in the
 * scroll without the other two sections' cards in between.
 */
@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 90f else 0f)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(content = content)
        }
    }
}

/**
 * The first card in a chapter (#188 follow-up): a two-column [MetricCell] grid, analogous to the
 * always-shown grid at the top of the drawer, holding the fields that used to live in the summary
 * grid but are scoped to this chapter's theme (e.g. Norm. Power for Power, Cardiac Eff./Fat
 * Eff./Fat-Carbs for Heart Rate). No headline — the parent [CollapsibleSection]'s own title already
 * names the chapter. Plain values only — no trend triangle, matching the rest of the relocated
 * fields. Renders nothing when [metrics] is empty (e.g. a powerless ride).
 */
@Composable
private fun ChapterStatsCard(metrics: List<Pair<String, String>>) {
    if (metrics.isEmpty()) return

    val leftColumn = metrics.subList(0, (metrics.size + 1) / 2)
    val rightColumn = metrics.subList((metrics.size + 1) / 2, metrics.size)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    leftColumn.forEachIndexed { index, (label, value) ->
                        if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                        MetricCell(label = label, value = value)
                    }
                }
                if (rightColumn.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        rightColumn.forEachIndexed { index, (label, value) ->
                            if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                            MetricCell(label = label, value = value)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RideSummaryGrid(session: CyclingSession, comparison: SessionComparison?, tagNarrative: String?) {
    var comparisonMode by remember { mutableStateOf(ComparisonMode.LAST_5) }
    var tagExpanded by remember { mutableStateOf(false) }
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
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    text = dateFormatter.format(session.sessionStart),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                )
                session.tag?.let { tag ->
                    RideTagLabel(tag, expanded = tagExpanded, onClick = { tagExpanded = !tagExpanded })
                }
            }
            ComparisonModeToggle(mode = comparisonMode, onModeChange = { comparisonMode = it })
        }
        if (tagExpanded && tagNarrative != null) {
            Text(
                text = tagNarrative,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        fun <T> pooled(last5: T, allPrevious: T): T =
            if (comparisonMode == ComparisonMode.LAST_5) last5 else allPrevious

        // Strava-simple headline set (#188) — 6 fields, 2 columns x 3 rows. The other 5 fields
        // that used to live here moved into their matching section's card header; Elev. gain /
        // 100km was dropped entirely as redundant with raw Elevation gain.
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                MetricCell(
                    label = "Distance",
                    value = FormatUtils.formatDistance(session.distanceKm),
                    current = session.distanceKm,
                    reference = pooled(comparison?.medianDistanceKmLast5, comparison?.medianDistanceKmAllPrevious),
                    higherIsBetter = true
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
                    higherIsBetter = true  // longer net duration is better
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
                    higherIsBetter = true
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                MetricCell(
                    label = "Elevation gain",
                    value = session.elevationGainM?.let { FormatUtils.formatElevationGain(it) } ?: "—",
                    current = session.elevationGainM,
                    reference = pooled(
                        comparison?.medianElevationGainMLast5,
                        comparison?.medianElevationGainMAllPrevious
                    ),
                    higherIsBetter = true  // more climbing is an achievement, not a cost
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
                    higherIsBetter = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                MetricCell(
                    label = "Calories",
                    value = session.energy?.formatTotalKcal() ?: "—",
                    current = totalKcal,
                    reference = pooled(comparison?.medianTotalKcalLast5, comparison?.medianTotalKcalAllPrevious),
                    higherIsBetter = true  // more calories burned = better workout
                )
            }
        }
    }
}

