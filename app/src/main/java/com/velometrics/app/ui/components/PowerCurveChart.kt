package com.velometrics.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.velometrics.app.domain.model.PowerCurvePoint
import kotlin.math.abs
import kotlin.math.ln

/**
 * A log-duration-scaled power curve: drag anywhere on the chart to move the selected point, whose
 * watts/duration (and, if [onNavigateToSession] is given and the point has one, originating ride
 * date) are shown above it. Shared by the all-time power curve (across every ride's best efforts)
 * and the single-ride power curve on Session Detail (#173) — the two differ only in what
 * [points] contains and whether tapping the header should navigate anywhere.
 */
@Composable
fun PowerCurveChart(
    points: List<PowerCurvePoint>,
    onNavigateToSession: ((Long) -> Unit)? = null
) {
    val availableIndices = remember(points) { points.indices.filter { points[it].watts != null } }
    if (availableIndices.isEmpty()) return

    var selectedIndex by remember(points) { mutableStateOf(availableIndices.first()) }

    val lineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    val selectedDotColor = MaterialTheme.colorScheme.primary
    val unselectedDotColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current

    val maxWatts = points.mapNotNull { it.watts }.maxOrNull() ?: 0
    val maxAxis = if (maxWatts % 50 == 0 && maxWatts > 0) maxWatts else ((maxWatts / 50) + 1) * 50
    val tickStep = (((maxAxis / 5).coerceAtLeast(1) + 49) / 50) * 50

    val logDurations = remember(points) { points.map { ln(it.durationSec.toDouble()) } }
    val minLog = logDurations.min()
    val maxLog = logDurations.max()

    val selected = points[selectedIndex]

    Column {
        val headerModifier = if (selected.sessionId != null && onNavigateToSession != null) {
            Modifier.clickable { onNavigateToSession(selected.sessionId) }
        } else {
            Modifier
        }
        Row(
            modifier = Modifier.fillMaxWidth().then(headerModifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                selected.watts?.let { "$it W" } ?: "--",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                selected.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (selected.date != null) {
                Text(
                    selected.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
        ) {
            val chartWidthPx = with(density) { maxWidth.toPx() }
            val chartHeightPx = with(density) { maxHeight.toPx() }
            val leftPaddingPx = with(density) { 32.dp.toPx() }
            val rightPaddingPx = with(density) { 8.dp.toPx() }
            val topPaddingPx = with(density) { 8.dp.toPx() }
            val bottomPaddingPx = with(density) { 20.dp.toPx() }

            fun xFor(i: Int): Float {
                val range = (maxLog - minLog).takeIf { it > 0.0 } ?: 1.0
                val fraction = (logDurations[i] - minLog) / range
                return leftPaddingPx + fraction.toFloat() * (chartWidthPx - leftPaddingPx - rightPaddingPx)
            }

            fun yFor(watts: Int): Float {
                val available = chartHeightPx - topPaddingPx - bottomPaddingPx
                return topPaddingPx + available * (1f - watts.toFloat() / maxAxis)
            }

            fun nearestAvailableIndex(x: Float): Int =
                availableIndices.minByOrNull { abs(xFor(it) - x) } ?: availableIndices.first()

            // The line/points are drawn on a Canvas, which carries no semantics of its own — the
            // header above already announces the selected point, so give the chart itself a
            // summary of the overall range a screen reader otherwise can't see.
            val chartDescription = "Power curve. Selected: " +
                (selected.watts?.let { "$it watts" } ?: "no data") + " at ${selected.label}. " +
                "Range across all durations: $maxWatts watts peak."

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .dragToSelectGesture(points) { x ->
                        selectedIndex = nearestAvailableIndex(x)
                    }
                    .semantics { contentDescription = chartDescription }
            ) {
                val labelPaint = android.graphics.Paint().apply {
                    textSize = 9.dp.toPx()
                }

                var tick = 0
                while (tick <= maxAxis) {
                    val y = yFor(tick)
                    drawLine(
                        color = gridColor,
                        start = Offset(leftPaddingPx, y),
                        end = Offset(chartWidthPx - rightPaddingPx, y),
                        strokeWidth = 1.dp.toPx()
                    )
                    labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                    labelPaint.color = onSurface.copy(alpha = 0.5f).toArgb()
                    drawContext.canvas.nativeCanvas.drawText(
                        "$tick",
                        leftPaddingPx - 4.dp.toPx(),
                        y + labelPaint.textSize / 3,
                        labelPaint
                    )
                    tick += tickStep
                }

                val path = Path()
                availableIndices.forEachIndexed { orderIdx, i ->
                    val x = xFor(i)
                    val y = yFor(points[i].watts!!)
                    if (orderIdx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = lineColor, style = Stroke(width = 2.dp.toPx()))

                points.forEachIndexed { i, point ->
                    val x = xFor(i)
                    val watts = point.watts
                    if (watts != null) {
                        val isSelected = i == selectedIndex
                        drawCircle(
                            color = if (isSelected) selectedDotColor else unselectedDotColor,
                            radius = if (isSelected) 5.dp.toPx() else 3.dp.toPx(),
                            center = Offset(x, yFor(watts))
                        )
                    }
                    labelPaint.textAlign = android.graphics.Paint.Align.CENTER
                    labelPaint.color = onSurface.copy(alpha = if (i == selectedIndex) 1f else 0.5f).toArgb()
                    drawContext.canvas.nativeCanvas.drawText(
                        point.label,
                        x,
                        chartHeightPx - 4.dp.toPx(),
                        labelPaint
                    )
                }
            }
        }
    }
}
