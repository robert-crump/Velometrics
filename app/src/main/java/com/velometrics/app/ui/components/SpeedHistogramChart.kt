package com.velometrics.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

private const val CHART_HEIGHT_DP = 80

/**
 * Displays the speed distribution from pre-computed average percentages per bin (0–100 scale).
 * @param allRidesAveragePercentages all-rides-ever average percentage per bin, drawn as a thin
 * tick mark on each bar for comparison against this route's own average. Empty map draws no ticks.
 */
@Composable
fun SpeedHistogramChartAvg(
    percentages: Map<String, Float>,
    allRidesAveragePercentages: Map<String, Float> = emptyMap()
) {
    SpeedHistogramChartContent(percentages, allRidesAveragePercentages)
}

/**
 * Displays a single ride's own speed distribution as percentages per bin (0–100 scale).
 * @param allRidesAveragePercentages all-rides-ever average percentage per bin, drawn as a thin
 * tick mark on each bar for comparison against this ride's own distribution. Empty map draws no
 * ticks.
 */
@Composable
fun SpeedHistogramChart(
    percentages: Map<String, Float>,
    allRidesAveragePercentages: Map<String, Float> = emptyMap()
) {
    SpeedHistogramChartContent(percentages, allRidesAveragePercentages)
}

@Composable
private fun SpeedHistogramChartContent(
    percentages: Map<String, Float>,
    allRidesAveragePercentages: Map<String, Float>
) {
    val bins = CyclingConstants.SPEED_HISTOGRAM_BINS.map { it.first }
    val maxPct = (percentages.values + allRidesAveragePercentages.values)
        .maxOrNull()?.coerceAtLeast(1f) ?: 1f

    val tickColor = MaterialTheme.colorScheme.onSurface
    val tickOutlineColor = MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Speed Distribution",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                bins.forEachIndexed { index, binName ->
                    val pct = percentages[binName] ?: 0f
                    val fraction = pct / maxPct
                    val avgFraction = allRidesAveragePercentages[binName]?.let { it / maxPct }
                    val color = barColors.getOrElse(index) { barColors.last() }
                    val shortLabel = shortLabels.getOrElse(index) { binName }

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(CHART_HEIGHT_DP.dp)
                        ) {
                            val barHeightPx = (size.height * fraction).coerceAtLeast(2.dp.toPx())
                            drawRect(
                                color = color,
                                topLeft = Offset(0f, size.height - barHeightPx),
                                size = Size(size.width, barHeightPx)
                            )
                            if (avgFraction != null) {
                                val tickY = size.height - size.height * avgFraction
                                drawLine(
                                    color = tickOutlineColor,
                                    start = Offset(0f, tickY),
                                    end = Offset(size.width, tickY),
                                    strokeWidth = 5.dp.toPx()
                                )
                                drawLine(
                                    color = tickColor,
                                    start = Offset(0f, tickY),
                                    end = Offset(size.width, tickY),
                                    strokeWidth = 2.dp.toPx()
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${pct.roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
