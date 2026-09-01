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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private const val CHART_HEIGHT_DP = 80

/**
 * One bar in a [TickedBarChart].
 * @param percentageLabel text shown below the bar (e.g. "42%"), or null to draw a blank spacer
 * instead — callers decide per-entry whether/how a bar's percentage is labeled (e.g.
 * [PowerZoneChart]/[HeartRateZoneChart] pass null at 0%, [SpeedHistogramChart] always labels).
 * @param tickPercentage optional comparison value (0-100 scale), drawn as a thin tick mark on the
 * bar. Null draws no tick.
 */
data class TickedBarEntry(
    val label: String,
    val percentage: Float,
    val color: Color,
    val percentageLabel: String?,
    val tickPercentage: Float? = null
)

/**
 * Shared bar+tick-mark Canvas used by [PowerZoneChart], [HeartRateZoneChart], and
 * [SpeedHistogramChart]: a titled card with one bar per entry, each bar's height proportional to
 * its percentage (relative to the largest percentage/tick across all entries), an optional thin
 * tick mark for a comparison value, a short label, and an optional percentage label.
 */
@Composable
fun TickedBarChart(
    title: String,
    entries: List<TickedBarEntry>,
    modifier: Modifier = Modifier
) {
    val maxPct = (entries.map { it.percentage } + entries.mapNotNull { it.tickPercentage })
        .maxOrNull()?.coerceAtLeast(1f) ?: 1f

    val tickColor = MaterialTheme.colorScheme.onSurface
    val tickOutlineColor = MaterialTheme.colorScheme.surface

    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
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
                entries.forEach { entry ->
                    val fraction = entry.percentage / maxPct
                    val tickFraction = entry.tickPercentage?.let { it / maxPct }

                    // The bar itself is drawn on a Canvas, which carries no semantics of its own —
                    // merge the whole column into one accessible node so TalkBack announces the
                    // bar's meaning (label, value, and comparison tick) as a single phrase.
                    val barDescription = buildString {
                        append(entry.label)
                        append(": ")
                        append(entry.percentageLabel ?: "${entry.percentage.roundToInt()}%")
                        entry.tickPercentage?.let { tick ->
                            append(", average ${tick.roundToInt()}%")
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .semantics(mergeDescendants = true) { contentDescription = barDescription },
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
                                color = entry.color,
                                topLeft = Offset(0f, size.height - barHeightPx),
                                size = Size(size.width, barHeightPx)
                            )
                            if (tickFraction != null) {
                                val tickY = size.height - size.height * tickFraction
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
                            text = entry.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (entry.percentageLabel != null) {
                            Text(
                                text = entry.percentageLabel,
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
