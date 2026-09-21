package com.velometrics.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.velometrics.app.domain.model.HrDistancePoint
import com.velometrics.app.util.CyclingConstants
import kotlin.math.abs
import kotlin.math.roundToInt

private const val CHART_HEIGHT_DP = 160

private val zoneBandColors = listOf(
    Color(0xFFEF9A9A), Color(0xFFE57373), Color(0xFFEF5350), Color(0xFFE53935), Color(0xFFB71C1C)
)

/**
 * Heart rate vs. distance line (#204) over a filled elevation backdrop and faint HR zone bands.
 * A null-HR point breaks the line rather than being interpolated. Dragging shows a cursor with
 * the distance and bpm at that point. Zone bands use [maxHr] as passed in (the current setting),
 * so they follow settings changes; [points] only carries raw bpm.
 */
@Composable
fun HrDistanceChart(points: List<HrDistancePoint>, maxHr: Int) {
    val hrValues = remember(points) { points.mapNotNull { it.heartRate } }
    if (hrValues.isEmpty()) return

    val (yLo, yHi) = remember(hrValues) {
        val (lo, hi) = roundedAxisBounds(hrValues.min().toFloat(), hrValues.max().toFloat(), 10f)
        if (hi - lo < 20f) (lo - 10f) to (hi + 10f) else lo to hi
    }
    val altitudes = remember(points) { points.mapNotNull { it.altitudeM } }
    val altMin = altitudes.minOrNull()
    val altMax = altitudes.maxOrNull()
    val totalKm = points.last().distanceKm

    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    var widthPx by remember { mutableFloatStateOf(0f) }

    val lineColor = MaterialTheme.colorScheme.primary
    val elevationColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val cursorColor = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text("Heart Rate vs. Distance", style = MaterialTheme.typography.titleMedium)
            val sel = selected?.let { points[it] }
            Text(
                text = if (sel == null) "Drag along the chart to inspect"
                else "%.1f km · %s".format(sel.distanceKm, sel.heartRate?.let { "$it bpm" } ?: "no HR"),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Light),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            val description = "Heart rate over ${"%.1f".format(totalKm)} km, ranging from " +
                "${hrValues.min()} to ${hrValues.max()} bpm, over the elevation profile."

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CHART_HEIGHT_DP.dp)
                    .onSizeChanged { widthPx = it.width.toFloat() }
                    .semantics { contentDescription = description }
                    .dragToSelectGesture(points, widthPx) { x ->
                        val plotLeft = with(density) { 34.dp.toPx() }
                        val plotRight = widthPx - with(density) { 8.dp.toPx() }
                        val km = LinearScale(0.0, totalKm, plotLeft, plotRight).invert(x)
                        selected = points.indices.minByOrNull { abs(points[it].distanceKm - km) }
                    }
            ) {
                val rect = plotRect(size.width, size.height, 34.dp, 8.dp, 8.dp, 18.dp)
                val xScale = rect.xScale(0.0, totalKm)
                val yScale = rect.yScale(yLo.toDouble(), yHi.toDouble())

                // Zone bands, clipped to the visible bpm range.
                CyclingConstants.HR_ZONES.forEachIndexed { i, (_, range) ->
                    val lo = (range.first * maxHr).coerceIn(yLo.toDouble(), yHi.toDouble())
                    val hi = (range.second * maxHr).coerceIn(yLo.toDouble(), yHi.toDouble())
                    if (hi > lo) {
                        drawRect(
                            color = zoneBandColors[i].copy(alpha = 0.10f),
                            topLeft = Offset(rect.left, yScale.map(hi)),
                            size = Size(rect.width, yScale.map(lo) - yScale.map(hi))
                        )
                    }
                }

                // Elevation backdrop: filled from the plot bottom, one polygon per run of known altitude.
                if (altMin != null && altMax != null) {
                    val altScale = rect.yScale(altMin, altMax.coerceAtLeast(altMin + 1.0))
                    var run = mutableListOf<Offset>()
                    fun flush() {
                        if (run.size >= 2) {
                            val path = Path().apply {
                                moveTo(run.first().x, rect.bottom)
                                run.forEach { lineTo(it.x, it.y) }
                                lineTo(run.last().x, rect.bottom)
                                close()
                            }
                            drawPath(path, elevationColor)
                        }
                        run = mutableListOf()
                    }
                    points.forEach { p ->
                        val alt = p.altitudeM
                        if (alt == null) flush() else run.add(Offset(xScale.map(p.distanceKm), altScale.map(alt)))
                    }
                    flush()
                }

                // HR line: consecutive present points only.
                for (i in 0 until points.lastIndex) {
                    val a = points[i].heartRate ?: continue
                    val b = points[i + 1].heartRate ?: continue
                    drawLine(
                        color = lineColor,
                        start = Offset(xScale.map(points[i].distanceKm), yScale.map(a)),
                        end = Offset(xScale.map(points[i + 1].distanceKm), yScale.map(b)),
                        strokeWidth = 2.dp.toPx()
                    )
                }

                val labels = ChartLabelPainter(9.dp.toPx(), light = true)
                integerAxisTicks(yLo, yHi, maxTicks = 4, stepUnit = 10).forEach { bpm ->
                    labels.draw(
                        this, "${bpm.roundToInt()}", rect.left - 4.dp.toPx(), yScale.map(bpm) + 3.dp.toPx(),
                        labelColor, android.graphics.Paint.Align.RIGHT
                    )
                }
                integerAxisTicks(0f, totalKm.toFloat(), maxTicks = 5).forEach { km ->
                    labels.draw(this, "${km.roundToInt()} km", xScale.map(km), size.height - 2.dp.toPx(), labelColor)
                }

                selected?.let { i ->
                    val x = xScale.map(points[i].distanceKm)
                    drawLine(cursorColor, Offset(x, rect.top), Offset(x, rect.bottom), strokeWidth = 1.dp.toPx())
                    points[i].heartRate?.let {
                        drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(x, yScale.map(it)))
                    }
                }
            }
        }
    }
}
