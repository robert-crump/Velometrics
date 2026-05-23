package com.cyclegraph.app.util

import com.cyclegraph.app.domain.model.IntervalPrototypeRoute
import com.cyclegraph.app.domain.model.IntervalSession
import com.cyclegraph.app.util.CyclingConstants.INTERVAL_COLOR_MAX_DURATION_SEC
import com.cyclegraph.app.util.CyclingConstants.INTERVAL_COLOR_MIN_DURATION_SEC
import com.cyclegraph.app.util.CyclingConstants.INTERVAL_DURATION_COLOR_RAMP
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Layer

// Inserts a layer below the user-location marker so the marker always stays on top.
// Falls back to addLayer (top of stack) when the marker hasn't been placed yet.
fun addLayerBelowUserMarker(style: Style, layer: Layer) {
    if (style.getLayer("user-location-outer") != null) {
        try { style.addLayerBelow(layer, "user-location-outer") } catch (_: Exception) {}
    } else {
        try { style.addLayer(layer) } catch (_: Exception) {}
    }
}

data class IntervalGroup(
    val prototypeRoute: IntervalPrototypeRoute,
    val intervals: List<IntervalSession>,
    val avgDurationNormalizedSec: Int,
    val avgPower: Int
)

object MapOverlayUtils {

    fun speedToColor(speedKmh: Double): String {
        return when {
            speedKmh <= 0.0 -> CyclingConstants.SPEED_COLOR_MAP["0 km/h"]!!
            speedKmh < 20.0 -> CyclingConstants.SPEED_COLOR_MAP["0-20 km/h"]!!
            speedKmh < 25.0 -> CyclingConstants.SPEED_COLOR_MAP["20-25 km/h"]!!
            speedKmh < 30.0 -> CyclingConstants.SPEED_COLOR_MAP["25-30 km/h"]!!
            speedKmh < 40.0 -> CyclingConstants.SPEED_COLOR_MAP["30-40 km/h"]!!
            speedKmh < 50.0 -> CyclingConstants.SPEED_COLOR_MAP["40-50 km/h"]!!
            speedKmh < 60.0 -> CyclingConstants.SPEED_COLOR_MAP["50-60 km/h"]!!
            speedKmh < 70.0 -> CyclingConstants.SPEED_COLOR_MAP["60-70 km/h"]!!
            else -> CyclingConstants.SPEED_COLOR_MAP[">70 km/h"]!!
        }
    }

    // --- Interval overlay utilities ---

    fun normalizedDurationToColor(durationNormalizedSec: Int): String {
        val clamped = durationNormalizedSec.coerceIn(INTERVAL_COLOR_MIN_DURATION_SEC, INTERVAL_COLOR_MAX_DURATION_SEC)
        val fraction = (clamped - INTERVAL_COLOR_MIN_DURATION_SEC).toDouble() /
                (INTERVAL_COLOR_MAX_DURATION_SEC - INTERVAL_COLOR_MIN_DURATION_SEC)

        val ramp = INTERVAL_DURATION_COLOR_RAMP
        val segments = ramp.size - 1
        val segmentFraction = fraction * segments
        val segIndex = segmentFraction.toInt().coerceAtMost(segments - 1)
        val localFraction = segmentFraction - segIndex

        return interpolateColor(ramp[segIndex], ramp[segIndex + 1], localFraction)
    }

    private fun interpolateColor(color1: String, color2: String, fraction: Double): String {
        val (r1, g1, b1) = parseHexColor(color1)
        val (r2, g2, b2) = parseHexColor(color2)
        val r = (r1 + (r2 - r1) * fraction).toInt().coerceIn(0, 255)
        val g = (g1 + (g2 - g1) * fraction).toInt().coerceIn(0, 255)
        val b = (b1 + (b2 - b1) * fraction).toInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(r, g, b)
    }

    private fun parseHexColor(hex: String): Triple<Int, Int, Int> {
        val clean = hex.removePrefix("#")
        val r = clean.substring(0, 2).toInt(16)
        val g = clean.substring(2, 4).toInt(16)
        val b = clean.substring(4, 6).toInt(16)
        return Triple(r, g, b)
    }

    fun groupIntervals(
        intervals: List<IntervalSession>,
        prototypes: List<IntervalPrototypeRoute>
    ): Pair<List<IntervalGroup>, List<IntervalSession>> {
        val prototypeMap = prototypes.associateBy { it.id }
        val (matched, ungrouped) = intervals.partition { it.prototypeRouteId != null }
        val groups = matched.groupBy { it.prototypeRouteId!! }
            .mapNotNull { (protoId, groupIntervals) ->
                val proto = prototypeMap[protoId] ?: return@mapNotNull null
                val avgDuration = groupIntervals.map { it.durationNormalizedSec }.average().toInt()
                val avgPower = groupIntervals.map { it.avgPower }.average().toInt()
                IntervalGroup(proto, groupIntervals, avgDuration, avgPower)
            }
        return Pair(groups, ungrouped)
    }

    fun formatDurationMinSec(totalSec: Int): String {
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return "$minutes:%02d".format(seconds)
    }
}
