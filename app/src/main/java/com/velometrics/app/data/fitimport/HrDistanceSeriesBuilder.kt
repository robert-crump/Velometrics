package com.velometrics.app.data.fitimport

import com.velometrics.app.domain.model.Datapoint
import com.velometrics.app.domain.model.HrDistancePoint
import com.velometrics.app.util.GeoUtils
import kotlin.math.abs

/**
 * Builds the heart rate / elevation vs. distance series persisted on a ride (#204): [POINT_COUNT]
 * samples where point k (1..100) is the first record whose cumulative distance reaches
 * k/100 of the ride, and the last point is the ride's final record.
 */
object HrDistanceSeriesBuilder {

    const val POINT_COUNT = 100
    const val MIN_DISTANCE_KM = 1.0
    const val GAP_SEARCH_RADIUS = 5

    /** Cumulative haversine distance in metres per record; the last entry is the ride total. */
    fun cumulativeMeters(datapoints: List<Datapoint>): DoubleArray {
        val cumulative = DoubleArray(datapoints.size)
        for (i in 1 until datapoints.size) {
            val prev = datapoints[i - 1]
            val curr = datapoints[i]
            cumulative[i] = cumulative[i - 1] +
                GeoUtils.haversineDistance(prev.lat, prev.lon, curr.lat, curr.lon)
        }
        return cumulative
    }

    /**
     * Null when the ride is shorter than [MIN_DISTANCE_KM] (e.g. indoor rides without GPS) or no
     * sampled point has a heart rate.
     */
    fun build(datapoints: List<Datapoint>): List<HrDistancePoint>? {
        if (datapoints.isEmpty()) return null
        val cumulative = cumulativeMeters(datapoints)
        val totalMeters = cumulative.last()
        if (totalMeters / 1000.0 < MIN_DISTANCE_KM) return null

        var index = 0
        val points = (1..POINT_COUNT).map { k ->
            if (k == POINT_COUNT) {
                index = datapoints.lastIndex
            } else {
                val target = totalMeters * k / POINT_COUNT
                while (index < datapoints.lastIndex && cumulative[index] < target) index++
            }
            HrDistancePoint(
                distanceKm = cumulative[index] / 1000.0,
                heartRate = nearestValue(datapoints, index) { dp -> dp.heartRate?.takeIf { it > 0 } },
                altitudeM = nearestValue(datapoints, index) { dp -> dp.altitude }
            )
        }
        return points.takeIf { p -> p.any { it.heartRate != null } }
    }

    /** Value at [index], else the nearest one within [GAP_SEARCH_RADIUS] records (earlier wins ties). */
    private fun <T : Any> nearestValue(datapoints: List<Datapoint>, index: Int, value: (Datapoint) -> T?): T? {
        for (offset in 0..GAP_SEARCH_RADIUS) {
            val before = index - offset
            if (before >= 0) value(datapoints[before])?.let { return it }
            if (offset == 0) continue
            val after = index + offset
            if (after < datapoints.size) value(datapoints[after])?.let { return it }
        }
        return null
    }
}
