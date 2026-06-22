package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.domain.model.CorridorConnector
import com.velometrics.app.util.GeoUtils
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.random.Random

data class OrienteerConfig(
    val candidateCount: Int = 3,
    val graspRestarts: Int = 30,
    val graspAlpha: Double = 0.3,
    val homeExitRadiusM: Double = 5000.0,
    val reusePenaltyWeight: Double = 2.0,
    val overlapDiversityWeight: Double = 0.5,
)

data class CandidateLoop(
    val corridors: List<Long>,
    val totalDistanceM: Double,
    val totalReward: Double,
    val flowScore: Double,
    val discoveryScore: Double,
)

object CorridorOrienteer {

    fun search(
        corridors: List<Corridor>,
        connectors: List<CorridorConnector>,
        homeLat: Double,
        homeLon: Double,
        targetDistanceM: Double,
        weights: RewardWeights = RewardWeights(),
        rewardContext: RewardContext = RewardContext(),
        config: OrienteerConfig = OrienteerConfig(),
        seed: Long = 42L,
    ): List<CandidateLoop> {
        val random = Random(seed)

        val corridorMap = corridors.associateBy { it.id }
        val adjacency = buildAdjacency(connectors)

        val homeCorridor = findNearestCorridor(corridors, homeLat, homeLon) ?: return emptyList()
        val homeId = homeCorridor.id

        val rewardCache = corridors.associate { c ->
            c.id to RewardComposer.composeCorridorReward(c, weights, rewardContext)
        }

        val returnCosts = dijkstraFromHome(homeId, adjacency)

        val allCandidates = mutableListOf<CandidateLoop>()

        repeat(config.graspRestarts) {
            val candidate = graspConstruct(
                corridorMap, adjacency, rewardCache, returnCosts, homeId, homeLat, homeLon,
                targetDistanceM, config, random,
            )
            if (candidate != null) {
                val improved = localSearch(
                    candidate, corridorMap, adjacency, rewardCache, returnCosts,
                    homeId, homeLat, homeLon, targetDistanceM, config,
                )
                allCandidates.add(improved)
            }
        }

        if (allCandidates.isEmpty()) return emptyList()

        return selectDiverse(allCandidates, config)
    }

    private fun graspConstruct(
        corridorMap: Map<Long, Corridor>,
        adjacency: Map<Long, List<AdjEntry>>,
        rewardCache: Map<Long, ComposedReward>,
        returnCosts: Map<Long, Double>,
        homeId: Long,
        homeLat: Double,
        homeLon: Double,
        targetDistanceM: Double,
        config: OrienteerConfig,
        random: Random,
    ): CandidateLoop? {
        val maxBudget = targetDistanceM * 1.15
        val route = mutableListOf(homeId)
        val visited = mutableSetOf(homeId)
        var usedDistance = 0.0
        var totalReward = rewardCache[homeId]?.total ?: 0.0

        while (true) {
            val current = route.last()
            val neighbors = adjacency[current] ?: break

            val feasible = neighbors.filter { adj ->
                val candidateReturnCost = returnCosts[adj.corridorId] ?: return@filter false
                corridorMap.containsKey(adj.corridorId) &&
                    usedDistance + adj.distanceM + candidateReturnCost <= maxBudget
            }
            if (feasible.isEmpty()) break

            val scored = feasible.map { adj ->
                val cId = adj.corridorId
                val corridor = corridorMap[cId]!!
                val baseReward = rewardCache[cId]?.total ?: 0.0
                val reusePenalty = if (visited.contains(cId)) {
                    val distFromHome = GeoUtils.haversineDistance(
                        homeLat, homeLon, corridor.centroidLat, corridor.centroidLon,
                    )
                    config.reusePenaltyWeight * reuseModulation(distFromHome, config.homeExitRadiusM)
                } else {
                    0.0
                }
                val effectiveReward = baseReward / max(adj.distanceM, 1.0) - reusePenalty
                ScoredCandidate(adj, effectiveReward)
            }

            val maxScore = scored.maxOf { it.score }
            val minScore = scored.minOf { it.score }
            val threshold = if (maxScore == minScore) {
                minScore
            } else {
                maxScore - config.graspAlpha * (maxScore - minScore)
            }
            val rcl = scored.filter { it.score >= threshold }

            val chosen = rcl[random.nextInt(rcl.size)]
            route.add(chosen.adj.corridorId)
            visited.add(chosen.adj.corridorId)
            usedDistance += chosen.adj.distanceM
            totalReward += rewardCache[chosen.adj.corridorId]?.total ?: 0.0
        }

        if (route.size < 2) return null

        val returnDist = returnCosts[route.last()] ?: return null
        val totalDist = usedDistance + returnDist

        if (totalDist < targetDistanceM * 0.85) return null

        val corridorSet = route.toSet()
        val flowScore = corridorSet.sumOf { rewardCache[it]?.flow ?: 0.0 }
        val discoveryScore = corridorSet.sumOf { rewardCache[it]?.explore ?: 0.0 }

        return CandidateLoop(
            corridors = route.toList(),
            totalDistanceM = totalDist,
            totalReward = totalReward,
            flowScore = flowScore,
            discoveryScore = discoveryScore,
        )
    }

