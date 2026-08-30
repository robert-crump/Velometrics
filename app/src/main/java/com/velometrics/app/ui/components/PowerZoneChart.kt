package com.velometrics.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val zoneColors = mapOf(
    "0 W" to Color(0xFF757575),
    "Zone 1" to Color(0xFFB0BEC5),
    "Zone 2" to Color(0xFF42A5F5),
    "Zone 3" to Color(0xFF66BB6A),
    "Zone 4" to Color(0xFFFFEE58),
    "Zone 5" to Color(0xFFFFA726),
    "Zone 6" to Color(0xFFEF5350)
)

private val zoneOrder = listOf("0 W", "Zone 1", "Zone 2", "Zone 3", "Zone 4", "Zone 5", "Zone 6")
private val shortLabels = mapOf(
    "0 W" to "0W",
    "Zone 1" to "Z1",
    "Zone 2" to "Z2",
    "Zone 3" to "Z3",
    "Zone 4" to "Z4",
    "Zone 5" to "Z5",
    "Zone 6" to "Z6"
)

/**
 * @param averagePercentages all-rides average percentage-of-ride-time per zone label, drawn as a
 * thin tick mark on each bar. Empty map draws no ticks (e.g. before the cache has any data).
 */
@Composable
fun PowerZoneChart(
    powerZones: Map<String, Int>,
    averagePercentages: Map<String, Float> = emptyMap()
) {
    val totalSeconds = powerZones.values.sum().coerceAtLeast(1)
    val zones = zoneOrder.filter { powerZones.containsKey(it) }
    val percentages = zones.associateWith { (powerZones[it] ?: 0).toFloat() / totalSeconds * 100f }

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
        title = "Power Zones",
        entries = entries,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
