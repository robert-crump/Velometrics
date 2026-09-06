package com.velometrics.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.velometrics.app.domain.model.IntervalSession

/**
 * "HRR60" card (#182): heart-rate recovery in the 60s after each interval ends, alongside the
 * average power held in that same window. Split out of `IntervalListCard`'s former inline HRR60
 * text so both recovery-window metrics (HR drop + post-interval power) sit together with room to
 * explain what they mean, via the info overlay below.
 *
 * Hidden entirely (no empty-state placeholder) when no interval in the session has a non-null
 * `hrr60` -- same "only render when there's something to show" precedent as the other optional
 * cards on SessionDetailScreen (PowerZoneChart, SprintCard, etc.).
 */
@Composable
fun Hrr60Card(intervals: List<IntervalSession>) {
    if (intervals.none { it.hrr60 != null }) return

    var showInfo by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HRR60",
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = { showInfo = true }) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = "About HRR60",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            intervals.forEachIndexed { index, interval ->
                interval.hrr60?.let { hrr60 ->
                    val truncated = (interval.restBeforeNextIntervalSec ?: Int.MAX_VALUE) < 60
                    val color = if (truncated) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
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
                            text = if (truncated) "-$hrr60 bpm*" else "-$hrr60 bpm",
                            style = MaterialTheme.typography.bodyMedium,
                            color = color
                        )
                        val powerAfter = interval.avgPower60sAfter
                        Text(
                            text = if (powerAfter != null) {
                                if (truncated) "$powerAfter W*" else "$powerAfter W"
                            } else "—",
                            style = MaterialTheme.typography.bodyMedium,
                            color = color
                        )
                    }
                    if (index < intervals.size - 1) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("About HRR60") },
            text = {
                Text(
                    "HRR60 is the drop in heart rate during the 60 seconds after an interval ends " +
                        "-- a bigger drop indicates better parasympathetic recovery.\n\n" +
                        "The power figure alongside it is the average power held over that same " +
                        "60-second window: a near-zero value suggests coasting, while an elevated " +
                        "value suggests continued (or soft-pedaling) effort, which is useful context " +
                        "when interpreting the HRR60 number next to it.\n\n" +
                        "An asterisk (*) marks a reading where the next interval started before the " +
                        "full 60-second window finished, so the figure may be understated."
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text("Got it")
                }
            }
        )
    }
}
