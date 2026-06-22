package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.util.GeoUtils
import java.util.PriorityQueue

data class RefinedRoute(
    val edges: List<MapEdge>,
    val actualDistanceM: Double,
    val totalReward: Double,
    val flowScore: Double,
    val discoveryScore: Double,
)

data class RefinerConfig(
    val bboxMarginM: Double = 2000.0,
    val maxAStarIterations: Int = 50_000,
    val rewardWeight: Double = 10.0,
    val turnCostWeight: Double = 50.0,
)

object RouteRefiner {

    private const val MIN_COST_FRACTION = 0.1

    suspend fun refine(
        candidate: CandidateLoop,
        corridorMap: Map<Long, Corridor>,
        repository: MapGraphRepository,
        weights: RewardWeights = RewardWeights(),
        rewardContext: RewardContext = RewardContext(),
        hazardConfig: HazardConfig = HazardConfig(),
        junctionCostConfig: JunctionCostConfig = JunctionCostConfig(),
        config: RefinerConfig = RefinerConfig(),
    ): RefinedRoute? {
        val candidateCorridors = candidate.corridors.mapNotNull { corridorMap[it] }
        if (candidateCorridors.isEmpty()) return null

        val bbox = computeCandidateBbox(candidateCorridors, config.bboxMarginM)

        val rawEdges = repository.getEdgesNear(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon)
        val nodes = repository.getNodesNear(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon)

        val safeEdges = rawEdges.filter { HazardFilter.shouldKeep(it, hazardConfig) }
        val nodeMap = nodes.associateBy { it.id }

        val waypoints = buildWaypoints(candidateCorridors)

        val allEdges = mutableListOf<MapEdge>()
        for (i in 0 until waypoints.size - 1) {
            val from = waypoints[i]
            val to = waypoints[i + 1]
            if (from == to) continue

            val segment = rewardWeightedAStar(
                safeEdges, from, to, nodeMap,
                weights, rewardContext, junctionCostConfig, config,
            ) ?: return null

            allEdges.addAll(segment)
        }

        val actualDistance = allEdges.sumOf { it.lengthM }
        var totalReward = 0.0
        var flowScore = 0.0
        var discoveryScore = 0.0
        for (edge in allEdges) {
            val reward = RewardComposer.composeEdgeReward(edge, weights, rewardContext)
            totalReward += reward.total
            flowScore += reward.flow
            discoveryScore += reward.explore
        }

        return RefinedRoute(
            edges = allEdges,
            actualDistanceM = actualDistance,
            totalReward = totalReward,
            flowScore = flowScore,
            discoveryScore = discoveryScore,
        )
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

    internal fun computeCandidateBbox(corridors: List<Corridor>, marginM: Double): BoundingBox {
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE

        for (c in corridors) {
            if (c.centroidLat < minLat) minLat = c.centroidLat
            if (c.centroidLat > maxLat) maxLat = c.centroidLat
            if (c.centroidLon < minLon) minLon = c.centroidLon
            if (c.centroidLon > maxLon) maxLon = c.centroidLon
        }

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

    internal fun rewardWeightedAStar(
        edges: List<MapEdge>,
        fromNode: Long,
        toNode: Long,
        nodeMap: Map<Long, MapNode>,
        weights: RewardWeights,
        rewardContext: RewardContext,
        junctionCostConfig: JunctionCostConfig,
        config: RefinerConfig,
    ): List<MapEdge>? {
        val edgesByFromNode = mutableMapOf<Long, MutableList<Int>>()
        edges.forEachIndexed { idx, edge ->
            edgesByFromNode.getOrPut(edge.fromNode) { mutableListOf() }.add(idx)
        }

        val adjacency = mutableMapOf<Int, List<Int>>()
        edges.forEachIndexed { idx, edge ->
            val succs = edgesByFromNode[edge.toNode] ?: emptyList()
            adjacency[idx] = succs.filter { it != idx }
        }

        val targetNode = nodeMap[toNode]

        fun heuristic(edge: MapEdge): Double {
            if (targetNode == null) return 0.0
            val endNode = nodeMap[edge.toNode] ?: return 0.0
            return GeoUtils.haversineDistance(
                endNode.lat, endNode.lon, targetNode.lat, targetNode.lon,
            ) * MIN_COST_FRACTION
        }

        fun edgeCost(edge: MapEdge, approachEdge: MapEdge?): Double {
            val reward = RewardComposer.composeEdgeReward(edge, weights, rewardContext)

            var turnCost = 0.0
            if (approachEdge != null) {
                val approachFrom = nodeMap[approachEdge.fromNode]
                val junction = nodeMap[approachEdge.toNode]
                val exitTo = nodeMap[edge.toNode]
                if (approachFrom != null && junction != null && exitTo != null) {
                    val approachBearing = GeoUtils.computeBearing(
                        approachFrom.lat, approachFrom.lon,
                        junction.lat, junction.lon,
                    )
                    val exitBearing = GeoUtils.computeBearing(
                        junction.lat, junction.lon,
                        exitTo.lat, exitTo.lon,
                    )
                    turnCost = JunctionCost.computeTurnCost(
                        approachBearing, exitBearing, approachEdge, junctionCostConfig,
                    )
                }
            }

            return maxOf(
                edge.lengthM * MIN_COST_FRACTION,
                edge.lengthM + config.turnCostWeight * turnCost -
                    config.rewardWeight * reward.total,
            )
        }

        data class AStarNode(val idx: Int, val gCost: Double, val fCost: Double)

        val openSet = PriorityQueue<AStarNode>(compareBy { it.fCost })
        val gCosts = mutableMapOf<Int, Double>()
        val cameFrom = mutableMapOf<Int, Int>()
        val closedSet = mutableSetOf<Int>()

        val startIndices = edgesByFromNode[fromNode] ?: return null
        for (idx in startIndices) {
            val cost = edgeCost(edges[idx], null)
            gCosts[idx] = cost
            openSet.add(AStarNode(idx, cost, cost + heuristic(edges[idx])))
        }

        var iterations = 0
        while (openSet.isNotEmpty() && iterations < config.maxAStarIterations) {
            iterations++
            val current = openSet.poll() ?: break

            if (current.idx in closedSet) continue
            closedSet.add(current.idx)

            if (edges[current.idx].toNode == toNode) {
                val path = mutableListOf<MapEdge>()
                var edgeIdx = current.idx
                while (true) {
                    path.add(edges[edgeIdx])
                    edgeIdx = cameFrom[edgeIdx] ?: break
                }
                path.reverse()
                return path
            }

            val currentG = gCosts[current.idx] ?: continue
            val successors = adjacency[current.idx] ?: continue

            for (succIdx in successors) {
                if (succIdx in closedSet) continue

                val cost = edgeCost(edges[succIdx], edges[current.idx])
                val newG = currentG + cost

                val bestG = gCosts[succIdx]
                if (bestG != null && newG >= bestG) continue

                gCosts[succIdx] = newG
                cameFrom[succIdx] = current.idx
                openSet.add(AStarNode(succIdx, newG, newG + heuristic(edges[succIdx])))
            }
        }

        return null
    }
}
