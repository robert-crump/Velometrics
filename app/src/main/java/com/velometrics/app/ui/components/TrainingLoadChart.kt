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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.velometrics.app.ui.screens.trainingload.DailyTrainingLoadPoint
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val CtlColorLabel = "Fitness (CTL)" to "Fatigue (ATL)"
private const val CHART_HEIGHT_DP = 160

private val monthDayFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

/**
 * Two-line trend chart of CTL (Fitness) and ATL (Fatigue) over [points] (#190), following
 * [CardiacDriftChart]'s hand-rolled Canvas template. TSB (Form) is deliberately not drawn here —
 * it's shown as a single current-value stat elsewhere on the Training Load screen, since it's a
 * derived difference rather than its own trend worth a third line.
 */
@Composable
fun TrainingLoadChart(points: List<DailyTrainingLoadPoint>) {
    val ctlColor = MaterialTheme.colorScheme.primary
    val atlColor = Color(0xFFFFA726) // orange, matches this app's existing secondary-series convention
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(text = "Training Load", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Last ${points.size} days",
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    LegendDot(color = ctlColor, label = CtlColorLabel.first)
                    LegendDot(color = atlColor, label = CtlColorLabel.second)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (points.isEmpty()) return@Column

            val ctlValues = points.map { it.ctl }
            val atlValues = points.map { it.atl }
            val rawMin = minOf(ctlValues.min(), atlValues.min(), 0.0)
            val rawMax = maxOf(ctlValues.max(), atlValues.max(), 1.0)
            val span = (rawMax - rawMin).coerceAtLeast(1.0)
            val padding = span * 0.1
            val minV = rawMin - padding
            val maxV = rawMax + padding
            val lastIndex = points.size - 1

            // Show at most ~5 x-axis date labels regardless of window length.
            val labelStride = labelStride(points.size, 5)

            // The line/points are drawn on a Canvas, which carries no semantics of its own.
            val chartDescription = "Fitness trending from ${ctlValues.first().roundToInt()} to " +
                "${ctlValues.last().roundToInt()}, fatigue from ${atlValues.first().roundToInt()} " +
                "to ${atlValues.last().roundToInt()}, over ${points.size} days."

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CHART_HEIGHT_DP.dp)
                    .semantics { contentDescription = chartDescription }
            ) {
                val rect = plotRect(size.width, size.height, 8.dp, 8.dp, 8.dp, 18.dp)
                val xScale = rect.indexScale(points.size)
                val yScale = rect.yScale(minV, maxV)

                fun drawSeries(values: List<Double>, color: Color) {
                    for (i in 0 until lastIndex) {
                        drawLine(
                            color = color,
                            start = Offset(xScale.map(i), yScale.map(values[i])),
                            end = Offset(xScale.map(i + 1), yScale.map(values[i + 1])),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }

                drawSeries(ctlValues, ctlColor)
                drawSeries(atlValues, atlColor)

                val labelPainter = ChartLabelPainter(9.dp.toPx())
                val labelColor = onSurfaceVariant.copy(alpha = 0.6f)
                var i = 0
                while (i <= lastIndex) {
                    labelPainter.draw(
                        this,
                        monthDayFormatter.format(points[i].date),
                        xScale.map(i),
                        size.height - 2.dp.toPx(),
                        labelColor
                    )
                    i += labelStride
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "● ", color = color, style = MaterialTheme.typography.labelSmall)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
