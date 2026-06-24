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

@Composable
fun HeartRateZoneChart(hrZones: Map<String, Int>) {
    val totalSeconds = hrZones.values.sum().coerceAtLeast(1)
    val zones = zoneOrder.filter { hrZones.containsKey(it) }
    val maxCount = zones.mapNotNull { hrZones[it] }.maxOrNull()?.coerceAtLeast(1) ?: 1

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
                    val count = hrZones[zoneName] ?: 0
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
