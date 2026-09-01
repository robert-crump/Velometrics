package com.velometrics.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor

data class ScatterPoint(val x: Float, val y: Float, val color: Color? = null)

/**
 * Rounds [min]/[max] outward to the nearest [step], e.g. for axis bounds that land on round
 * tick values instead of the raw data range.
 */
fun roundedAxisBounds(min: Float, max: Float, step: Float = 10f): Pair<Float, Float> {
    val lo = floor(min / step) * step
    val hi = ceil(max / step) * step
    return lo to hi
}

private val NICE_INTEGER_STEPS = intArrayOf(1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000)

/**
 * Whole-number tick values spanning [lo, hi], spaced by the smallest "nice" integer step
 * (1, 2, 5, 10, ...) that keeps the tick count at or below [maxTicks]. Narrow ranges naturally
 * produce fewer, non-repeating labels instead of always rendering [maxTicks] + 1 of them.
 */
fun integerAxisTicks(lo: Float, hi: Float, maxTicks: Int = 4): List<Float> {
    val range = hi - lo
    val step = if (range <= 0f) {
        1
    } else {
        NICE_INTEGER_STEPS.firstOrNull { range / it <= maxTicks } ?: NICE_INTEGER_STEPS.last()
    }
    val start = ceil(lo / step) * step
    val ticks = mutableListOf<Float>()
    var v = start
    while (v <= hi + step * 1e-4f) {
        ticks.add(v)
        v += step
    }
    if (ticks.isEmpty()) ticks.add(((lo + hi) / 2f))
    return ticks
}

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
    xTickFormat: String = "%.1f",
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
            "$xLabel ranges from ${xTickFormat.format(xValues.min())} to ${xTickFormat.format(xValues.max())}. " +
            "$yLabel ranges from ${"%.0f".format(yValues.min())} to ${"%.0f".format(yValues.max())}."
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .semantics { contentDescription = chartDescription }
    ) {
        val padLeft = 56.dp.toPx()
        val padRight = 16.dp.toPx()
        val padTop = 16.dp.toPx()
        val padBottom = 48.dp.toPx()

        val plotW = size.width - padLeft - padRight
        val plotH = size.height - padTop - padBottom

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

        fun mapX(v: Float) = padLeft + (v - xLo) / (xHi - xLo) * plotW
        fun mapY(v: Float) = padTop + plotH - (v - yLo) / (yHi - yLo) * plotH

        // Draw axes
        val axisPaint = android.graphics.Paint().apply {
            color = axisColor.toArgb()
            strokeWidth = 1.5f * density.density
        }
        drawContext.canvas.nativeCanvas.apply {
            // X-axis
            drawLine(padLeft, padTop + plotH, padLeft + plotW, padTop + plotH, axisPaint)
            // Y-axis
            drawLine(padLeft, padTop, padLeft, padTop + plotH, axisPaint)
        }

        // Draw tick labels
        val textPaint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = with(density) { 10.sp.toPx() }
            isAntiAlias = true
        }

        val xTicks = 4
        for (i in 0..xTicks) {
            val v = xLo + (xHi - xLo) * i / xTicks
            val x = mapX(v)
            val label = xTickFormat.format(v)
            drawContext.canvas.nativeCanvas.drawText(
                label,
                x - textPaint.measureText(label) / 2,
                padTop + plotH + 14.dp.toPx(),
                textPaint
            )
        }

        for (v in integerAxisTicks(yLo, yHi)) {
            val y = mapY(v)
            val label = "%.0f".format(v)
            drawContext.canvas.nativeCanvas.drawText(
                label,
                padLeft - textPaint.measureText(label) - 4.dp.toPx(),
                y + textPaint.textSize / 3,
                textPaint
            )
        }

        // Axis labels
        val labelPaint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = with(density) { 11.sp.toPx() }
            isAntiAlias = true
            isFakeBoldText = true
        }
        // X label centered below x-axis
        drawContext.canvas.nativeCanvas.drawText(
            xLabel,
            padLeft + plotW / 2 - labelPaint.measureText(xLabel) / 2,
            size.height - 4.dp.toPx(),
            labelPaint
        )
        // Y label rotated (vertical)
        drawContext.canvas.nativeCanvas.save()
        drawContext.canvas.nativeCanvas.rotate(-90f, 12.dp.toPx(), padTop + plotH / 2)
        drawContext.canvas.nativeCanvas.drawText(
            yLabel,
            12.dp.toPx() - labelPaint.measureText(yLabel) / 2,
            padTop + plotH / 2,
            labelPaint
        )
        drawContext.canvas.nativeCanvas.restore()

        // Draw dots
        val dotRadiusPx = dotRadius.toPx()
        for (pt in points) {
            drawCircle(
                color = pt.color ?: dotColor,
                radius = dotRadiusPx,
                center = Offset(mapX(pt.x), mapY(pt.y))
            )
        }
    }
}
