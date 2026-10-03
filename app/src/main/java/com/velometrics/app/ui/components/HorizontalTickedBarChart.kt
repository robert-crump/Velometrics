package com.velometrics.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val BAR_THICKNESS = 14.dp
private val ROW_GAP = 4.dp
private val LABEL_WIDTH = 24.dp

/**
 * One row in a [HorizontalTickedBarChart].
 * @param tickPercentage optional comparison value (0-100 scale), drawn as a thin vertical tick
 * mark across the bar. Null draws no tick.
 */
data class HorizontalBarEntry(
    val label: String,
    val percentage: Float,
    val color: Color,
    val tickPercentage: Float? = null
)

/**
 * Horizontal bar+tick-mark chart used by [PowerZoneChart] and [HeartRateZoneChart]: one row per
 * entry, top to bottom in the given order, each a fixed-width short label followed by a bar whose
 * length is proportional to its percentage (relative to the largest percentage/tick across all
 * entries) and an optional thin tick mark for a comparison value. Rows have a fixed thickness, so
 * the chart's height grows with the entry count. No percentage text is drawn; values are exposed
 * to accessibility services only.
 */
@Composable
fun HorizontalTickedBarChart(
    entries: List<HorizontalBarEntry>,
    modifier: Modifier = Modifier
) {
    val maxPct = (entries.map { it.percentage } + entries.mapNotNull { it.tickPercentage })
        .maxOrNull()?.coerceAtLeast(1f) ?: 1f

    val tickColor = MaterialTheme.colorScheme.onSurface
    val tickOutlineColor = MaterialTheme.colorScheme.surface

    Column(
        modifier = modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP)
    ) {
        entries.forEach { entry ->
            val fraction = entry.percentage / maxPct
            val tickFraction = entry.tickPercentage?.let { it / maxPct }

            // The bar is drawn on a Canvas, which carries no semantics of its own — merge the row
            // into one accessible node so TalkBack announces label, value, and comparison tick.
            val rowDescription = buildString {
                append(entry.label)
                append(": ")
                append("${entry.percentage.roundToInt()}%")
                entry.tickPercentage?.let { tick ->
                    append(", average ${tick.roundToInt()}%")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { contentDescription = rowDescription },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Light),
                    maxLines = 1,
                    modifier = Modifier.width(LABEL_WIDTH)
                )
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(BAR_THICKNESS)
                ) {
                    val barWidthPx = (size.width * fraction).coerceAtLeast(2.dp.toPx())
                    drawRect(
                        color = entry.color,
                        topLeft = Offset.Zero,
                        size = Size(barWidthPx, size.height)
                    )
                    if (tickFraction != null) {
                        val tickX = size.width * tickFraction
                        drawLine(
                            color = tickOutlineColor,
                            start = Offset(tickX, 0f),
                            end = Offset(tickX, size.height),
                            strokeWidth = 5.dp.toPx()
                        )
                        drawLine(
                            color = tickColor,
                            start = Offset(tickX, 0f),
                            end = Offset(tickX, size.height),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }
            }
        }
    }
}
