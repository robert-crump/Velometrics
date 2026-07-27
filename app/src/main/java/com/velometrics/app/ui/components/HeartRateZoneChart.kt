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

private val zoneColors = mapOf(
    "Zone 1" to Color(0xFFEF9A9A),
    "Zone 2" to Color(0xFFE57373),
    "Zone 3" to Color(0xFFEF5350),
    "Zone 4" to Color(0xFFE53935),
    "Zone 5" to Color(0xFFB71C1C)
)

private val zoneOrder = listOf("Zone 1", "Zone 2", "Zone 3", "Zone 4", "Zone 5")
private val shortLabels = mapOf(
    "Zone 1" to "Z1",
    "Zone 2" to "Z2",
    "Zone 3" to "Z3",
    "Zone 4" to "Z4",
    "Zone 5" to "Z5"
)

private const val CHART_HEIGHT_DP = 80

/**
 * @param averagePercentages all-rides average percentage-of-ride-time per zone label, drawn as a
 * thin tick mark on each bar. Empty map draws no ticks (e.g. before the cache has any data).
 */
@Composable
fun HeartRateZoneChart(
    hrZones: Map<String, Int>,
    averagePercentages: Map<String, Float> = emptyMap()
) {
    val totalSeconds = hrZones.values.sum().coerceAtLeast(1)
    val zones = zoneOrder.filter { hrZones.containsKey(it) }
    val percentages = zones.associateWith { (hrZones[it] ?: 0).toFloat() / totalSeconds * 100f }
    val maxPct = (percentages.values + averagePercentages.values)
        .maxOrNull()?.coerceAtLeast(1f) ?: 1f

    val tickColor = MaterialTheme.colorScheme.onSurface
    val tickOutlineColor = MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Heart Rate Zones",
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
                zones.forEach { zoneName ->
                    val pct = percentages[zoneName] ?: 0f
                    val fraction = pct / maxPct
                    val avgFraction = averagePercentages[zoneName]?.let { it / maxPct }
                    val color = zoneColors[zoneName] ?: Color.Gray
                    val label = shortLabels[zoneName] ?: zoneName

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
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (pct > 0) {
                            Text(
                                text = "${pct.toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        } else {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}
