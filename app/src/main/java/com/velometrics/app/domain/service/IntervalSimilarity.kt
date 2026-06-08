package com.velometrics.app.domain.service

import com.velometrics.app.util.CyclingConstants.INTERVAL_LENGTH_TOLERANCE_M
import com.velometrics.app.util.CyclingConstants.INTERVAL_POINT_MATCH_RADIUS_M
import com.velometrics.app.util.CyclingConstants.INTERVAL_POINT_SIMILARITY_THRESHOLD
import com.velometrics.app.util.GeoUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/**
 * Shared "do these two GPS tracks represent the same ride segment" test (#10/#25/#26):
 * length within [INTERVAL_LENGTH_TOLERANCE_M] AND ≥ [INTERVAL_POINT_SIMILARITY_THRESHOLD] of one
 * track's points have a neighbor on the other within [INTERVAL_POINT_MATCH_RADIUS_M] (bidirectional
 * max coverage). Used by both [IntervalClusteringService] (to cluster raw intervals into
 * [com.velometrics.app.domain.model.RepeatedInterval] archetypes) and [IntervalMatcher] (to assign
 * newly-detected raw intervals to those archetypes), so "matches" means one consistent thing.
 */
object IntervalSimilarity {

    /** A GPS track ([points], as `[lat, lon]` pairs) prepared for repeated similarity tests against it. */
    class PreparedTrack(val points: List<List<Double>>, val distanceM: Double) {
        val grid = SpatialGrid(points)
    }

    fun qualifies(a: PreparedTrack, b: PreparedTrack): Boolean {
        if (abs(a.distanceM - b.distanceM) > INTERVAL_LENGTH_TOLERANCE_M) return false
        val sim = max(coverageScore(a.points, b.grid), coverageScore(b.points, a.grid))
        return sim >= INTERVAL_POINT_SIMILARITY_THRESHOLD
    }

    private fun coverageScore(points: List<List<Double>>, grid: SpatialGrid): Double {
        if (points.isEmpty()) return 0.0
        var matched = 0
        for (pt in points) {
            if (grid.hasPointWithin(pt[0], pt[1])) matched++
        }
        return matched.toDouble() / points.size
    }

    /** Spatial grid for point-to-point neighbor lookup within [INTERVAL_POINT_MATCH_RADIUS_M]. */
    class SpatialGrid(points: List<List<Double>>) {
        private val latCellSize: Double
        private val lonCellSize: Double
        private val cells = HashMap<Long, MutableList<List<Double>>>()

        init {
            val avgLat = if (points.isEmpty()) 0.0 else points.sumOf { it[0] } / points.size
            latCellSize = INTERVAL_POINT_MATCH_RADIUS_M / 111_320.0
            lonCellSize = INTERVAL_POINT_MATCH_RADIUS_M / (111_320.0 * cos(Math.toRadians(avgLat)))
            for (pt in points) {
                cells.getOrPut(cellKey(pt[0], pt[1])) { mutableListOf() }.add(pt)
            }
        }

        private fun cellKey(lat: Double, lon: Double): Long {
            val row = floor(lat / latCellSize).toLong()
            val col = floor(lon / lonCellSize).toLong()
            return row * 2_000_000L + col + 1_000_000L
        }

        fun hasPointWithin(lat: Double, lon: Double): Boolean {
            val row = floor(lat / latCellSize).toLong()
            val col = floor(lon / lonCellSize).toLong()
            for (dr in -1L..1L) {
                for (dc in -1L..1L) {
                    val bucket = cells[(row + dr) * 2_000_000L + (col + dc) + 1_000_000L]
                        ?: continue
                    for (pt in bucket) {
                        if (GeoUtils.haversineDistance(lat, lon, pt[0], pt[1]) <= INTERVAL_POINT_MATCH_RADIUS_M) return true
                    }
                }
            }
            return false
        }
    }
}
