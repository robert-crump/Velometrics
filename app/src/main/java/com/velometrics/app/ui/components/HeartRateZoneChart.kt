package com.velometrics.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

    val entries = zones.map { zoneName ->
        val pct = percentages[zoneName] ?: 0f
        TickedBarEntry(
            label = shortLabels[zoneName] ?: zoneName,
            percentage = pct,
            color = zoneColors[zoneName] ?: Color.Gray,
            percentageLabel = if (pct > 0) "${pct.toInt()}%" else null,
            tickPercentage = averagePercentages[zoneName]
        )
    }

    TickedBarChart(
        title = "Heart Rate Zones",
        entries = entries,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
