package com.velometrics.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.velometrics.app.util.CyclingConstants
import kotlin.math.roundToInt

private val barColors = listOf(
    Color(0xFFFFEE58), // 0-5 km/h   – very light yellow (coming to a stop)
    Color(0xFFFFD740), // 5-10 km/h  – yellow (climbing)
    Color(0xFFFFC107), // 10-20 km/h
    Color(0xFFFFA726), // 20-25 km/h
    Color(0xFFFF7043), // 25-30 km/h
    Color(0xFFF44336), // 30-35 km/h
    Color(0xFFE53935), // 35-40 km/h
    Color(0xFFB71C1C)  // >40 km/h
)

private val shortLabels = listOf("0-5", "5-10", "10-20", "20-25", "25-30", "30-35", "35-40", ">40")

/** Displays the speed distribution from pre-computed average percentages per bin (0–100 scale). */
@Composable
fun SpeedHistogramChartAvg(percentages: Map<String, Float>) {
    SpeedHistogramChartContent(percentages)
}

@Composable
private fun SpeedHistogramChartContent(percentages: Map<String, Float>) {
    val bins = CyclingConstants.SPEED_HISTOGRAM_BINS.map { it.first }
    val maxPct = percentages.values.maxOrNull()?.coerceAtLeast(1f) ?: 1f

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
                    val color = barColors.getOrElse(index) { barColors.last() }
                    val shortLabel = shortLabels.getOrElse(index) { binName }

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Text(
                            text = "${pct.roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((fraction * 80).dp.coerceAtLeast(2.dp))
                        ) {
                            drawRect(color = color)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
