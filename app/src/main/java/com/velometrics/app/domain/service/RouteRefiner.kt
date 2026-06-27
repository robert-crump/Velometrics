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
    val bboxExpansionSteps: List<Double> = listOf(1.0, 2.0, 4.0),
    val edgeReusePenalty: Double = 5.0,
    val connectorRewardBudgetFraction: Double? = 1.5,
)

object RouteRefiner {

    private const val TAG = "RouteRefiner"

    suspend fun refine(
        candidate: CandidateLoop,
        corridorMap: Map<Long, Corridor>,
        repository: MapGraphRepository,
        config: RefinerConfig = RefinerConfig(),
        closeLoop: Boolean = true,
    ): RefinedRoute? {
        val refineStart = System.currentTimeMillis()
        val candidateCorridors = candidate.corridors.mapNotNull { corridorMap[it] }
        if (candidateCorridors.isEmpty()) return null

        val waypoints = buildWaypoints(candidateCorridors, closeLoop)
        Log.d(TAG, "refine: ${candidateCorridors.size} corridors -> ${waypoints.size} waypoints")

        val waypointNodeIds = waypoints.toSet().toLongArray()
        val waypointNodes = repository.getNodesByIds(*waypointNodeIds).associateBy { it.id }

        val missingNodes = waypointNodeIds.filter { it !in waypointNodes }
        if (missingNodes.isNotEmpty()) {
            Log.d(TAG, "refine: MISSING waypoint nodes: $missingNodes")
        }

        val allWaypointNodes = waypoints.mapNotNull { waypointNodes[it] }
        if (allWaypointNodes.size < 2) {
            Log.d(TAG, "refine: abort - only ${allWaypointNodes.size} waypoint nodes resolved out of ${waypoints.size}")
            return null
        }

        val loadStart = System.currentTimeMillis()
        val chainBbox = computeChainBbox(allWaypointNodes, config.bboxMarginM * 2.0)
        val preloadedEdges = repository.getRoutingEdgesNear(
            chainBbox.minLat, chainBbox.minLon, chainBbox.maxLat, chainBbox.maxLon,
        )
        val preloadedNodes = repository.getNodesNear(
            chainBbox.minLat, chainBbox.minLon, chainBbox.maxLat, chainBbox.maxLon,
        )
        val preloadedNodeMap = preloadedNodes.associateBy { it.id }
        val preloadedEdgeIndex = HashMap<Long, MutableList<Int>>(preloadedEdges.size)
        preloadedEdges.forEachIndexed { idx, edge ->
            preloadedEdgeIndex.getOrPut(edge.fromNode) { mutableListOf() }.add(idx)
        }
        Log.d(TAG, "refine: preloaded ${preloadedEdges.size} edges, ${preloadedNodes.size} nodes in ${System.currentTimeMillis() - loadStart}ms")

        val edgePairToLength = HashMap<Pair<Long, Long>, Double>(preloadedEdges.size)
        val edgePairToReward = HashMap<Pair<Long, Long>, Double>(preloadedEdges.size)
        for (e in preloadedEdges) {
            val key = e.fromNode to e.toNode
            edgePairToLength[key] = e.lengthM
            edgePairToReward[key] = e.reward
        }

        val astarStart = System.currentTimeMillis()
        val pathNodePairs = mutableListOf<Pair<Long, Long>>()
        var segmentCount = 0
        val usedNodePairs = HashSet<Pair<Long, Long>>()
        for (i in 0 until waypoints.size - 1) {
            val from = waypoints[i]
            val to = waypoints[i + 1]
            if (from == to) continue

            val targetNode = waypointNodes[to] ?: preloadedNodeMap[to]
            if (targetNode == null) {
                Log.d(TAG, "refine: segment[$segmentCount] FAIL toNode=$to not found")
                return null
            }

            val shortestPairsFromPreload = shortestPathAStar(
                preloadedEdges, preloadedNodeMap, preloadedEdgeIndex, from, to, targetNode, config,
                usedNodePairs,
            )
            var segmentPairs = shortestPairsFromPreload

            if (segmentPairs == null) {
                val fromNode = waypointNodes[from] ?: preloadedNodeMap[from]
                if (fromNode == null) {
                    Log.d(TAG, "refine: segment[$segmentCount] FAIL fromNode=$from not found")
                    return null
                }
                for (marginMultiplier in config.bboxExpansionSteps) {
                    val margin = config.bboxMarginM * marginMultiplier
                    val segBbox = computeSegmentBbox(fromNode, targetNode, margin)
                    val edges = repository.getRoutingEdgesNear(
                        segBbox.minLat, segBbox.minLon, segBbox.maxLat, segBbox.maxLon,
                    )
                    if (edges.isEmpty()) continue
                    val nodeList = repository.getNodesNear(
                        segBbox.minLat, segBbox.minLon, segBbox.maxLat, segBbox.maxLon,
                    )
                    val nodeMap = nodeList.associateBy { it.id }
                    val edgesByFromNode = HashMap<Long, MutableList<Int>>(edges.size)
                    edges.forEachIndexed { idx, edge ->
                        edgesByFromNode.getOrPut(edge.fromNode) { mutableListOf() }.add(idx)
                    }
                    segmentPairs = shortestPathAStar(edges, nodeMap, edgesByFromNode, from, to, targetNode, config, usedNodePairs)
                    if (segmentPairs != null) {
                        Log.d(TAG, "refine: segment[$segmentCount] OK from=$from to=$to ${segmentPairs.size} edges (fallback ${marginMultiplier}x)")
                        break
                    }
                }
            }

            if (segmentPairs == null) {
                Log.d(TAG, "refine: segment[$segmentCount] FAIL from=$from to=$to")
                return null
            }

            val rewardFraction = config.connectorRewardBudgetFraction
            if (rewardFraction != null && shortestPairsFromPreload != null) {
                val shortestLength = shortestPairsFromPreload.sumOf { edgePairToLength[it] ?: 0.0 }
                val pass1Reward = shortestPairsFromPreload.sumOf { edgePairToReward[it] ?: 0.0 }
                val rewardPairs = rewardMaxDijkstra(
                    preloadedEdges, preloadedEdgeIndex, from, to,
                    shortestLength * rewardFraction, config.maxAStarIterations,
                )
                if (rewardPairs != null) {
                    val pass2Reward = rewardPairs.sumOf { edgePairToReward[it] ?: 0.0 }
                    if (pass2Reward > pass1Reward) {
                        Log.d(TAG, "refine: segment[$segmentCount] reward detour reward=$pass2Reward vs $pass1Reward cap=${(shortestLength * rewardFraction).toInt()}m")
                        segmentPairs = rewardPairs
                    }
                }
            }

            pathNodePairs.addAll(segmentPairs)
            for (pair in segmentPairs) {
                usedNodePairs.add(pair)
                usedNodePairs.add(pair.second to pair.first)
            }
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
        usedNodePairs: Set<Pair<Long, Long>> = emptySet(),
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

        val startIndices = edgesByFromNode[fromNode]
        if (startIndices == null) {
            Log.d(TAG, "A*: no outgoing edges from fromNode=$fromNode")
            return null
        }
        for (idx in startIndices) {
            val reuse = if (usedNodePairs.contains(edges[idx].fromNode to edges[idx].toNode)) config.edgeReusePenalty else 1.0
            val cost = edges[idx].lengthM * reuse
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

                val reuse = if (usedNodePairs.contains(edges[succIdx].fromNode to edges[succIdx].toNode)) config.edgeReusePenalty else 1.0
                val newG = currentG + edges[succIdx].lengthM * reuse

                val bestG = gCosts[succIdx]
                if (bestG != null && newG >= bestG) continue

                gCosts[succIdx] = newG
                cameFrom[succIdx] = current.idx
                openSet.add(AStarEntry(succIdx, newG + heuristic(succIdx)))
            }
        }

        val exhausted = iterations >= config.maxAStarIterations
        Log.d(TAG, "A*: FAIL from=$fromNode to=$toNode iterations=$iterations exhausted=$exhausted openSet=${openSet.size} closed=${closedSet.size}")
        return null
    }

