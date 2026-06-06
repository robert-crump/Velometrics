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

@Composable
fun PowerZoneChart(powerZones: Map<String, Int>) {
    val totalSeconds = powerZones.values.sum().coerceAtLeast(1)
    val zones = zoneOrder.filter { powerZones.containsKey(it) }
    val maxCount = zones.mapNotNull { powerZones[it] }.maxOrNull()?.coerceAtLeast(1) ?: 1

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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                zones.forEach { zoneName ->
                    val count = powerZones[zoneName] ?: 0
                    val fraction = count.toFloat() / maxCount
                    val pct = (count.toFloat() / totalSeconds * 100).toInt()
                    val color = zoneColors[zoneName] ?: Color.Gray
                    val label = shortLabels[zoneName] ?: zoneName

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        if (pct > 0) {
                            Text(
                                text = "$pct%",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        } else {
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((fraction * 80).dp.coerceAtLeast(2.dp))
                        ) {
                            drawRect(color = color)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
