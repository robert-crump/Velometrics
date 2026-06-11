package com.velometrics.app.util

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Result of a map scale-bar calculation: the bar's pixel width and its distance label. */
data class ScaleBarInfo(val widthPx: Double, val label: String)

object ScaleBarUtils {

    /**
     * Given the current ground distance covered by one screen pixel and the maximum
     * width the bar may occupy, returns the bar width and label for the largest
     * "nice" round distance (1-2-5-10-20-50... pattern) that fits within that width.
     */
    fun computeScaleBar(metersPerPixel: Double, maxWidthPx: Double): ScaleBarInfo {
        val maxMeters = metersPerPixel * maxWidthPx
        val niceMeters = niceDistanceMeters(maxMeters)
        return ScaleBarInfo(niceMeters / metersPerPixel, formatLabel(niceMeters))
    }

    /** Largest value from the 1-2-5 × 10^n sequence that is <= maxMeters (minimum 1 m). */
    fun niceDistanceMeters(maxMeters: Double): Double {
        if (maxMeters <= 1.0) return 1.0
        val magnitude = 10.0.pow(floor(log10(maxMeters)))
        val residual = maxMeters / magnitude
        val niceResidual = when {
            residual >= 5.0 -> 5.0
            residual >= 2.0 -> 2.0
            else -> 1.0
        }
        return niceResidual * magnitude
    }

    fun formatLabel(distanceMeters: Double): String =
        if (distanceMeters < 1000.0) {
            "${distanceMeters.roundToInt()} m"
        } else {
            "${(distanceMeters / 1000.0).roundToInt()} km"
        }
}