    private fun localSearch(
        candidate: CandidateLoop,
        corridorMap: Map<Long, Corridor>,
        adjacency: Map<Long, List<AdjEntry>>,
        rewardCache: Map<Long, ComposedReward>,
        returnCosts: Map<Long, Double>,
        homeId: Long,
        homeLat: Double,
        homeLon: Double,
        targetDistanceM: Double,
        config: OrienteerConfig,
    ): CandidateLoop {
        var best = candidate
        var improved = true

        while (improved) {
            improved = false

            val twoOptResult = tryTwoOpt(
                best, corridorMap, adjacency, rewardCache, returnCosts,
                homeId, homeLat, homeLon, targetDistanceM, config,
            )
            if (twoOptResult != null && twoOptResult.totalReward > best.totalReward) {
                best = twoOptResult
                improved = true
            }

            val orOptResult = tryOrOpt(
                best, corridorMap, adjacency, rewardCache, returnCosts,
                homeId, homeLat, homeLon, targetDistanceM, config,
            )
            if (orOptResult != null && orOptResult.totalReward > best.totalReward) {
                best = orOptResult
                improved = true
            }
        }

        return best
    }

    private fun tryTwoOpt(
        candidate: CandidateLoop,
        corridorMap: Map<Long, Corridor>,
        adjacency: Map<Long, List<AdjEntry>>,
        rewardCache: Map<Long, ComposedReward>,
        returnCosts: Map<Long, Double>,
        homeId: Long,
        homeLat: Double,
        homeLon: Double,
        targetDistanceM: Double,
        config: OrienteerConfig,
    ): CandidateLoop? {
        val route = candidate.corridors
        if (route.size < 4) return null

        var bestCandidate: CandidateLoop? = null

        for (i in 1 until route.size - 2) {
            for (j in i + 1 until route.size - 1) {
                val newRoute = route.subList(0, i) +
                    route.subList(i, j + 1).reversed() +
                    route.subList(j + 1, route.size)

                val evaluated = evaluateRoute(
                    newRoute, corridorMap, adjacency, rewardCache, returnCosts,
                    homeId, homeLat, homeLon, targetDistanceM, config,
                )
                if (evaluated != null) {
                    if (bestCandidate == null || evaluated.totalReward > bestCandidate.totalReward) {
                        bestCandidate = evaluated
                    }
                }
            }
        }

        return bestCandidate
    }

    private fun tryOrOpt(
        candidate: CandidateLoop,
        corridorMap: Map<Long, Corridor>,
        adjacency: Map<Long, List<AdjEntry>>,
        rewardCache: Map<Long, ComposedReward>,
        returnCosts: Map<Long, Double>,
        homeId: Long,
        homeLat: Double,
        homeLon: Double,
        targetDistanceM: Double,
        config: OrienteerConfig,
    ): CandidateLoop? {
        val route = candidate.corridors
        if (route.size < 4) return null

        var bestCandidate: CandidateLoop? = null

        for (i in 1 until route.size - 1) {
            val segment = route[i]
            val without = route.toMutableList().apply { removeAt(i) }

            for (j in 1 until without.size) {
                val newRoute = without.toMutableList().apply { add(j, segment) }

                val evaluated = evaluateRoute(
                    newRoute, corridorMap, adjacency, rewardCache, returnCosts,
                    homeId, homeLat, homeLon, targetDistanceM, config,
                )
                if (evaluated != null) {
                    if (bestCandidate == null || evaluated.totalReward > bestCandidate.totalReward) {
                        bestCandidate = evaluated
                    }
                }
            }
        }

        return bestCandidate
    }

