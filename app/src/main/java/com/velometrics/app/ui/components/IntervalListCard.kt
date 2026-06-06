package com.velometrics.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.util.FormatUtils
import java.time.Duration

@Composable
fun IntervalListCard(
    intervals: List<IntervalSession>,
    onIntervalClick: (IntervalSession) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Intervals (${intervals.size})",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            intervals.forEachIndexed { index, interval ->
                // Pause between consecutive intervals
                if (index > 0) {
                    val prevEnd = intervals[index - 1].startTimestamp
                        .plusSeconds(intervals[index - 1].durationSec.toLong())
                    val pauseSec = Duration.between(prevEnd, interval.startTimestamp).seconds.toInt()
                    if (pauseSec > 0) {
                        Text(
                            text = "Pause: ${FormatUtils.formatDuration(pauseSec)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp, horizontal = 4.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onIntervalClick(interval) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "#${index + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(28.dp)
                    )
                    Text(
                        text = FormatUtils.formatDuration(interval.durationSec),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (interval.durationNormalizedSec != interval.durationSec) {
                        Text(
                            text = "(norm: ${FormatUtils.formatDuration(interval.durationNormalizedSec)})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "%.2f km".format(interval.distanceM / 1000.0),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${interval.avgPower} W",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                if (index < intervals.size - 1) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))
                }
            }

            // Summary line
            if (intervals.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                val totalSec = intervals.sumOf { it.durationSec }
                val avgPower = intervals.map { it.avgPower }.average().toInt()
                Text(
                    text = "Total: ${FormatUtils.formatDuration(totalSec)} in ${intervals.size} intervals, avg. $avgPower W",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
