package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.util.GeoUtils
import com.velometrics.app.util.PolylineDecoder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * Shape quality of a generated route (issue #129).
 *
 * @property repeatFraction Share of total route length ridden on roads already ridden earlier in
 *  the route, after applying the stem exemption. This is the primary lollipop/tree signal.
 * @property rawRepeatFraction Same, but without the stem exemption — diagnostic only.
 * @property compactness Isoperimetric quotient 4π·Area/Length² of the route polygon, where Area is
 *  the net (signed, self-cancelling) enclosed area and Length the full route length. 1.0 is a
 *  perfect circle, ~0.785 a square loop, ~0 a pure out-and-back.
 */
data class RouteShapeReport(
    val totalLengthM: Double,
    val repeatedLengthM: Double,
    val repeatFraction: Double,
    val rawRepeatedLengthM: Double,
    val rawRepeatFraction: Double,
    val compactness: Double,
    val repeatedRoadCount: Int,
)

/**
 * Quantifies how oval (good) vs. lollipop/tree-shaped (bad) a refined route is.
 *
 * Repeat metric: consecutive traversals of the same road count direction-agnostically (A→B and
 * B→A are the same road). Per the #129 stem policy, the first and last [DEFAULT_STEM_EXEMPTION_M]
 * of the route may share geometry: a repeated road is exempt only when every one of its traversals
 * lies fully inside those two windows; a single traversal in the middle portion makes all of its
 * repetition count.
 *
 * Compactness uses the net shoelace area over the route geometry, so out-and-back excursions
 * cancel to zero enclosed area by construction rather than needing special-casing.
 */
object RouteShapeMetrics {

    const val DEFAULT_STEM_EXEMPTION_M = 8_000.0

    fun evaluate(
        edges: List<MapEdge>,
        nodeCoordinates: Map<Long, Pair<Double, Double>> = emptyMap(),
        stemExemptionM: Double = DEFAULT_STEM_EXEMPTION_M,
    ): RouteShapeReport {
        val totalLengthM = edges.sumOf { it.lengthM }
        if (edges.isEmpty() || totalLengthM <= 0.0) {
            return RouteShapeReport(totalLengthM, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        }

        data class Traversal(val startM: Double, val lengthM: Double)

        val traversalsByRoad = LinkedHashMap<Pair<Long, Long>, MutableList<Traversal>>()
        var cursorM = 0.0
        for (edge in edges) {
            val road = if (edge.fromNode <= edge.toNode) {
                edge.fromNode to edge.toNode
            } else {
                edge.toNode to edge.fromNode
            }
            traversalsByRoad.getOrPut(road) { mutableListOf() }.add(Traversal(cursorM, edge.lengthM))
            cursorM += edge.lengthM
        }

        val headEndM = stemExemptionM
        val tailStartM = totalLengthM - stemExemptionM
        fun inExemptWindow(t: Traversal): Boolean =
            (t.startM + t.lengthM) <= headEndM || t.startM >= tailStartM

        var repeatedM = 0.0
        var rawRepeatedM = 0.0
        var repeatedRoadCount = 0
        for (traversals in traversalsByRoad.values) {
            if (traversals.size < 2) continue
            val extraM = traversals.drop(1).sumOf { it.lengthM }
            rawRepeatedM += extraM
            if (!traversals.all(::inExemptWindow)) {
                repeatedM += extraM
                repeatedRoadCount++
            }
        }

        return RouteShapeReport(
            totalLengthM = totalLengthM,
            repeatedLengthM = repeatedM,
            repeatFraction = repeatedM / totalLengthM,
            rawRepeatedLengthM = rawRepeatedM,
            rawRepeatFraction = rawRepeatedM / totalLengthM,
            compactness = compactness(edges, nodeCoordinates, totalLengthM),
            repeatedRoadCount = repeatedRoadCount,
        )
    }

    private fun compactness(
        edges: List<MapEdge>,
        nodeCoordinates: Map<Long, Pair<Double, Double>>,
        totalLengthM: Double,
    ): Double {
        val points = routeGeometry(edges, nodeCoordinates)
        if (points.size < 3) return 0.0

        val (lat0, lon0) = points.first()
        val metersPerDegLon = GeoUtils.METERS_PER_DEG_LAT * cos(Math.toRadians(lat0))

        // Shoelace over the local-projected polygon, implicitly closed last→first.
        var signedArea2 = 0.0
        var prevX = 0.0
        var prevY = 0.0
        var firstX = 0.0
        var firstY = 0.0
        for ((i, p) in points.withIndex()) {
            val x = (p.second - lon0) * metersPerDegLon
            val y = (p.first - lat0) * GeoUtils.METERS_PER_DEG_LAT
            if (i == 0) {
                firstX = x
                firstY = y
            } else {
                signedArea2 += prevX * y - x * prevY
            }
            prevX = x
            prevY = y
        }
        signedArea2 += prevX * firstY - firstX * prevY

        val areaM2 = abs(signedArea2) / 2.0
        return 4.0 * PI * areaM2 / (totalLengthM * totalLengthM)
    }

    /** Ordered (lat, lon) points along the route: decoded edge geometry, node-coordinate fallback. */
    fun routeGeometry(
        edges: List<MapEdge>,
        nodeCoordinates: Map<Long, Pair<Double, Double>> = emptyMap(),
    ): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        for (edge in edges) {
            val decoded = PolylineDecoder.decode(edge.geometryEncoded)
            if (decoded.size >= 2) {
                decoded.mapTo(points) { it.latitude to it.longitude }
            } else {
                nodeCoordinates[edge.fromNode]?.let(points::add)
                nodeCoordinates[edge.toNode]?.let(points::add)
            }
        }
        return points
    }
}
