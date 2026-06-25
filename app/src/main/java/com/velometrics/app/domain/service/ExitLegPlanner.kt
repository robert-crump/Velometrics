package com.velometrics.app.domain.service

import android.util.Log
import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.util.GeoUtils
import java.util.PriorityQueue

data class ExitLegConfig(
    val bboxHalfSizeM: Double = 2500.0,
    val fallbackThresholds: List<Double> = listOf(0.25, 0.10, 0.05, 0.0),
    val minCorridorDistM: Double = 3000.0,
    val maxCorridorDistM: Double = 8000.0,
    val maxCandidateCorridors: Int = 5,
    val softCapFraction: Double = 0.15,
    val edgeFallbackBudgetFraction: Double = 1.0 / 6.0,
    val maxAStarIterations: Int = 50_000,
)

data class ExitLeg(
    val edges: List<MapEdge>,
    val distanceM: Double,
    val targetNode: Long,
)

object ExitLegPlanner {

    private const val TAG = "ExitLegPlanner"
    private const val DIRECTION_CONE_HALF_ANGLE = 45.0
    private const val FALLBACK_DIRECTION_CONE = 90.0

    suspend fun computeExitLeg(
        homeLat: Double,
        homeLon: Double,
        direction: RideDirection?,
        corridors: List<Corridor>,
        remainingBudgetM: Double,
        repository: MapGraphRepository,
        config: ExitLegConfig = ExitLegConfig(),
    ): ExitLeg? {
        val bbox = buildBbox(homeLat, homeLon, config.bboxHalfSizeM)
        val allEdges = repository.getEdgesNear(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon)
        val allNodes = repository.getNodesNear(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon)
        if (allEdges.isEmpty() || allNodes.isEmpty()) {
            Log.d(TAG, "computeExitLeg: no edges or nodes near home")
            return null
        }
        val nodeMap = allNodes.associateBy { it.id }

        val maxTraversal = allEdges.mapNotNull { it.traversalCount }.maxOrNull() ?: 0
        Log.d(TAG, "computeExitLeg: ${allEdges.size} edges, maxTraversal=$maxTraversal")

        val homeNode = findNearestNode(allNodes, homeLat, homeLon) ?: return null

        val candidateCorridors = findCandidateCorridors(
            corridors, homeLat, homeLon, direction, config,
        )

        if (candidateCorridors.isEmpty()) {
            Log.d(TAG, "computeExitLeg: no candidate corridors, trying edge-based fallback")
            return computeEdgeFallback(
                homeNode, homeLat, homeLon, direction, allEdges, nodeMap,
                remainingBudgetM, corridors, config,
            )
        }

        val nearestDist = candidateCorridors.minOf {
            GeoUtils.haversineDistance(homeLat, homeLon, it.centroidLat, it.centroidLon)
        }
        if (nearestDist > remainingBudgetM * config.softCapFraction) {
            Log.d(TAG, "computeExitLeg: soft cap - nearest corridor ${nearestDist.toInt()}m > ${(remainingBudgetM * config.softCapFraction).toInt()}m budget cap")
            return null
        }

        for (thresholdFraction in config.fallbackThresholds) {
            val threshold = (maxTraversal * thresholdFraction).toInt()
            val filteredEdges = if (threshold > 0) {
                allEdges.filter { (it.traversalCount ?: 0) >= threshold }
            } else {
                allEdges
            }
            if (filteredEdges.isEmpty()) continue

            val edgeIndex = buildEdgeIndex(filteredEdges)

            val results = candidateCorridors.mapNotNull { corridor ->
                val targetNode = nodeMap[corridor.entryNode] ?: return@mapNotNull null
                val path = aStarOnEdges(
                    filteredEdges, nodeMap, edgeIndex,
                    homeNode.id, corridor.entryNode, targetNode, config,
                ) ?: return@mapNotNull null
                val distance = path.sumOf { it.lengthM }
                val score = scoreExitLeg(path)
                Triple(path, distance, score) to corridor
            }

            if (results.isNotEmpty()) {
                val best = results.maxByOrNull { it.first.third }!!
                val (path, distance, _) = best.first
                val corridor = best.second
                Log.d(TAG, "computeExitLeg: path to corridor ${corridor.id} at threshold=$thresholdFraction, ${path.size} edges, ${distance.toInt()}m")
                return ExitLeg(edges = path, distanceM = distance, targetNode = corridor.entryNode)
            }
            Log.d(TAG, "computeExitLeg: no path at threshold=$thresholdFraction ($threshold)")
        }

        return null
    }

