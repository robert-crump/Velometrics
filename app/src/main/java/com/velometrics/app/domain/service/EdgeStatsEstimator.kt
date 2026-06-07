package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.GeoUtils
import com.velometrics.app.util.PolylineDecoder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject
import javax.inject.Singleton

data class EdgeStats(
    val speedP25: Double, val speedP50: Double, val speedP75: Double,
    val powerP25: Double?, val powerP50: Double?, val powerP75: Double?,
    val isEstimated: Boolean
)

/**
 * Fills in speed/power statistics for points that don't have their own traversal data
 * (untraversed graph edges, or arbitrary `.gpx` geometry) by borrowing distance- and
 * bearing-weighted stats from nearby *traversed* edges. Graph-agnostic beyond reading
 * traversed edges' own statistics — usable both for "fast way home" pathfinding (#9/#18)
 * and future `.gpx` rating.
 */
@Singleton
class EdgeStatsEstimator @Inject constructor(
    private val repository: MapGraphRepository
) {
    private val spatialIndex = RTreeSpatialIndex()
    private val mutex = Mutex()
    private var traversedByIndex: Map<Int, MapEdge>? = null
    private var traversedBearings: Map<Int, Double>? = null

    suspend fun estimateStatsNear(point: LatLng, bearing: Double): EdgeStats {
        ensureIndex()
        val byIndex = traversedByIndex ?: return fallback()
        val bearings = traversedBearings ?: return fallback()

        val candidates = spatialIndex.queryEdgesNear(
            point.latitude, point.longitude, CyclingConstants.EDGE_STATS_SEARCH_RADIUS_M
        ).mapNotNull { candidate ->
            val idx = candidate.edgeKey.toInt()
            val edge = byIndex[idx] ?: return@mapNotNull null
            val edgeBearing = bearings[idx] ?: return@mapNotNull null
            if (GeoUtils.angleDifference(edgeBearing, bearing) > CyclingConstants.EDGE_STATS_MAX_BEARING_DIFF_DEG) {
                return@mapNotNull null
            }
            edge to candidate.distanceM
        }.sortedBy { it.second }
            .take(CyclingConstants.EDGE_STATS_NEAREST_K)

        if (candidates.isEmpty()) return fallback()

        val weights = candidates.map { (_, distanceM) -> 1.0 / (distanceM + 1.0) }

        return EdgeStats(
            speedP25 = weightedAverage(candidates, weights) { it.speedP25 } ?: CyclingConstants.EDGE_STATS_FALLBACK_SPEED_KMH,
            speedP50 = weightedAverage(candidates, weights) { it.speedMedian } ?: CyclingConstants.EDGE_STATS_FALLBACK_SPEED_KMH,
            speedP75 = weightedAverage(candidates, weights) { it.speedP75 } ?: CyclingConstants.EDGE_STATS_FALLBACK_SPEED_KMH,
            powerP25 = weightedAverage(candidates, weights) { it.powerP25 },
            powerP50 = weightedAverage(candidates, weights) { it.powerMedian },
            powerP75 = weightedAverage(candidates, weights) { it.powerP75 },
            isEstimated = true
        )
    }

    private fun weightedAverage(
        candidates: List<Pair<MapEdge, Double>>,
        weights: List<Double>,
        selector: (MapEdge) -> Double?
    ): Double? {
        var weightedSum = 0.0
        var weightSum = 0.0
        candidates.forEachIndexed { i, (edge, _) ->
            val value = selector(edge) ?: return@forEachIndexed
            weightedSum += value * weights[i]
            weightSum += weights[i]
        }
        return if (weightSum > 0.0) weightedSum / weightSum else null
    }

    private fun fallback(): EdgeStats = EdgeStats(
        speedP25 = CyclingConstants.EDGE_STATS_FALLBACK_SPEED_KMH,
        speedP50 = CyclingConstants.EDGE_STATS_FALLBACK_SPEED_KMH,
        speedP75 = CyclingConstants.EDGE_STATS_FALLBACK_SPEED_KMH,
        powerP25 = null,
        powerP50 = null,
        powerP75 = null,
        isEstimated = true
    )

    private suspend fun ensureIndex() {
        mutex.withLock {
            if (traversedByIndex != null) return
            val traversed = repository.getTraversedEdges().first()
            val nodes = repository.getAllNodes().first().associateBy { it.id }

            val byIndex = mutableMapOf<Int, MapEdge>()
            val bearings = mutableMapOf<Int, Double>()
            traversed.forEachIndexed { idx, edge ->
                byIndex[idx] = edge
                bearings[idx] = edgeBearing(edge, nodes)
            }

            spatialIndex.rebuildIndex(traversed, nodes)
            traversedByIndex = byIndex
            traversedBearings = bearings
        }
    }

    private fun edgeBearing(edge: MapEdge, nodes: Map<Long, MapNode>): Double {
        val decoded = PolylineDecoder.decode(edge.geometryEncoded)
        if (decoded.size >= 2) {
            return GeoUtils.computeBearing(
                decoded.first().latitude, decoded.first().longitude,
                decoded.last().latitude, decoded.last().longitude
            )
        }
        val from = nodes[edge.fromNode]
        val to = nodes[edge.toNode]
        return if (from != null && to != null) {
            GeoUtils.computeBearing(from.lat, from.lon, to.lat, to.lon)
        } else 0.0
    }
}
