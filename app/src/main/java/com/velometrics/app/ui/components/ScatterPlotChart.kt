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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor

data class ScatterPoint(val x: Float, val y: Float)

@Composable
fun ScatterPlotChart(
    points: List<ScatterPoint>,
    xLabel: String,
    yLabel: String,
    modifier: Modifier = Modifier,
    xMin: Float? = null,
    xMax: Float? = null,
    xTickFormat: String = "%.1f"
) {
    if (points.isEmpty()) return

    val dotColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val density = LocalDensity.current

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
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
        val yMin = points.minOf { it.y }
        val yMax = points.maxOf { it.y }

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

        val yPad = if (yMax == yMin) 1f else (yMax - yMin) * 0.1f
        val yLo = yMin - yPad
        val yHi = yMax + yPad

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

        val yTicks = 4
        for (i in 0..yTicks) {
            val v = yLo + (yHi - yLo) * i / yTicks
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
        val dotRadius = 6.dp.toPx()
        for (pt in points) {
            drawCircle(
                color = dotColor,
                radius = dotRadius,
                center = Offset(mapX(pt.x), mapY(pt.y))
            )
        }
    }
}
