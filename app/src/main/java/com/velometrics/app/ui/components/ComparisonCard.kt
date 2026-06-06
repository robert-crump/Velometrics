package com.velometrics.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.service.SessionComparison
import com.velometrics.app.util.FormatUtils

private val betterColor = Color(0xFF4CAF50)
private val worseColor = Color(0xFFF44336)
private val neutralColor = Color.Gray

@Composable
fun ComparisonCard(session: CyclingSession, comparison: SessionComparison) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Comparison (vs. ${comparison.previousSessionCount} recent rides)",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Net Duration (neutral)
            ComparisonRow(
                label = "Net Duration",
                value = FormatUtils.formatDurationComparison(session.netDurationSec, comparison.medianNetDurationSec),
                diffColor = neutralColor
            )

            // Distance (more is better)
            val avgSpeed = if (session.netDurationSec > 0)
                session.distanceKm / session.netDurationSec * 3600 else 0.0
            ComparisonRow(
                label = "Distance",
                value = FormatUtils.formatComparison(session.distanceKm, comparison.medianDistanceKm, "km", true),
                diffColor = getDiffColor(session.distanceKm, comparison.medianDistanceKm?.toDouble(), true)
            )

            // Avg Speed (higher is better)
            ComparisonRow(
                label = "Avg Speed",
                value = FormatUtils.formatComparison(avgSpeed, comparison.medianAvgSpeedKmh, "km/h", true),
                diffColor = getDiffColor(avgSpeed, comparison.medianAvgSpeedKmh, true)
            )

            // Avg Power (higher is better, only if both have power)
            if (session.hasPower && session.averagePower != null && comparison.medianAvgPower != null) {
                ComparisonRow(
                    label = "Avg Power",
                    value = FormatUtils.formatComparison(session.averagePower, comparison.medianAvgPower, "W", true),
                    diffColor = getDiffColor(session.averagePower.toDouble(), comparison.medianAvgPower?.toDouble(), true)
                )
            }

            // Normalized Power (higher is better, same condition)
            if (session.hasPower && session.normalizedPower != null && comparison.medianNormalizedPower != null) {
                ComparisonRow(
                    label = "Normalized Power",
                    value = FormatUtils.formatComparison(session.normalizedPower, comparison.medianNormalizedPower, "W", true),
                    diffColor = getDiffColor(session.normalizedPower.toDouble(), comparison.medianNormalizedPower?.toDouble(), true)
                )
            }

            // Interval Time (more is better)
            ComparisonRow(
                label = "Interval Time",
                value = FormatUtils.formatDurationComparison(session.intervalTotalTimeSec, comparison.medianIntervalTimeSec),
                diffColor = getDiffColor(session.intervalTotalTimeSec.toDouble(), comparison.medianIntervalTimeSec?.toDouble(), true)
            )
        }
    }
}

@Composable
private fun ComparisonRow(label: String, value: String, diffColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = diffColor
        )
    }
}

private fun getDiffColor(current: Double, median: Double?, higherIsBetter: Boolean): Color {
    if (median == null || median == 0.0) return neutralColor
    val diff = current - median
    return when {
        diff > 0 && higherIsBetter -> betterColor
        diff < 0 && higherIsBetter -> worseColor
        diff > 0 && !higherIsBetter -> worseColor
        diff < 0 && !higherIsBetter -> betterColor
        else -> neutralColor
    }
}
