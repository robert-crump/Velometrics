package com.velometrics.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.velometrics.app.domain.model.CardiacDriftBand
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.FormatUtils
import kotlin.math.roundToInt

private val bandColors = mapOf(
    CardiacDriftBand.GOOD to Color(0xFF4CAF50),        // green
    CardiacDriftBand.NORMAL to Color(0xFFFFA726),       // orange
    CardiacDriftBand.SIGNIFICANT to Color(0xFFEF5350)   // red
)

private val bandLabels = mapOf(
    CardiacDriftBand.GOOD to "Good",
    CardiacDriftBand.NORMAL to "Normal",
    CardiacDriftBand.SIGNIFICANT to "Significant drift"
)

private const val CHART_HEIGHT_DP = 120

/**
 * Line chart of per-bucket efficiency factor (avg power / avg HR) as a percentage of the ride's
 * first-half baseline, with a flat 100% reference line. A bucket absent from [buckets] (dropped
 * for having too many low-power/coasting samples) renders as a genuine gap in the line rather
 * than an interpolated or zero value.
 */
@Composable
fun CardiacDriftChart(buckets: Map<String, Double>, decouplingPercent: Double) {
    val band = CardiacDriftBand.fromPercent(decouplingPercent)
    val bandColor = bandColors[band] ?: MaterialTheme.colorScheme.onSurface

    val maxIndex = remember(buckets) { buckets.keys.maxOfOrNull { it.toInt() } ?: 0 }
    val values = remember(buckets, maxIndex) {
        (0..maxIndex).map { i -> buckets[i.toString()] }
    }

    val lineColor = MaterialTheme.colorScheme.primary
    val referenceLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
                    Text(
                        text = "Cardiac Drift",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "EF vs. first-half baseline",
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = FormatUtils.formatCardiacDriftPercent(decouplingPercent),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = bandColor
                    )
                    Text(
                        text = bandLabels[band] ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = bandColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            val presentValues = values.filterNotNull()
            if (presentValues.isEmpty()) return@Column

            val rawMin = minOf(100.0, presentValues.min())
            val rawMax = maxOf(100.0, presentValues.max())
            val span = (rawMax - rawMin).coerceAtLeast(1.0)
            val padding = span * 0.15
            val minV = rawMin - padding
            val maxV = rawMax + padding

            // Show at most ~6 x-axis labels regardless of ride length
            val labelStride = labelStride(maxIndex + 1, 6)

            // The line/points are drawn on a Canvas, which carries no semantics of its own — the
            // header above already announces the overall decoupling percent and band, so give the
            // chart itself a summary of the per-bucket range a screen reader otherwise can't see.
            val chartDescription = "Efficiency factor trend across the ride, relative to a " +
                "first-half baseline of 100%. Ranges from ${presentValues.min().roundToInt()}% " +
                "to ${presentValues.max().roundToInt()}%."

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CHART_HEIGHT_DP.dp)
                    .semantics { contentDescription = chartDescription }
            ) {
                val rect = plotRect(size.width, size.height, 8.dp, 8.dp, 8.dp, 18.dp)
                val xScale = rect.indexScale(maxIndex + 1)
                val yScale = rect.yScale(minV, maxV)

                // 100% reference line
                val refY = yScale.map(100.0)
                drawLine(
                    color = referenceLineColor,
                    start = Offset(rect.left, refY),
                    end = Offset(rect.right, refY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                )

                // Connect consecutive present buckets only — a gap on either side of a dropped
                // bucket is left unconnected rather than interpolated across.
                for (i in 0 until maxIndex) {
                    val v0 = values[i]
                    val v1 = values[i + 1]
                    if (v0 != null && v1 != null) {
                        drawLine(
                            color = lineColor,
                            start = Offset(xScale.map(i), yScale.map(v0)),
                            end = Offset(xScale.map(i + 1), yScale.map(v1)),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }

                values.forEachIndexed { i, v ->
                    if (v != null) {
                        drawCircle(
                            color = lineColor,
                            radius = 3.dp.toPx(),
                            center = Offset(xScale.map(i), yScale.map(v))
                        )
                    }
                }

                val labelPainter = ChartLabelPainter(9.dp.toPx())
                val labelColor = onSurfaceVariant.copy(alpha = 0.6f)
                var i = 0
                while (i <= maxIndex) {
                    val minuteMark = (i + 1) * CyclingConstants.CARDIAC_DRIFT_BUCKET_SEC / 60
                    labelPainter.draw(this, "${minuteMark}m", xScale.map(i), size.height - 2.dp.toPx(), labelColor)
                    i += labelStride
                }
            }
        }
    }
}