    internal fun buildWaypoints(corridors: List<Corridor>, closeLoop: Boolean = true): List<Long> {
        val waypoints = mutableListOf<Long>()
        for ((idx, c) in corridors.withIndex()) {
            if (closeLoop && idx == corridors.lastIndex && c.id == corridors.first().id) {
                break
            }
            waypoints.add(c.entryNode)
            waypoints.add(c.exitNode)
        }
        if (closeLoop) {
            waypoints.add(corridors.first().entryNode)
        }
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

    private fun rewardMaxDijkstra(
        edges: List<RoutingEdge>,
        edgesByFromNode: Map<Long, List<Int>>,
        fromNode: Long,
        toNode: Long,
        maxLength: Double,
        maxIterations: Int,
    ): List<Pair<Long, Long>>? {
        data class Entry(val edgeIdx: Int, val accLength: Double, val accReward: Double)

        val open = PriorityQueue<Entry>(compareByDescending { it.accReward })
        // Keyed by edge index so each edge is settled at most once, preventing cameFrom cycles.
        val settled = HashSet<Int>()
        val gReward = HashMap<Int, Double>()
        val cameFrom = HashMap<Int, Int>()

        val starts = edgesByFromNode[fromNode] ?: return null
        for (idx in starts) {
            val e = edges[idx]
            if (e.lengthM > maxLength) continue
            gReward[idx] = e.reward
            open.add(Entry(idx, e.lengthM, e.reward))
        }

        var bestDestReward = Double.NEGATIVE_INFINITY
        var bestDestIdx = -1

        var iterations = 0
        while (open.isNotEmpty() && iterations < maxIterations) {
            iterations++
            val curr = open.poll() ?: break

            if (curr.edgeIdx in settled) continue
            settled.add(curr.edgeIdx)

            val currEdge = edges[curr.edgeIdx]

            if (currEdge.toNode == toNode) {
                if (curr.accReward > bestDestReward) {
                    bestDestReward = curr.accReward
                    bestDestIdx = curr.edgeIdx
                }
                continue
            }

            val succs = edgesByFromNode[currEdge.toNode] ?: continue
            for (succIdx in succs) {
                if (succIdx in settled) continue
                val succ = edges[succIdx]
                val newLen = curr.accLength + succ.lengthM
                if (newLen > maxLength) continue
                val newReward = curr.accReward + succ.reward
                val best = gReward[succIdx]
                if (best != null && newReward <= best) continue
                gReward[succIdx] = newReward
                cameFrom[succIdx] = curr.edgeIdx
                open.add(Entry(succIdx, newLen, newReward))
            }
        }

        if (bestDestIdx == -1) return null
        val path = mutableListOf<Int>()
        var idx = bestDestIdx
        while (true) {
            path.add(idx)
            idx = cameFrom[idx] ?: break
        }
        path.reverse()
        return path.map { edges[it].fromNode to edges[it].toNode }
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
