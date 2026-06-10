package com.velometrics.app.domain.service

import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.GeoUtils
import com.velometrics.app.util.PolylineDecoder
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.geometry.LatLng
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Singleton

data class FastWayHomeResult(
    val path: List<MapEdge>,
    val totalDistanceM: Double,
    val coveragePercent: Int,
    val slow: RideEstimate?,
    val avg: RideEstimate?,
    val fast: RideEstimate?
)

private data class Connectivity(
    val edgeByIndex: Map<Int, MapEdge>,
    val adjacency: Map<Int, List<Int>>,
    val edgeStartPoints: Map<Int, LatLng>,
    val edgeEndPoints: Map<Int, LatLng>
)

/**
 * Finds the *shortest* route from a given location to the user's configured home location
 * (#47 — replaces the old time-optimal search, which sometimes produced "pretzel" routes when
 * GPS noise or map-matching errors skewed an edge's speed metadata) and scores it via
 * [RideEstimator]. Uses its own A* search ([aStarToHome]) rather than [RoutePlanner.aStarReturn]
 * — that search is direction/novelty-scored and tied to route generation, while this one is a
 * pure shortest-path search.
 */
@Singleton
class FastWayHomeService @Inject constructor(
    private val repository: MapGraphRepository,
    private val userSettingsRepository: UserSettingsRepository
) {

    suspend fun findFastWayHome(
        currentLocation: StateFlow<LatLng?>,
        locationAccuracy: StateFlow<Float?>
    ): FastWayHomeResult? {
        val start = withTimeoutOrNull(CyclingConstants.FAST_WAY_HOME_GPS_WAIT_TIMEOUT_MS) {
            combine(currentLocation, locationAccuracy) { loc, accuracy -> loc to accuracy }
                .first { (loc, accuracy) ->
                    loc != null && accuracy != null && accuracy <= CyclingConstants.FAST_WAY_HOME_GPS_ACCURACY_THRESHOLD_M
                }
        }?.first ?: currentLocation.value ?: return null

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
        val path = fullIndices.mapNotNull { edges[it] }
        if (path.isEmpty()) return null

        val totalDistanceM = path.sumOf { it.lengthM }
        val coveredLengthM = path.filter { it.speedP25 != null && it.speedMedian != null && it.speedP75 != null }
            .sumOf { it.lengthM }
        val coveragePercent = if (totalDistanceM > 0) {
            Math.round(coveredLengthM / totalDistanceM * 100.0).toInt()
        } else 0

        val segments = path.map { edge ->
            ScoredSegment(
                lengthM = edge.lengthM,
                speedP25 = edge.speedP25, speedP50 = edge.speedMedian, speedP75 = edge.speedP75,
                powerP25 = edge.powerP25, powerP50 = edge.powerMedian, powerP75 = edge.powerP75
            )
        }

        return FastWayHomeResult(
            path = path,
            totalDistanceM = totalDistanceM,
            coveragePercent = coveragePercent,
            slow = RideEstimator.estimateRide(segments, Percentile.SLOW),
            avg = RideEstimator.estimateRide(segments, Percentile.AVG),
            fast = RideEstimator.estimateRide(segments, Percentile.FAST)
        )
    }

    private suspend fun buildConnectivity(bbox: BoundingBox): Connectivity {
        val edgesList = repository.getEdgesNear(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon)
        val nodesList = repository.getNodesNear(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon)
        val nMap = nodesList.associateBy { it.id }

        val edgesByIdx = mutableMapOf<Int, MapEdge>()
        val startPts = mutableMapOf<Int, LatLng>()
        val endPts = mutableMapOf<Int, LatLng>()

        edgesList.forEachIndexed { idx, edge ->
            edgesByIdx[idx] = edge
            val decoded = PolylineDecoder.decode(edge.geometryEncoded)
            if (decoded.size >= 2) {
                startPts[idx] = decoded.first()
                endPts[idx] = decoded.last()
            } else {
                val fromNode = nMap[edge.fromNode]
                val toNode = nMap[edge.toNode]
                if (fromNode != null && toNode != null) {
                    startPts[idx] = LatLng(fromNode.lat, fromNode.lon)
                    endPts[idx] = LatLng(toNode.lat, toNode.lon)
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

        return Connectivity(
            edgeByIndex = edgesByIdx,
            adjacency = adj,
            edgeStartPoints = startPts,
            edgeEndPoints = endPts
        )
    }

    /**
     * Pure shortest-path A* search: cost = edge length, heuristic = haversine distance from the
     * edge's end point to home. Both are in meters, so the heuristic is admissible.
     */
    private fun aStarToHome(
        connectivity: Connectivity,
        fromIdx: Int,
        targetLat: Double,
        targetLon: Double
    ): List<Int>? {
        val adj = connectivity.adjacency
        val edges = connectivity.edgeByIndex
        val edgeEndPoints = connectivity.edgeEndPoints

        val endPt = edgeEndPoints[fromIdx] ?: return null

        data class Node(val idx: Int, val gCost: Double, val fCost: Double)

        fun heuristicM(lat: Double, lon: Double): Double =
            GeoUtils.haversineDistance(lat, lon, targetLat, targetLon)

        val openSet = PriorityQueue<Node>(compareBy { it.fCost })
        val gCosts = mutableMapOf<Int, Double>()
        val cameFrom = mutableMapOf<Int, Int>()
        val closedSet = mutableSetOf<Int>()

        val h0 = heuristicM(endPt.latitude, endPt.longitude)
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

                val newG = currentG + succEdge.lengthM

                val bestG = gCosts[succIdx]
                if (bestG != null && newG >= bestG) continue

                gCosts[succIdx] = newG
                cameFrom[succIdx] = currentIdx
                val succEnd = edgeEndPoints[succIdx]
                val h = if (succEnd != null) heuristicM(succEnd.latitude, succEnd.longitude) else 0.0
                openSet.add(Node(succIdx, newG, newG + h))
            }
        }

        return null
    }
}