    suspend fun computeReturnLeg(
        fromNode: Long,
        fromLat: Double,
        fromLon: Double,
        homeLat: Double,
        homeLon: Double,
        repository: MapGraphRepository,
        config: ExitLegConfig = ExitLegConfig(),
    ): ExitLeg? {
        val bbox = buildCoveringBbox(fromLat, fromLon, homeLat, homeLon, config.bboxHalfSizeM)
        val allEdges = repository.getEdgesNear(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon)
        val allNodes = repository.getNodesNear(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon)
        if (allEdges.isEmpty() || allNodes.isEmpty()) {
            Log.d(TAG, "computeReturnLeg: no edges or nodes")
            return null
        }
        val nodeMap = allNodes.associateBy { it.id }

        val homeNode = findNearestNode(allNodes, homeLat, homeLon) ?: return null
        val maxTraversal = allEdges.mapNotNull { it.traversalCount }.maxOrNull() ?: 0

        for (thresholdFraction in config.fallbackThresholds) {
            val threshold = (maxTraversal * thresholdFraction).toInt()
            val filteredEdges = if (threshold > 0) {
                allEdges.filter { (it.traversalCount ?: 0) >= threshold }
            } else {
                allEdges
            }
            if (filteredEdges.isEmpty()) continue

            val edgeIndex = buildEdgeIndex(filteredEdges)
            val path = aStarOnEdges(
                filteredEdges, nodeMap, edgeIndex,
                fromNode, homeNode.id, homeNode, config,
            )

            if (path != null) {
                val distance = path.sumOf { it.lengthM }
                Log.d(TAG, "computeReturnLeg: path at threshold=$thresholdFraction, ${path.size} edges, ${distance.toInt()}m")
                return ExitLeg(edges = path, distanceM = distance, targetNode = homeNode.id)
            }
            Log.d(TAG, "computeReturnLeg: no path at threshold=$thresholdFraction ($threshold)")
        }

        return null
    }

    private fun aStarOnEdges(
        edges: List<MapEdge>,
        nodeMap: Map<Long, MapNode>,
        edgesByFromNode: Map<Long, List<Int>>,
        fromNode: Long,
        toNode: Long,
        targetNode: MapNode,
        config: ExitLegConfig,
    ): List<MapEdge>? {
        fun heuristic(edgeIdx: Int): Double {
            val endNode = nodeMap[edges[edgeIdx].toNode] ?: return 0.0
            return GeoUtils.haversineDistance(endNode.lat, endNode.lon, targetNode.lat, targetNode.lon)
        }

        data class AStarEntry(val idx: Int, val fCost: Double)

        val openSet = PriorityQueue<AStarEntry>(compareBy { it.fCost })
        val gCosts = HashMap<Int, Double>()
        val cameFrom = HashMap<Int, Int>()
        val closedSet = HashSet<Int>()

        val startIndices = edgesByFromNode[fromNode] ?: return null
        for (idx in startIndices) {
            val cost = edges[idx].lengthM
            gCosts[idx] = cost
            openSet.add(AStarEntry(idx, cost + heuristic(idx)))
        }

        var iterations = 0
        while (openSet.isNotEmpty() && iterations < config.maxAStarIterations) {
            iterations++
            val current = openSet.poll() ?: break
            if (current.idx in closedSet) continue
            closedSet.add(current.idx)

            if (edges[current.idx].toNode == toNode) {
                val path = mutableListOf<Int>()
                var edgeIdx = current.idx
                while (true) {
                    path.add(edgeIdx)
                    edgeIdx = cameFrom[edgeIdx] ?: break
                }
                path.reverse()
                return path.map { edges[it] }
            }

            val currentG = gCosts[current.idx] ?: continue
            val successors = edgesByFromNode[edges[current.idx].toNode] ?: continue
            for (succIdx in successors) {
                if (succIdx in closedSet) continue
                if (succIdx == current.idx) continue
                val newG = currentG + edges[succIdx].lengthM
                val bestG = gCosts[succIdx]
                if (bestG != null && newG >= bestG) continue
                gCosts[succIdx] = newG
                cameFrom[succIdx] = current.idx
                openSet.add(AStarEntry(succIdx, newG + heuristic(succIdx)))
            }
        }
        return null
    }

    internal fun scoreExitLeg(path: List<MapEdge>): Double {
        if (path.isEmpty()) return 0.0
        val totalDist = path.sumOf { it.lengthM }
        if (totalDist == 0.0) return 0.0
        return path.sumOf { (it.traversalCount ?: 0).toDouble() } / totalDist
    }

