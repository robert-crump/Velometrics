package com.cyclegraph.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cyclegraph.app.util.FormatUtils

// Keys match what is stored in the DB — do not rename.
private val binKeys = listOf("Low (0-50%)", "Medium (50-80%)", "High (>80%)")

// Single-line display labels shown in the UI
private val binDisplayLabels = listOf(
    "Low\n(0-50%)",
    "Medium\n(50-80%)",
    "High\n(80-100%)"
)

private val binColors = listOf(
    Color(0xFF9E9E9E),  // gray   – low
    Color(0xFFFFA726),  // orange – medium
    Color(0xFF4CAF50)   // green  – high
)

@Composable
fun FatEfficiencyHistogram(histogram: Map<String, Int>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Fat Burn Rate",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "vs. FatMax",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                binKeys.forEachIndexed { index, key ->
                    val seconds = histogram[key] ?: 0
                    val duration = FormatUtils.formatDurationHhMm(seconds)
                    val displayLabel = binDisplayLabels[index]
                    val color = binColors.getOrElse(index) { Color.Gray }

                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = displayLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = duration,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                    }
                }
            }
        }
    }
}
