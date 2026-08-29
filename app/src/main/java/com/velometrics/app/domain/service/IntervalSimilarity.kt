package com.velometrics.app.domain.service

import com.velometrics.app.util.CyclingConstants.INTERVAL_LENGTH_TOLERANCE_M
import com.velometrics.app.util.CyclingConstants.INTERVAL_POINT_MATCH_RADIUS_M
import com.velometrics.app.util.CyclingConstants.INTERVAL_POINT_SIMILARITY_THRESHOLD
import com.velometrics.app.util.GeoUtils
import com.velometrics.app.util.SpatialPointGrid
import kotlin.math.abs
import kotlin.math.cos

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
        val grid = SpatialPointGrid(points, INTERVAL_POINT_MATCH_RADIUS_M)

        // Axis-aligned bounding box, used as a cheap O(1) reject before the grid coverage scan.
        val minLat: Double
        val maxLat: Double
        val minLon: Double
        val maxLon: Double

        init {
            var nLat = Double.MAX_VALUE; var xLat = -Double.MAX_VALUE
            var nLon = Double.MAX_VALUE; var xLon = -Double.MAX_VALUE
            for (p in points) {
                val lat = p[0]; val lon = p[1]
                if (lat < nLat) nLat = lat
                if (lat > xLat) xLat = lat
                if (lon < nLon) nLon = lon
                if (lon > xLon) xLon = lon
            }
            minLat = nLat; maxLat = xLat; minLon = nLon; maxLon = xLon
        }
    }

    fun qualifies(a: PreparedTrack, b: PreparedTrack): Boolean {
        if (abs(a.distanceM - b.distanceM) > INTERVAL_LENGTH_TOLERANCE_M) return false
        if (!bboxesWithinMatchRange(a, b)) return false
        // Short-circuit: qualification only needs max(A→B, B→A) ≥ threshold, so if the first
        // direction already clears it there's no need to scan the second.
        if (coverageScore(a.points, b.grid) >= INTERVAL_POINT_SIMILARITY_THRESHOLD) return true
        return coverageScore(b.points, a.grid) >= INTERVAL_POINT_SIMILARITY_THRESHOLD
    }

    /**
     * True unless the two tracks' bounding boxes are separated by more than
     * [INTERVAL_POINT_MATCH_RADIUS_M] on either axis. A point of one track can lie within the match
     * radius of the other only if both axis gaps are within that radius, so a larger gap guarantees
     * zero coverage — making this a behavior-preserving prefilter. The longitude margin is computed
     * at the highest-magnitude latitude of the pair (smallest cos) so it is never under-estimated.
     */
    private fun bboxesWithinMatchRange(a: PreparedTrack, b: PreparedTrack): Boolean {
        val latMargin = INTERVAL_POINT_MATCH_RADIUS_M / GeoUtils.METERS_PER_DEG_LAT
        if (a.minLat > b.maxLat + latMargin || b.minLat > a.maxLat + latMargin) return false
        val maxAbsLat = maxOf(abs(a.minLat), abs(a.maxLat), abs(b.minLat), abs(b.maxLat))
        val lonMargin = INTERVAL_POINT_MATCH_RADIUS_M / (GeoUtils.METERS_PER_DEG_LAT * cos(Math.toRadians(maxAbsLat)))
        if (a.minLon > b.maxLon + lonMargin || b.minLon > a.maxLon + lonMargin) return false
        return true
    }

    private fun coverageScore(points: List<List<Double>>, grid: SpatialPointGrid): Double {
        if (points.isEmpty()) return 0.0
        var matched = 0
        for (pt in points) {
            if (grid.hasPointWithin(pt[0], pt[1])) matched++
        }
        return matched.toDouble() / points.size
    }

}
