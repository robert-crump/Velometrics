package com.velometrics.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.velometrics.app.util.CyclingConstants
import kotlin.math.roundToInt

private val barColors = listOf(
    Color(0xFFFFEE58), // 0-10 km/h – light yellow (coming to a stop)
    Color(0xFFFFC107), // 10-20 km/h
    Color(0xFFFFA726), // 20-30 km/h
    Color(0xFFF44336), // 30-40 km/h
    Color(0xFFB71C1C)  // >40 km/h
)

private val shortLabels = listOf("0-10", "10-20", "20-30", "30-40", ">40")

/**
 * Displays the speed distribution from pre-computed average percentages per bin (0–100 scale).
 * Used on RepeatedRouteDetailScreen, whose surrounding Column already applies horizontal padding,
 * so the Card itself stays edge-to-edge within it.
 * @param allRidesAveragePercentages all-rides-ever average percentage per bin, drawn as a thin
 * tick mark on each bar for comparison against this route's own average. Empty map draws no ticks.
 */
@Composable
fun SpeedHistogramChartAvg(
    percentages: Map<String, Float>,
    allRidesAveragePercentages: Map<String, Float> = emptyMap()
) {
    SpeedHistogramChartContent(percentages, allRidesAveragePercentages, Modifier.fillMaxWidth())
}

/**
 * Displays a single ride's own speed distribution as percentages per bin (0–100 scale).
 * Used on SessionDetailScreen, whose cards each own their horizontal/vertical margin (unlike
 * RepeatedRouteDetailScreen's already-padded container), matching PowerZoneChart/HeartRateZoneChart.
 * @param allRidesAveragePercentages all-rides-ever average percentage per bin, drawn as a thin
 * tick mark on each bar for comparison against this ride's own distribution. Empty map draws no
 * ticks.
 */
@Composable
fun SpeedHistogramChart(
    percentages: Map<String, Float>,
    allRidesAveragePercentages: Map<String, Float> = emptyMap()
) {
    SpeedHistogramChartContent(
        percentages,
        allRidesAveragePercentages,
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SpeedHistogramChartContent(
    percentages: Map<String, Float>,
    allRidesAveragePercentages: Map<String, Float>,
    cardModifier: Modifier
) {
    val bins = CyclingConstants.SPEED_HISTOGRAM_BINS.map { it.first }

    val entries = bins.mapIndexed { index, binName ->
        val pct = percentages[binName] ?: 0f
        TickedBarEntry(
            label = shortLabels.getOrElse(index) { binName },
            percentage = pct,
            color = barColors.getOrElse(index) { barColors.last() },
            percentageLabel = "${pct.roundToInt()}%",
            tickPercentage = allRidesAveragePercentages[binName]
        )
    }

    TickedBarChart(
        title = "Speed Distribution",
        entries = entries,
        modifier = cardModifier
    )
}
