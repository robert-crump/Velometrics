package com.velometrics.app.ui.components

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Pure axis maths shared by the hand-drawn Canvas line/scatter charts: tick derivation, the padded
 * plot rectangle, and value → pixel scales. Kept free of Compose drawing so it is unit-testable;
 * label drawing lives in [ChartLabelPainter].
 */

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
 * Whole-number tick values spanning [lo, hi], spaced by the smallest step that keeps the interval
 * count at or below [maxTicks]. Narrow ranges naturally produce fewer, non-repeating labels
 * instead of always rendering [maxTicks] + 1 of them.
 *
 * By default the step is the smallest "nice" integer (1, 2, 5, 10, ...). With [stepUnit] the step
 * is instead the smallest multiple of that unit (e.g. 50 W for power), so ticks stay on round
 * values of a domain-specific grid.
 */
fun integerAxisTicks(lo: Float, hi: Float, maxTicks: Int = 4, stepUnit: Int? = null): List<Float> {
    val range = hi - lo
    val step = when {
        range <= 0f -> stepUnit ?: 1
        stepUnit != null -> ceil(range / maxTicks / stepUnit).toInt().coerceAtLeast(1) * stepUnit
        else -> NICE_INTEGER_STEPS.firstOrNull { range / it <= maxTicks } ?: NICE_INTEGER_STEPS.last()
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

/** [count] + 1 evenly spaced tick values from [lo] to [hi] inclusive. */
fun evenAxisTicks(lo: Float, hi: Float, count: Int): List<Float> =
    (0..count).map { lo + (hi - lo) * it / count }

/**
 * Step between labelled category indices so at most about [maxLabels] labels are drawn for
 * [count] points (dates, minute marks) regardless of series length.
 */
fun labelStride(count: Int, maxLabels: Int): Int = (count / maxLabels).coerceAtLeast(1)

/** Linear map from a data domain [lo]..[hi] onto pixels [startPx]..[endPx] (either direction). */
class LinearScale(
    private val lo: Double,
    private val hi: Double,
    private val startPx: Float,
    private val endPx: Float
) {
    /** A zero-width domain (single value) maps to the middle of the pixel range. */
    fun map(v: Double): Float =
        if (hi == lo) (startPx + endPx) / 2f
        else startPx + ((v - lo) / (hi - lo)).toFloat() * (endPx - startPx)

    fun map(v: Float): Float = map(v.toDouble())

    fun map(v: Int): Float = map(v.toDouble())

    /** Inverse of [map], e.g. for turning a touch x back into a data value. */
    fun invert(px: Float): Double =
        if (endPx == startPx) lo else lo + (px - startPx) / (endPx - startPx) * (hi - lo)
}

/** The drawable plot area inside a chart's padding, in pixels. */
data class PlotRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Left-to-right scale over [lo]..[hi]. */
    fun xScale(lo: Double, hi: Double) = LinearScale(lo, hi, left, right)

    /** Bottom-to-top scale over [lo]..[hi] (larger values are higher on screen). */
    fun yScale(lo: Double, hi: Double) = LinearScale(lo, hi, bottom, top)

    /** Evenly spaced x positions for indices 0 until [count]; a single index is centred. */
    fun indexScale(count: Int) = xScale(0.0, (count - 1).toDouble().coerceAtLeast(0.0))

    companion object {
        fun fromPadding(
            width: Float,
            height: Float,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float
        ) = PlotRect(left, top, width - right, height - bottom)
    }
}

/** [PlotRect] for a chart of [width]×[height] px inset by the given [Dp] paddings. */
fun Density.plotRect(
    width: Float,
    height: Float,
    left: Dp,
    top: Dp,
    right: Dp,
    bottom: Dp
) = PlotRect.fromPadding(width, height, left.toPx(), top.toPx(), right.toPx(), bottom.toPx())
