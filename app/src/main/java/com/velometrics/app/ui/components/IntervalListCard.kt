package com.velometrics.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.RepeatedIntervalRef
import com.velometrics.app.util.FormatUtils
import java.time.Duration

@Composable
fun IntervalListCard(
    intervals: List<IntervalSession>,
    onIntervalClick: (IntervalSession) -> Unit,
    repeatedIntervalNames: Map<Long, RepeatedIntervalRef> = emptyMap(),
    onRepeatedIntervalClick: (Long) -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = "Intervals",
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
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Light),
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
                        text = compactDuration(interval.durationSec),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${interval.avgPower} W",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    repeatedIntervalNames[interval.id]?.let { ref ->
                        Text(
                            text = ref.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onRepeatedIntervalClick(ref.repeatedIntervalId) }
                        )
                    }
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
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Light),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** "4m46s" / "46s" -- no spaces, so an interval row stays on one line. */
private fun compactDuration(totalSec: Int): String {
    if (totalSec >= 3600) return FormatUtils.formatDuration(totalSec)
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m > 0) "${m}m${"%02d".format(s)}s" else "${s}s"
}
