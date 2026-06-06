package com.velometrics.app.domain.service

import android.util.Log
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
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.random.Random

@Singleton
class RoutePlanner @Inject constructor(
    private val repository: MapGraphRepository,
    private val spatialIndex: RTreeSpatialIndex
) {

    data class RouteResult(
        val edges: List<MapEdge>,
        val totalDistanceM: Double,
        val estimatedDurationSec: Double,
        val turnCount: Int,
        val avgSpeedKmh: Double,
        val score: Double
    )

    private val mutex = Mutex()
    private var edgeList: List<MapEdge>? = null
    private var edgeByIndex: Map<Int, MapEdge>? = null
    private var adjacency: Map<Int, List<Int>>? = null
    private var nodeMap: Map<Long, MapNode>? = null
    private var edgeStartPoints: Map<Int, LatLng>? = null
    private var edgeEndPoints: Map<Int, LatLng>? = null
    private var edgeBearings: Map<Int, Double>? = null
    private var maxTraversalCount: Int = 1

    companion object {
        private const val TAG = "RoutePlanner"
    }

    suspend fun invalidate() {
        mutex.withLock {
            edgeList = null
            edgeByIndex = null
            adjacency = null
            nodeMap = null
            edgeStartPoints = null
            edgeEndPoints = null
            edgeBearings = null
            Log.d(TAG, "Connectivity cache invalidated")
        }
    }

    suspend fun generateBestRoute(
        startLat: Double,
        startLon: Double,
        targetDistanceM: Double
    ): RouteResult? {
        ensureConnectivity()

        val edges = edgeByIndex ?: return null
        if (edges.size < CyclingConstants.ROUTE_MIN_CONFIRMED_EDGES) {
            Log.d(TAG, "Not enough edges: ${edges.size}")
            return null
        }

        val candidates = mutableListOf<RouteResult>()
        for (i in 0 until CyclingConstants.ROUTE_MAX_CANDIDATES) {
            val random = Random(System.nanoTime() + i)
            val result = generateRoute(startLat, startLon, targetDistanceM, random)
            if (result != null) {
                candidates.add(result)
            }
        }

        if (candidates.isEmpty()) return null

        val tight = candidates.filter { r ->
            abs(r.totalDistanceM - targetDistanceM) / targetDistanceM <= CyclingConstants.ROUTE_DISTANCE_TOLERANCE_TIGHT
        }
        if (tight.isNotEmpty()) return tight.maxByOrNull { it.score }

        val relaxed = candidates.filter { r ->
            abs(r.totalDistanceM - targetDistanceM) / targetDistanceM <= CyclingConstants.ROUTE_DISTANCE_TOLERANCE_RELAXED
        }
        if (relaxed.isNotEmpty()) return relaxed.maxByOrNull { it.score }

        return candidates.minByOrNull { abs(it.totalDistanceM - targetDistanceM) }
    }

    private suspend fun ensureConnectivity() {
        mutex.withLock {
            if (edgeByIndex != null && adjacency != null) return
            buildConnectivity()
        }
    }

    private suspend fun buildConnectivity() {
        Log.d(TAG, "Building connectivity graph...")
        val allEdges = repository.getAllEdges().first()
        val allNodes = repository.getAllNodes().first()

        val nMap = allNodes.associateBy { it.id }
        nodeMap = nMap

        val edgesByIdx = mutableMapOf<Int, MapEdge>()
        val startPts = mutableMapOf<Int, LatLng>()
        val endPts = mutableMapOf<Int, LatLng>()
        val bearings = mutableMapOf<Int, Double>()
        var maxCount = 1

        // Build edge index and decode geometry endpoints
        allEdges.forEachIndexed { idx, edge ->
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
            val tc = edge.traversalCount ?: 0
            if (tc > maxCount) maxCount = tc
        }

        // Build adjacency from graph topology (fromNode/toNode)
        // Edges sharing a toNode -> fromNode connection are adjacent
        val edgesByFromNode = mutableMapOf<Long, MutableList<Int>>()
        allEdges.forEachIndexed { idx, edge ->
            edgesByFromNode.getOrPut(edge.fromNode) { mutableListOf() }.add(idx)
        }

        val adj = mutableMapOf<Int, MutableList<Int>>()
        allEdges.forEachIndexed { idx, edge ->
            val successors = edgesByFromNode[edge.toNode] ?: return@forEachIndexed
            val filtered = successors.filter { it != idx }
            if (filtered.isNotEmpty()) {
                adj[idx] = filtered.toMutableList()
            }
        }

        edgeList = allEdges
        edgeByIndex = edgesByIdx
        edgeStartPoints = startPts
        edgeEndPoints = endPts
        edgeBearings = bearings
        adjacency = adj
        maxTraversalCount = maxCount

        // Rebuild spatial index
        spatialIndex.rebuildIndex(allEdges, nMap)

        Log.d(TAG, "Connectivity built: ${allEdges.size} edges, ${adj.size} edges with successors")
    }

    internal fun scoreEdge(
        currentIdx: Int,
        candidateIdx: Int,
        visitedIds: Set<Int>,
        distanceSoFar: Double,
        targetDistance: Double,
        startLat: Double,
        startLon: Double
    ): Double {
        val candidate = edgeByIndex?.get(candidateIdx) ?: return CyclingConstants.ROUTE_MIN_SCORE
        val currentBearing = edgeBearings?.get(currentIdx) ?: 0.0
        val candidateBearing = edgeBearings?.get(candidateIdx) ?: 0.0
        val angleDiff = GeoUtils.angleDifference(currentBearing, candidateBearing)

        val speedKmh = candidate.speedMean ?: 20.0

        // Straight bonus
        val straightBonus = if (angleDiff < CyclingConstants.ROUTE_STRAIGHT_ANGLE_THRESHOLD &&
            speedKmh > CyclingConstants.ROUTE_STRAIGHT_SPEED_THRESHOLD) 1.0 else 0.0

        // Speed score
        val speedScore = min(speedKmh / CyclingConstants.ROUTE_SPEED_CAP, 1.0)

        // Stop penalty
        val stopPenalty = (candidate.stopProbability ?: 0.0).coerceAtMost(1.0)

        // Turn penalty
        val turnPenalty = if (angleDiff > CyclingConstants.ROUTE_TURN_ANGLE_THRESHOLD) {
            (angleDiff / 180.0) * (0.5 + stopPenalty)
        } else 0.0

        // Novelty
        val traversalCount = candidate.traversalCount ?: 0
        val novelty = traversalCount.toDouble() / maxTraversalCount

        // Direction score
        val candidateEnd = edgeEndPoints?.get(candidateIdx)
        val directionScore = if (candidateEnd != null) {
            val bearingToHome = GeoUtils.computeBearing(
                candidateEnd.latitude, candidateEnd.longitude, startLat, startLon
            )
            val cosAngle = cos(Math.toRadians(candidateBearing - bearingToHome))
            val inFirstHalf = distanceSoFar < targetDistance / 2.0
            if (inFirstHalf) -cosAngle else cosAngle
        } else 0.0

        var score = CyclingConstants.ROUTE_STRAIGHT_BONUS_WEIGHT * straightBonus +
                CyclingConstants.ROUTE_SPEED_WEIGHT * speedScore +
                CyclingConstants.ROUTE_STOP_PENALTY_WEIGHT * stopPenalty +
                CyclingConstants.ROUTE_TURN_PENALTY_WEIGHT * turnPenalty +
                CyclingConstants.ROUTE_NOVELTY_WEIGHT * novelty +
                CyclingConstants.ROUTE_DIRECTION_WEIGHT * directionScore

        score = maxOf(score, CyclingConstants.ROUTE_MIN_SCORE)

        if (candidateIdx in visitedIds) {
            score *= CyclingConstants.ROUTE_REVISIT_PENALTY
        }

        return score
    }

    private fun weightedRandomSelect(
        candidates: List<Int>,
        scores: List<Double>,
        random: Random
    ): Int? {
        if (candidates.isEmpty()) return null
        val total = scores.sum()
        if (total <= 0.0) return candidates[random.nextInt(candidates.size)]

        var threshold = random.nextDouble() * total
        for (i in candidates.indices) {
            threshold -= scores[i]
            if (threshold <= 0.0) return candidates[i]
        }
        return candidates.last()
    }

    private fun randomWalk(
        startEdges: List<Int>,
        targetDistanceM: Double,
        startLat: Double,
        startLon: Double,
        random: Random
    ): Pair<List<Int>, Double>? {
        val adj = adjacency ?: return null
        val edges = edgeByIndex ?: return null

        if (startEdges.isEmpty()) return null
        val firstIdx = startEdges[random.nextInt(startEdges.size)]
        val firstEdge = edges[firstIdx] ?: return null

        val path = mutableListOf(firstIdx)
        val visited = mutableSetOf(firstIdx)
        var distanceSoFar = firstEdge.lengthM
        var currentIdx = firstIdx
        var consecutiveDeadEnds = 0

        while (true) {
            val currentEdge = edges[currentIdx] ?: break
            val endPt = edgeEndPoints?.get(currentIdx) ?: break

            val distToHome = GeoUtils.haversineDistance(
                endPt.latitude, endPt.longitude, startLat, startLon
            )
            val remainingBudget = targetDistanceM - distanceSoFar
            if (remainingBudget < CyclingConstants.ROUTE_RETURN_BUDGET_FACTOR * distToHome) {
                break
            }

            val successors = adj[currentIdx]
            if (successors.isNullOrEmpty()) {
                consecutiveDeadEnds++
                if (consecutiveDeadEnds >= 3) break
                if (path.size > 1) {
                    path.removeAt(path.size - 1)
                    currentIdx = path.last()
                }
                continue
            }

            consecutiveDeadEnds = 0

            val scores = successors.map { succIdx ->
                scoreEdge(currentIdx, succIdx, visited, distanceSoFar, targetDistanceM, startLat, startLon)
            }

            val nextIdx = weightedRandomSelect(successors, scores, random) ?: break
            val nextEdge = edges[nextIdx] ?: break

            path.add(nextIdx)
            visited.add(nextIdx)
            distanceSoFar += nextEdge.lengthM
            currentIdx = nextIdx
        }

        return Pair(path, distanceSoFar)
    }

    private fun aStarReturn(
        fromIdx: Int,
        startLat: Double,
        startLon: Double
    ): List<Int>? {
        val adj = adjacency ?: return null
        val edges = edgeByIndex ?: return null

        val endPt = edgeEndPoints?.get(fromIdx) ?: return null

        data class Node(val idx: Int, val gCost: Double, val fCost: Double)

        val openSet = PriorityQueue<Node>(compareBy { it.fCost })
        val gCosts = mutableMapOf<Int, Double>()
        val cameFrom = mutableMapOf<Int, Int>()
        val closedSet = mutableSetOf<Int>()

        val h0 = GeoUtils.haversineDistance(endPt.latitude, endPt.longitude, startLat, startLon)
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

            val curEndPt = edgeEndPoints?.get(currentIdx)
            if (curEndPt != null) {
                val distToStart = GeoUtils.haversineDistance(
                    curEndPt.latitude, curEndPt.longitude, startLat, startLon
                )
                if (distToStart <= CyclingConstants.ROUTE_START_RADIUS_M && currentIdx != fromIdx) {
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
                val stopPenalty = (succEdge.stopProbability ?: 0.0).coerceAtMost(1.0)

                val edgeCost = succEdge.lengthM * (1.0 + stopPenalty)
                val newG = currentG + edgeCost

                val bestG = gCosts[succIdx]
                if (bestG != null && newG >= bestG) continue

                gCosts[succIdx] = newG
                cameFrom[succIdx] = currentIdx
                val succEnd = edgeEndPoints?.get(succIdx)
                val h = if (succEnd != null) {
                    GeoUtils.haversineDistance(succEnd.latitude, succEnd.longitude, startLat, startLon)
                } else 0.0
                openSet.add(Node(succIdx, newG, newG + h))
            }
        }

        return null
    }

    private fun generateRoute(
        startLat: Double,
        startLon: Double,
        targetDistanceM: Double,
        random: Random
    ): RouteResult? {
        val edges = edgeByIndex ?: return null
        val startPoints = edgeStartPoints ?: return null

        val startCandidates = startPoints.entries.filter { (_, pt) ->
            val dist = GeoUtils.haversineDistance(startLat, startLon, pt.latitude, pt.longitude)
            dist <= CyclingConstants.ROUTE_START_RADIUS_M
        }.map { it.key }

        if (startCandidates.isEmpty()) {
            Log.d(TAG, "No start edges found within ${CyclingConstants.ROUTE_START_RADIUS_M}m")
            return null
        }

        val walkResult = randomWalk(startCandidates, targetDistanceM, startLat, startLon, random)
            ?: return null
        val (outboundPath, _) = walkResult

        if (outboundPath.isEmpty()) return null

        val returnPath = aStarReturn(outboundPath.last(), startLat, startLon)

        val fullPath = if (returnPath != null) {
            outboundPath + returnPath
        } else {
            outboundPath
        }

        val routeEdges = fullPath.mapNotNull { edges[it] }
        if (routeEdges.isEmpty()) return null

        val totalDistance = routeEdges.sumOf { it.lengthM }
        val weightedSpeedSum = routeEdges.sumOf { (it.speedMean ?: 20.0) * it.lengthM }
        val avgSpeed = if (totalDistance > 0) weightedSpeedSum / totalDistance else 0.0
        val estimatedDuration = if (avgSpeed > 0) (totalDistance / 1000.0) / avgSpeed * 3600.0 else 0.0

        var turnCount = 0
        val bearingsMap = edgeBearings ?: emptyMap()
        for (i in 1 until fullPath.size) {
            val b1 = bearingsMap[fullPath[i - 1]] ?: continue
            val b2 = bearingsMap[fullPath[i]] ?: continue
            val angleDiff = GeoUtils.angleDifference(b1, b2)
            if (angleDiff > CyclingConstants.ROUTE_TURN_ANGLE_THRESHOLD) turnCount++
        }

        val visited = mutableSetOf<Int>()
        var routeScore = 0.0
        for (i in 1 until fullPath.size) {
            visited.add(fullPath[i - 1])
            routeScore += scoreEdge(
                fullPath[i - 1], fullPath[i], visited,
                routeEdges.take(i).sumOf { it.lengthM },
                targetDistanceM, startLat, startLon
            )
        }

        return RouteResult(
            edges = routeEdges,
            totalDistanceM = totalDistance,
            estimatedDurationSec = estimatedDuration,
            turnCount = turnCount,
            avgSpeedKmh = avgSpeed,
            score = routeScore
        )
    }
}
