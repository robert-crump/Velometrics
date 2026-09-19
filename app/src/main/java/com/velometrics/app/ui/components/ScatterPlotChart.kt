package com.velometrics.app.ui.components

import android.graphics.Paint
import com.velometrics.app.util.FormatUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ScatterPoint(val x: Float, val y: Float, val color: Color? = null)

@Composable
fun ScatterPlotChart(
    points: List<ScatterPoint>,
    xLabel: String,
    yLabel: String,
    modifier: Modifier = Modifier,
    xMin: Float? = null,
    xMax: Float? = null,
    yMin: Float? = null,
    yMax: Float? = null,
    xTickDecimals: Int = 1,
    dotRadius: Dp = 6.dp
) {
    if (points.isEmpty()) return

    val dotColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val density = LocalDensity.current

    // Drawn entirely on Canvas, which carries no semantics of its own — give the chart a summary
    // of what it plots and its data range so TalkBack doesn't skip it silently.
    val chartDescription = run {
        val xValues = points.map { it.x }
        val yValues = points.map { it.y }
        "Scatter plot of $yLabel versus $xLabel, ${points.size} points. " +
            "$xLabel ranges from ${FormatUtils.formatDecimal(xValues.min().toDouble(), xTickDecimals)} to ${FormatUtils.formatDecimal(xValues.max().toDouble(), xTickDecimals)}. " +
            "$yLabel ranges from ${FormatUtils.formatDecimal(yValues.min().toDouble(), 0)} to ${FormatUtils.formatDecimal(yValues.max().toDouble(), 0)}."
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .semantics { contentDescription = chartDescription }
    ) {
        val rect = plotRect(size.width, size.height, 56.dp, 16.dp, 16.dp, 48.dp)

        // Data ranges
        val xDataMin = points.minOf { it.x }
        val xDataMax = points.maxOf { it.x }
        val yDataMin = points.minOf { it.y }
        val yDataMax = points.maxOf { it.y }

        // Use provided x bounds or fall back to 10%-padded data range
        val xLo: Float
        val xHi: Float
        if (xMin != null && xMax != null) {
            xLo = xMin
            xHi = xMax
        } else {
            val xPad = if (xDataMax == xDataMin) 1f else (xDataMax - xDataMin) * 0.1f
            xLo = xDataMin - xPad
            xHi = xDataMax + xPad
        }

        // Use provided y bounds or fall back to 10%-padded data range
        val yLo: Float
        val yHi: Float
        if (yMin != null && yMax != null) {
            yLo = yMin
            yHi = yMax
        } else {
            val yPad = if (yDataMax == yDataMin) 1f else (yDataMax - yDataMin) * 0.1f
            yLo = yDataMin - yPad
            yHi = yDataMax + yPad
        }

        val xScale = rect.xScale(xLo.toDouble(), xHi.toDouble())
        val yScale = rect.yScale(yLo.toDouble(), yHi.toDouble())

        // Draw axes
        val axisStroke = 1.5f * density.density
        drawLine(axisColor, Offset(rect.left, rect.bottom), Offset(rect.right, rect.bottom), axisStroke)
        drawLine(axisColor, Offset(rect.left, rect.top), Offset(rect.left, rect.bottom), axisStroke)

        // Draw tick labels
        val tickPainter = ChartLabelPainter(with(density) { 10.sp.toPx() })
        for (v in evenAxisTicks(xLo, xHi, 4)) {
            tickPainter.draw(
                this,
                FormatUtils.formatDecimal(v.toDouble(), xTickDecimals),
                xScale.map(v),
                rect.bottom + 14.dp.toPx(),
                labelColor
            )
        }
        for (v in integerAxisTicks(yLo, yHi)) {
            tickPainter.draw(
                this,
                FormatUtils.formatDecimal(v.toDouble(), 0),
                rect.left - 4.dp.toPx(),
                yScale.map(v) + tickPainter.textSize / 3,
                labelColor,
                Paint.Align.RIGHT
            )
        }

        // Axis labels: x centered below x-axis, y rotated (vertical)
        val labelPainter = ChartLabelPainter(with(density) { 11.sp.toPx() }, bold = true)
        labelPainter.draw(this, xLabel, rect.left + rect.width / 2, size.height - 4.dp.toPx(), labelColor)
        labelPainter.drawVertical(this, yLabel, 12.dp.toPx(), rect.top + rect.height / 2, labelColor)

        // Draw dots
        val dotRadiusPx = dotRadius.toPx()
        for (pt in points) {
            drawCircle(
                color = pt.color ?: dotColor,
                radius = dotRadiusPx,
                center = Offset(xScale.map(pt.x), yScale.map(pt.y))
            )
        }
    }
}