    internal fun findCandidateCorridors(
        corridors: List<Corridor>,
        homeLat: Double,
        homeLon: Double,
        direction: RideDirection?,
        config: ExitLegConfig,
    ): List<Corridor> {
        val inRange = corridors.filter { c ->
            val dist = GeoUtils.haversineDistance(homeLat, homeLon, c.centroidLat, c.centroidLon)
            dist in config.minCorridorDistM..config.maxCorridorDistM
        }

        val directed = if (direction != null) {
            inRange.filter { c ->
                val bearing = GeoUtils.computeBearing(homeLat, homeLon, c.centroidLat, c.centroidLon)
                GeoUtils.bearingDifference(bearing, direction.bearingCenter) <= DIRECTION_CONE_HALF_ANGLE
            }
        } else {
            inRange
        }

        return directed
            .sortedBy { GeoUtils.haversineDistance(homeLat, homeLon, it.centroidLat, it.centroidLon) }
            .take(config.maxCandidateCorridors)
    }

    private fun computeEdgeFallback(
        homeNode: MapNode,
        homeLat: Double,
        homeLon: Double,
        direction: RideDirection?,
        edges: List<MapEdge>,
        nodeMap: Map<Long, MapNode>,
        remainingBudgetM: Double,
        corridors: List<Corridor>,
        config: ExitLegConfig,
    ): ExitLeg? {
        val budgetM = remainingBudgetM * config.edgeFallbackBudgetFraction
        val traversedEdges = edges.filter { (it.traversalCount ?: 0) > 0 }
        if (traversedEdges.isEmpty()) return null

        val edgeIndex = buildEdgeIndex(traversedEdges)
        val path = mutableListOf<MapEdge>()
        val visited = mutableSetOf(homeNode.id)
        var currentNode = homeNode.id
        var totalDist = 0.0

        while (totalDist < budgetM) {
            val candidates = edgeIndex[currentNode] ?: break
            val available = candidates
                .map { traversedEdges[it] }
                .filter { it.toNode !in visited }

            val directional = if (direction != null && available.size > 1) {
                available.filter { edge ->
                    val toNode = nodeMap[edge.toNode] ?: return@filter true
                    val bearing = GeoUtils.computeBearing(homeLat, homeLon, toNode.lat, toNode.lon)
                    GeoUtils.bearingDifference(bearing, direction.bearingCenter) <= FALLBACK_DIRECTION_CONE
                }.ifEmpty { available }
            } else {
                available
            }

            val best = directional.maxByOrNull { it.traversalCount ?: 0 } ?: break
            if (totalDist + best.lengthM > budgetM) break

            path.add(best)
            visited.add(best.toNode)
            currentNode = best.toNode
            totalDist += best.lengthM
        }

        if (path.isEmpty()) return null

        val endNode = nodeMap[currentNode]
        val handoffCorridor = if (endNode != null) {
            corridors.minByOrNull {
                GeoUtils.haversineDistance(endNode.lat, endNode.lon, it.centroidLat, it.centroidLon)
            }
        } else null

        val targetNode = handoffCorridor?.entryNode ?: currentNode
        Log.d(TAG, "computeEdgeFallback: ${path.size} edges, ${totalDist.toInt()}m, handoff corridor=${handoffCorridor?.id}")
        return ExitLeg(edges = path, distanceM = totalDist, targetNode = targetNode)
    }

    internal fun buildEdgeIndex(edges: List<MapEdge>): Map<Long, List<Int>> {
        val result = HashMap<Long, MutableList<Int>>(edges.size)
        edges.forEachIndexed { idx, edge ->
            result.getOrPut(edge.fromNode) { mutableListOf() }.add(idx)
        }
        return result
    }

    internal fun findNearestNode(nodes: List<MapNode>, lat: Double, lon: Double): MapNode? {
        return nodes.minByOrNull { GeoUtils.haversineDistance(lat, lon, it.lat, it.lon) }
    }

    internal fun buildBbox(lat: Double, lon: Double, halfSizeM: Double): BoundingBox {
        val latBuffer = GeoUtils.metersToLat(halfSizeM)
        val lonBuffer = GeoUtils.metersToLon(halfSizeM, lat)
        return BoundingBox(
            minLat = lat - latBuffer,
            minLon = lon - lonBuffer,
            maxLat = lat + latBuffer,
            maxLon = lon + lonBuffer,
        )
    }

    internal fun buildCoveringBbox(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double,
        marginM: Double,
    ): BoundingBox {
        val minLat = minOf(lat1, lat2)
        val maxLat = maxOf(lat1, lat2)
        val minLon = minOf(lon1, lon2)
        val maxLon = maxOf(lon1, lon2)
        val midLat = (minLat + maxLat) / 2.0
        val latBuffer = GeoUtils.metersToLat(marginM)
        val lonBuffer = GeoUtils.metersToLon(marginM, midLat)
        return BoundingBox(
            minLat = minLat - latBuffer,
            minLon = minLon - lonBuffer,
            maxLat = maxLat + latBuffer,
            maxLon = maxLon + lonBuffer,
        )
    }
}
