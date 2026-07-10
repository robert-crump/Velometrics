package com.velometrics.app.domain.service

import com.velometrics.app.util.GeoUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Convex hull over a candidate route's resolved waypoint nodes and corridor centroids (issue #131).
 * Used to bias glue routing toward tracing the planned oval's perimeter (the "ring") instead of
 * shortest-pathing through the interior, which is what produced near-zero enclosed area
 * (compactness 0.03-0.25) even without literally repeating roads.
 *
 * All geometry is done in a local equirectangular projection (meters) anchored at the first input
 * point, accurate enough for the corridor-scale distances this operates on.
 */
class LoopHull private constructor(
    private val originLat: Double,
    private val originLon: Double,
    private val verticesLocal: List<Pair<Double, Double>>,
    val bandM: Double,
) {

    /**
     * Cost multiplier for a point at [lat]/[lon]: 1.0 exactly on the ring, growing quadratically
     * with distance from it (in either direction - cutting into the interior or overshooting
     * outward), capped at `1 + w * 9` once distance reaches 3 bands.
     */
    fun hullFactor(lat: Double, lon: Double, w: Double = DEFAULT_W): Double {
        val ringDistM = distanceToRingM(lat, lon)
        return 1.0 + w * min(ringDistM / bandM, MAX_BAND_MULTIPLES).pow(2)
    }

    /** Distance in meters from ([lat], [lon]) to the nearest point on the hull boundary. */
    fun distanceToRingM(lat: Double, lon: Double): Double {
        val p = toLocalMeters(originLat, originLon, lat, lon)
        var best = Double.MAX_VALUE
        val n = verticesLocal.size
        for (i in 0 until n) {
            val a = verticesLocal[i]
            val b = verticesLocal[(i + 1) % n]
            val d = pointToSegmentDistanceLocal(p, a, b)
            if (d < best) best = d
        }
        return best
    }

    companion object {
        private const val DEFAULT_W = 0.4
        private const val MAX_BAND_MULTIPLES = 3.0

        /** Degenerate-hull area floor (~1 km^2); below this the hull yields no penalty. */
        private const val MIN_AREA_M2 = 1_000_000.0

        private const val BAND_FRACTION_OF_RADIUS = 0.15
        private const val MIN_BAND_M = 500.0
        private const val MAX_BAND_M = 2000.0

        /**
         * Builds a hull from [points] (lat, lon pairs). Returns null for a degenerate hull - fewer
         * than 3 distinct hull vertices (e.g. collinear input), or an enclosed area under ~1 km^2 -
         * so callers can treat "no hull" as "no penalty", preserving today's behavior.
         */
        fun build(points: List<Pair<Double, Double>>): LoopHull? {
            if (points.size < 3) return null
            val origin = points.first()
            val local = points.map { toLocalMeters(origin.first, origin.second, it.first, it.second) }
            val hull = convexHull(local)
            if (hull.size < 3) return null
            val area = polygonArea(hull)
            if (area < MIN_AREA_M2) return null

            val centroidX = hull.sumOf { it.first } / hull.size
            val centroidY = hull.sumOf { it.second } / hull.size
            val meanRingRadiusM = hull.map { euclid(it.first, it.second, centroidX, centroidY) }.average()
            val bandM = (BAND_FRACTION_OF_RADIUS * meanRingRadiusM).coerceIn(MIN_BAND_M, MAX_BAND_M)

            return LoopHull(origin.first, origin.second, hull, bandM)
        }

        internal fun toLocalMeters(
            originLat: Double,
            originLon: Double,
            lat: Double,
            lon: Double,
        ): Pair<Double, Double> {
            val metersPerDegLon = GeoUtils.METERS_PER_DEG_LAT * cos(Math.toRadians(originLat))
            val east = (lon - originLon) * metersPerDegLon
            val north = (lat - originLat) * GeoUtils.METERS_PER_DEG_LAT
            return east to north
        }

        private fun pointToSegmentDistanceLocal(
            p: Pair<Double, Double>,
            a: Pair<Double, Double>,
            b: Pair<Double, Double>,
        ): Double {
            val dx = b.first - a.first
            val dy = b.second - a.second
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq == 0.0) {
                0.0
            } else {
                (((p.first - a.first) * dx + (p.second - a.second) * dy) / lenSq).coerceIn(0.0, 1.0)
            }
            val projX = a.first + t * dx
            val projY = a.second + t * dy
            return euclid(p.first, p.second, projX, projY)
        }

        private fun euclid(x1: Double, y1: Double, x2: Double, y2: Double): Double {
            val dx = x2 - x1
            val dy = y2 - y1
            return sqrt(dx * dx + dy * dy)
        }

        /** Shoelace-formula area of a closed polygon given in order (implicitly closed last->first). */
        internal fun polygonArea(points: List<Pair<Double, Double>>): Double {
            var sum = 0.0
            val n = points.size
            for (i in 0 until n) {
                val (x1, y1) = points[i]
                val (x2, y2) = points[(i + 1) % n]
                sum += x1 * y2 - x2 * y1
            }
            return abs(sum) / 2.0
        }

        /**
         * Andrew's monotone chain convex hull. Returns vertices in order (no duplicate closing
         * point). Collinear input collapses to fewer than 3 points, which callers treat as
         * degenerate.
         */
        internal fun convexHull(pointsIn: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
            val points = pointsIn.distinct().sortedWith(compareBy({ it.first }, { it.second }))
            if (points.size < 3) return points

            fun cross(o: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
                (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)

            val lower = mutableListOf<Pair<Double, Double>>()
            for (p in points) {
                while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) {
                    lower.removeAt(lower.size - 1)
                }
                lower.add(p)
            }

            val upper = mutableListOf<Pair<Double, Double>>()
            for (p in points.asReversed()) {
                while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) {
                    upper.removeAt(upper.size - 1)
                }
                upper.add(p)
            }

            lower.removeAt(lower.size - 1)
            upper.removeAt(upper.size - 1)
            return lower + upper
        }
    }
}
