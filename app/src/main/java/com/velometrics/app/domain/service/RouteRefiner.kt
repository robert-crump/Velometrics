package com.velometrics.app.domain.service

import android.util.Log
import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.domain.repository.RoutingEdge
import com.velometrics.app.util.GeoUtils
import java.util.PriorityQueue

data class RefinedRoute(
    val edges: List<MapEdge>,
    val actualDistanceM: Double,
)

data class RefinerConfig(
    val bboxMarginM: Double = 500.0,
    val maxAStarIterations: Int = 50_000,
)

object RouteRefiner {

    private const val TAG = "RouteRefiner"

    suspend fun refine(
        candidate: CandidateLoop,
        corridorMap: Map<Long, Corridor>,
        repository: MapGraphRepository,
        config: RefinerConfig = RefinerConfig(),
    ): RefinedRoute? {
        val refineStart = System.currentTimeMillis()
        val candidateCorridors = candidate.corridors.mapNotNull { corridorMap[it] }
        if (candidateCorridors.isEmpty()) return null

        val waypoints = buildWaypoints(candidateCorridors)
        Log.d(TAG, "refine: ${candidateCorridors.size} corridors -> ${waypoints.size} waypoints")

        val waypointNodeIds = waypoints.toSet().toLongArray()
        val waypointNodes = repository.getNodesByIds(*waypointNodeIds).associateBy { it.id }

        val allWaypointNodes = waypoints.mapNotNull { waypointNodes[it] }
        if (allWaypointNodes.size < 2) return null

        val astarStart = System.currentTimeMillis()
        val pathNodePairs = mutableListOf<Pair<Long, Long>>()
        var segmentCount = 0
        for (i in 0 until waypoints.size - 1) {
            val from = waypoints[i]
            val to = waypoints[i + 1]
            if (from == to) continue

            val fromNode = waypointNodes[from] ?: return null
            val targetNode = waypointNodes[to] ?: return null

            val segBbox = computeSegmentBbox(fromNode, targetNode, config.bboxMarginM)
            val edges = repository.getRoutingEdgesNear(
                segBbox.minLat, segBbox.minLon, segBbox.maxLat, segBbox.maxLon,
            )
            if (edges.isEmpty()) return null

            val nodeList = repository.getNodesNear(
                segBbox.minLat, segBbox.minLon, segBbox.maxLat, segBbox.maxLon,
            )
            val nodeMap = nodeList.associateBy { it.id }
            val edgesByFromNode = HashMap<Long, MutableList<Int>>(edges.size)
            edges.forEachIndexed { idx, edge ->
                edgesByFromNode.getOrPut(edge.fromNode) { mutableListOf() }.add(idx)
            }

            val segmentPairs = shortestPathAStar(
                edges, nodeMap, edgesByFromNode, from, to, targetNode, config,
            ) ?: return null

            pathNodePairs.addAll(segmentPairs)
            segmentCount++
        }
        Log.d(TAG, "refine: $segmentCount A* segments in ${System.currentTimeMillis() - astarStart}ms")

        if (pathNodePairs.isEmpty()) return null

        val pathEdges = repository.getEdgesByNodePairs(pathNodePairs)
        val edgeByPair = pathEdges.associateBy { it.fromNode to it.toNode }
        val orderedEdges = pathNodePairs.mapNotNull { edgeByPair[it] }

        val actualDistance = orderedEdges.sumOf { it.lengthM }
        Log.d(TAG, "refine: done ${orderedEdges.size} edges, ${actualDistance.toInt()}m total in ${System.currentTimeMillis() - refineStart}ms")

        return RefinedRoute(
            edges = orderedEdges,
            actualDistanceM = actualDistance,
        )
    }

    private fun shortestPathAStar(
        edges: List<RoutingEdge>,
        nodeMap: Map<Long, MapNode>,
        edgesByFromNode: Map<Long, List<Int>>,
        fromNode: Long,
        toNode: Long,
        targetNode: MapNode,
        config: RefinerConfig,
    ): List<Pair<Long, Long>>? {
        fun heuristic(edgeIdx: Int): Double {
            val endNode = nodeMap[edges[edgeIdx].toNode] ?: return 0.0
            return GeoUtils.haversineDistance(
                endNode.lat, endNode.lon, targetNode.lat, targetNode.lon,
            )
        }

        class AStarEntry(val idx: Int, val fCost: Double)

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
                return path.map { edges[it].fromNode to edges[it].toNode }
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

    internal fun buildWaypoints(corridors: List<Corridor>): List<Long> {
        val waypoints = mutableListOf<Long>()
        for (c in corridors) {
            waypoints.add(c.entryNode)
            waypoints.add(c.exitNode)
        }
        waypoints.add(corridors.first().entryNode)
        return waypoints
    }

    internal fun computeChainBbox(
        nodes: List<MapNode>,
        marginM: Double,
    ): BoundingBox {
        val minLat = nodes.minOf { it.lat }
        val maxLat = nodes.maxOf { it.lat }
        val minLon = nodes.minOf { it.lon }
        val maxLon = nodes.maxOf { it.lon }

        val latBuffer = GeoUtils.metersToLat(marginM)
        val midLat = (minLat + maxLat) / 2.0
        val lonBuffer = GeoUtils.metersToLon(marginM, midLat)

        return BoundingBox(
            minLat = minLat - latBuffer,
            minLon = minLon - lonBuffer,
            maxLat = maxLat + latBuffer,
            maxLon = maxLon + lonBuffer,
        )
    }

    internal fun computeSegmentBbox(
        fromNode: MapNode,
        toNode: MapNode,
        marginM: Double,
    ): BoundingBox {
        val minLat = minOf(fromNode.lat, toNode.lat)
        val maxLat = maxOf(fromNode.lat, toNode.lat)
        val minLon = minOf(fromNode.lon, toNode.lon)
        val maxLon = maxOf(fromNode.lon, toNode.lon)

        val latBuffer = GeoUtils.metersToLat(marginM)
        val midLat = (minLat + maxLat) / 2.0
        val lonBuffer = GeoUtils.metersToLon(marginM, midLat)

        return BoundingBox(
            minLat = minLat - latBuffer,
            minLon = minLon - lonBuffer,
            maxLat = maxLat + latBuffer,
            maxLon = maxLon + lonBuffer,
        )
    }
}
