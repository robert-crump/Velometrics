package com.velometrics.app.domain.service

import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.GeoUtils
import com.velometrics.app.util.PolylineDecoder
import kotlinx.coroutines.flow.first
import org.maplibre.android.geometry.LatLng
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Singleton

data class FastWayHomeResult(
    val path: List<MapEdge>,
    val slow: RideEstimate,
    val avg: RideEstimate,
    val fast: RideEstimate,
    val anySegmentsEstimated: Boolean
)

private data class Connectivity(
    val edgeByIndex: Map<Int, MapEdge>,
    val adjacency: Map<Int, List<Int>>,
    val edgeStartPoints: Map<Int, LatLng>,
    val edgeEndPoints: Map<Int, LatLng>,
    val edgeStatsByIndex: Map<Int, EdgeStats>
)

/**
 * Finds the time-optimal route from a given location to the user's configured home location
 * and scores it via [RideEstimator]. Uses its own A* search ([aStarToHome]) rather than
 * [RoutePlanner.aStarReturn] — that search is distance-based and tied to route generation,
 * while this one minimizes travel time using per-edge speed estimates from [EdgeStatsEstimator].
 */
@Singleton
class FastWayHomeService @Inject constructor(
    private val repository: MapGraphRepository,
    private val edgeStatsEstimator: EdgeStatsEstimator,
    private val userSettingsRepository: UserSettingsRepository
) {

    suspend fun findFastWayHome(start: LatLng): FastWayHomeResult? {
        val targetLat = userSettingsRepository.homeLat.first()
        val targetLon = userSettingsRepository.homeLon.first()

        // The full road graph is 500K+ edges — far too large to load in one go (decoding every
        // polyline and parsing its metadata blows the heap, #46). Query only the slice of the
        // graph spanning the start and home locations, with a margin generous enough to cover
        // routes that first move away from home before heading back towards it.
        val bbox = TrackGeometryUtils.computeBoundingBox(
            listOf(start, LatLng(targetLat, targetLon)),
            CyclingConstants.FAST_WAY_HOME_BBOX_MARGIN_M
        )
        val connectivity = buildConnectivity(bbox)

        val startCandidates = connectivity.edgeStartPoints.entries.filter { (_, pt) ->
            GeoUtils.haversineDistance(start.latitude, start.longitude, pt.latitude, pt.longitude) <=
                CyclingConstants.ROUTE_START_RADIUS_M
        }
        if (startCandidates.isEmpty()) return null

        val fromIdx = startCandidates.minByOrNull {
            GeoUtils.haversineDistance(start.latitude, start.longitude, it.value.latitude, it.value.longitude)
        }?.key ?: return null

        val pathIndices = aStarToHome(connectivity, fromIdx, targetLat, targetLon) ?: return null
        val fullIndices = listOf(fromIdx) + pathIndices

        val edges = connectivity.edgeByIndex
        val statsByIndex = connectivity.edgeStatsByIndex

        val path = fullIndices.mapNotNull { edges[it] }
        if (path.isEmpty()) return null

        val segments = fullIndices.mapNotNull { idx ->
            val edge = edges[idx] ?: return@mapNotNull null
            val edgeStats = statsByIndex[idx] ?: return@mapNotNull null
            ScoredSegment(
                lengthM = edge.lengthM,
                speedP25 = edgeStats.speedP25, speedP50 = edgeStats.speedP50, speedP75 = edgeStats.speedP75,
                powerP25 = edgeStats.powerP25, powerP50 = edgeStats.powerP50, powerP75 = edgeStats.powerP75,
                isEstimated = edgeStats.isEstimated
            )
        }

        return FastWayHomeResult(
            path = path,
            slow = RideEstimator.estimateRide(segments, Percentile.SLOW),
            avg = RideEstimator.estimateRide(segments, Percentile.AVG),
            fast = RideEstimator.estimateRide(segments, Percentile.FAST),
            anySegmentsEstimated = segments.any { it.isEstimated }
        )
    }

    private suspend fun buildConnectivity(bbox: BoundingBox): Connectivity {
        val edgesList = repository.getEdgesNear(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon)
        val nodesList = repository.getNodesNear(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon)
        val nMap = nodesList.associateBy { it.id }

        val edgesByIdx = mutableMapOf<Int, MapEdge>()
        val startPts = mutableMapOf<Int, LatLng>()
        val endPts = mutableMapOf<Int, LatLng>()
        val bearings = mutableMapOf<Int, Double>()

        edgesList.forEachIndexed { idx, edge ->
            edgesByIdx[idx] = edge
            val decoded = PolylineDecoder.decode(edge.geometryEncoded)
            if (decoded.size >= 2) {
                startPts[idx] = decoded.first()
                endPts[idx] = decoded.last()
                bearings[idx] = GeoUtils.computeBearing(
                    decoded.first().latitude, decoded.first().longitude,
                    decoded.last().latitude, decoded.last().longitude
                )
            } else {
                val fromNode = nMap[edge.fromNode]
                val toNode = nMap[edge.toNode]
                if (fromNode != null && toNode != null) {
                    startPts[idx] = LatLng(fromNode.lat, fromNode.lon)
                    endPts[idx] = LatLng(toNode.lat, toNode.lon)
                    bearings[idx] = GeoUtils.computeBearing(fromNode.lat, fromNode.lon, toNode.lat, toNode.lon)
                }
            }
        }

        // Build adjacency from graph topology (fromNode -> toNode), same as RoutePlanner
        val edgesByFromNode = mutableMapOf<Long, MutableList<Int>>()
        edgesList.forEachIndexed { idx, edge ->
            edgesByFromNode.getOrPut(edge.fromNode) { mutableListOf() }.add(idx)
        }
        val adj = mutableMapOf<Int, MutableList<Int>>()
        edgesList.forEachIndexed { idx, edge ->
            val successors = edgesByFromNode[edge.toNode] ?: return@forEachIndexed
            val filtered = successors.filter { it != idx }
            if (filtered.isNotEmpty()) adj[idx] = filtered.toMutableList()
        }

        val statsByIdx = mutableMapOf<Int, EdgeStats>()
        edgesList.forEachIndexed { idx, edge ->
            statsByIdx[idx] = statsFor(idx, edge, endPts, bearings)
        }

        return Connectivity(
            edgeByIndex = edgesByIdx,
            adjacency = adj,
            edgeStartPoints = startPts,
            edgeEndPoints = endPts,
            edgeStatsByIndex = statsByIdx
        )
    }

    /**
     * Real traversed stats are used directly; everything else (untraversed or data-sparse
     * edges) is filled in via [EdgeStatsEstimator], which itself falls back to a flat
     * 15 km/h estimate when no nearby traversed edges match.
     */
    private suspend fun statsFor(
        idx: Int,
        edge: MapEdge,
        endPts: Map<Int, LatLng>,
        bearings: Map<Int, Double>
    ): EdgeStats {
        val speedP25 = edge.speedP25
        val speedP50 = edge.speedMedian
        val speedP75 = edge.speedP75
        if (edge.isTraversed && speedP25 != null && speedP50 != null && speedP75 != null) {
            return EdgeStats(
                speedP25 = speedP25, speedP50 = speedP50, speedP75 = speedP75,
                powerP25 = edge.powerP25, powerP50 = edge.powerMedian, powerP75 = edge.powerP75,
                isEstimated = false
            )
        }

        val point = endPts[idx] ?: return EdgeStats(
            speedP25 = CyclingConstants.EDGE_STATS_FALLBACK_SPEED_KMH,
            speedP50 = CyclingConstants.EDGE_STATS_FALLBACK_SPEED_KMH,
            speedP75 = CyclingConstants.EDGE_STATS_FALLBACK_SPEED_KMH,
            powerP25 = null, powerP50 = null, powerP75 = null,
            isEstimated = true
        )
        return edgeStatsEstimator.estimateStatsNear(point, bearings[idx] ?: 0.0)
    }

    /**
     * A* search minimizing travel time (cost = lengthM / speedP50), independent from
     * [RoutePlanner.aStarReturn] (which is distance-based and must stay untouched). The
     * heuristic converts remaining distance to a lower-bound time using an optimistic max
     * speed, keeping it admissible for the time-based cost.
     */
    private fun aStarToHome(
        connectivity: Connectivity,
        fromIdx: Int,
        targetLat: Double,
        targetLon: Double
    ): List<Int>? {
        val adj = connectivity.adjacency
        val edges = connectivity.edgeByIndex
        val stats = connectivity.edgeStatsByIndex
        val edgeEndPoints = connectivity.edgeEndPoints

        val endPt = edgeEndPoints[fromIdx] ?: return null

        data class Node(val idx: Int, val gCost: Double, val fCost: Double)

        fun timeHeuristicSec(lat: Double, lon: Double): Double {
            val distM = GeoUtils.haversineDistance(lat, lon, targetLat, targetLon)
            return distM / (CyclingConstants.GPS_IMPLIED_MAX_SPEED_KMH / 3.6)
        }

        val openSet = PriorityQueue<Node>(compareBy { it.fCost })
        val gCosts = mutableMapOf<Int, Double>()
        val cameFrom = mutableMapOf<Int, Int>()
        val closedSet = mutableSetOf<Int>()

        val h0 = timeHeuristicSec(endPt.latitude, endPt.longitude)
        gCosts[fromIdx] = 0.0
        openSet.add(Node(fromIdx, 0.0, h0))

        var iterations = 0
        val maxIterations = 50000

        while (openSet.isNotEmpty() && iterations < maxIterations) {
            iterations++
            val current = openSet.poll() ?: break
            val currentIdx = current.idx

            if (currentIdx in closedSet) continue
            closedSet.add(currentIdx)

            val curEndPt = edgeEndPoints[currentIdx]
            if (curEndPt != null) {
                val distToTarget = GeoUtils.haversineDistance(
                    curEndPt.latitude, curEndPt.longitude, targetLat, targetLon
                )
                if (distToTarget <= CyclingConstants.ROUTE_START_RADIUS_M && currentIdx != fromIdx) {
                    val path = mutableListOf<Int>()
                    var node = currentIdx
                    while (node in cameFrom) {
                        path.add(node)
                        node = cameFrom[node]!!
                    }
                    path.reverse()
                    return path
                }
            }

            val successors = adj[currentIdx] ?: continue
            val currentG = gCosts[currentIdx] ?: continue

            for (succIdx in successors) {
                if (succIdx in closedSet) continue
                val succEdge = edges[succIdx] ?: continue
                val succStats = stats[succIdx] ?: continue

                val speedMs = succStats.speedP50 / 3.6
                val edgeCost = if (speedMs > 0.0) succEdge.lengthM / speedMs else Double.MAX_VALUE
                val newG = currentG + edgeCost

                val bestG = gCosts[succIdx]
                if (bestG != null && newG >= bestG) continue

                gCosts[succIdx] = newG
                cameFrom[succIdx] = currentIdx
                val succEnd = edgeEndPoints[succIdx]
                val h = if (succEnd != null) timeHeuristicSec(succEnd.latitude, succEnd.longitude) else 0.0
                openSet.add(Node(succIdx, newG, newG + h))
            }
        }

        return null
    }
}