    private fun evaluateRoute(
        route: List<Long>,
        corridorMap: Map<Long, Corridor>,
        adjacency: Map<Long, List<AdjEntry>>,
        rewardCache: Map<Long, ComposedReward>,
        returnCosts: Map<Long, Double>,
        homeId: Long,
        homeLat: Double,
        homeLon: Double,
        targetDistanceM: Double,
        config: OrienteerConfig,
    ): CandidateLoop? {
        var distance = 0.0
        for (i in 0 until route.size - 1) {
            val conn = connectionDistance(route[i], route[i + 1], adjacency) ?: return null
            distance += conn
        }

        val returnDist = returnCosts[route.last()] ?: return null
        val totalDist = distance + returnDist

        if (totalDist > targetDistanceM * 1.15 || totalDist < targetDistanceM * 0.85) return null

        val visited = mutableSetOf<Long>()
        var totalReward = 0.0
        for (cId in route) {
            val baseReward = rewardCache[cId]?.total ?: 0.0
            if (visited.contains(cId)) {
                val corridor = corridorMap[cId] ?: continue
                val distFromHome = GeoUtils.haversineDistance(
                    homeLat, homeLon, corridor.centroidLat, corridor.centroidLon,
                )
                val penalty = config.reusePenaltyWeight * reuseModulation(distFromHome, config.homeExitRadiusM)
                totalReward += baseReward - penalty * corridor.lengthM / max(totalDist, 1.0)
            } else {
                totalReward += baseReward
            }
            visited.add(cId)
        }

        val corridorSet = route.toSet()
        val flowScore = corridorSet.sumOf { rewardCache[it]?.flow ?: 0.0 }
        val discoveryScore = corridorSet.sumOf { rewardCache[it]?.explore ?: 0.0 }

        return CandidateLoop(
            corridors = route,
            totalDistanceM = totalDist,
            totalReward = totalReward,
            flowScore = flowScore,
            discoveryScore = discoveryScore,
        )
    }

    internal fun selectDiverse(
        candidates: List<CandidateLoop>,
        config: OrienteerConfig,
    ): List<CandidateLoop> {
        if (candidates.isEmpty()) return emptyList()

        val remaining = candidates.sortedByDescending { it.totalReward }.toMutableList()
        val selected = mutableListOf(remaining.removeFirst())

        while (selected.size < config.candidateCount && remaining.isNotEmpty()) {
            val best = remaining.maxByOrNull { candidate ->
                val maxOverlap = selected.maxOf { existing ->
                    corridorOverlap(candidate.corridors, existing.corridors)
                }
                candidate.totalReward * (1.0 - maxOverlap * config.overlapDiversityWeight)
            } ?: break

            val maxOverlap = selected.maxOf { existing ->
                corridorOverlap(best.corridors, existing.corridors)
            }
            val penalizedReward = best.totalReward * (1.0 - maxOverlap * config.overlapDiversityWeight)
            if (penalizedReward <= 0) break

            remaining.remove(best)
            selected.add(best)
        }

        return selected
    }

    internal fun corridorOverlap(a: List<Long>, b: List<Long>): Double {
        val setA = a.toSet()
        val setB = b.toSet()
        val intersection = setA.intersect(setB).size.toDouble()
        val union = setA.union(setB).size.toDouble()
        if (union == 0.0) return 0.0
        return intersection / union
    }

    internal fun reuseModulation(distanceFromHomeM: Double, homeExitRadiusM: Double): Double {
        if (distanceFromHomeM <= 0.0) return 0.0
        if (distanceFromHomeM >= homeExitRadiusM) return 1.0
        return distanceFromHomeM / homeExitRadiusM
    }

    internal fun findNearestCorridor(
        corridors: List<Corridor>,
        lat: Double,
        lon: Double,
    ): Corridor? {
        return corridors.minByOrNull {
            GeoUtils.haversineDistance(lat, lon, it.centroidLat, it.centroidLon)
        }
    }

    private fun buildAdjacency(connectors: List<CorridorConnector>): Map<Long, List<AdjEntry>> {
        val result = mutableMapOf<Long, MutableList<AdjEntry>>()
        for (conn in connectors) {
            result.getOrPut(conn.fromCorridor) { mutableListOf() }
                .add(AdjEntry(conn.toCorridor, conn.distanceM))
            result.getOrPut(conn.toCorridor) { mutableListOf() }
                .add(AdjEntry(conn.fromCorridor, conn.distanceM))
        }
        return result
    }

    private fun dijkstraFromHome(
        homeId: Long,
        adjacency: Map<Long, List<AdjEntry>>,
    ): Map<Long, Double> {
        val dist = mutableMapOf(homeId to 0.0)
        val visited = mutableSetOf<Long>()
        val pq = PriorityQueue<Pair<Long, Double>>(compareBy { it.second })
        pq.add(homeId to 0.0)

        while (pq.isNotEmpty()) {
            val (current, currentDist) = pq.poll() ?: break
            if (!visited.add(current)) continue
            if (currentDist > (dist[current] ?: Double.MAX_VALUE)) continue

            val neighbors = adjacency[current] ?: continue
            for (adj in neighbors) {
                val newDist = currentDist + adj.distanceM
                if (newDist < (dist[adj.corridorId] ?: Double.MAX_VALUE)) {
                    dist[adj.corridorId] = newDist
                    pq.add(adj.corridorId to newDist)
                }
            }
        }

        return dist
    }

    private fun connectionDistance(
        fromId: Long,
        toId: Long,
        adjacency: Map<Long, List<AdjEntry>>,
    ): Double? {
        return adjacency[fromId]?.firstOrNull { it.corridorId == toId }?.distanceM
    }

    internal data class AdjEntry(val corridorId: Long, val distanceM: Double)
    private data class ScoredCandidate(val adj: AdjEntry, val score: Double)
}
