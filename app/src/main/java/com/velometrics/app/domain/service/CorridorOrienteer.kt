package com.velometrics.app.domain.service

import android.util.Log
import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.domain.model.CorridorConnector
import com.velometrics.app.util.GeoUtils
import kotlinx.coroutines.ensureActive
import java.util.PriorityQueue
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

data class OrienteerConfig(
    val candidateCount: Int = 1,
    val graspRestarts: Int = 10,
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

    private const val TAG = "CorridorOrienteer"
    private const val DIRECTION_CONE_HALF_ANGLE = 45.0

    suspend fun search(
        corridors: List<Corridor>,
        connectors: List<CorridorConnector>,
        homeLat: Double,
        homeLon: Double,
        targetDistanceM: Double,
        weights: RewardWeights = RewardWeights(),
        rewardContext: RewardContext = RewardContext(),
        config: OrienteerConfig = OrienteerConfig(),
        seed: Long = 42L,
        direction: RideDirection? = null,
    ): List<CandidateLoop> {
        val searchStart = System.currentTimeMillis()
        val random = Random(seed)

        val maxRadiusM = targetDistanceM / 2.0
        val reachable = filterByRadius(corridors, homeLat, homeLon, maxRadiusM)
        val filtered = if (direction != null) {
            filterByDirection(reachable, homeLat, homeLon, direction, config.homeExitRadiusM)
        } else {
            reachable
        }
        Log.d(TAG, "search: ${corridors.size} corridors -> ${reachable.size} by radius(${maxRadiusM.toInt()}m) -> ${filtered.size} by direction, restarts=${config.graspRestarts}")

        val corridorMap = filtered.associateBy { it.id }
        val adjacency = buildAdjacency(connectors.filter {
            corridorMap.containsKey(it.fromCorridor) && corridorMap.containsKey(it.toCorridor)
        })

        val homeCorridor = findNearestCorridor(filtered, homeLat, homeLon) ?: return emptyList()
        val homeId = homeCorridor.id

        val rewardCache = filtered.associate { c ->
            c.id to RewardComposer.composeCorridorReward(c, weights, rewardContext)
        }

        val returnCosts = dijkstraFromHome(homeId, adjacency)

        val allCandidates = mutableListOf<CandidateLoop>()

        repeat(config.graspRestarts) { restart ->
            coroutineContext.ensureActive()
            val restartStart = System.currentTimeMillis()
            val candidate = graspConstruct(
                corridorMap, adjacency, rewardCache, returnCosts, homeId, homeLat, homeLon,
                targetDistanceM, config, random,
            )
            val graspMs = System.currentTimeMillis() - restartStart
            if (candidate != null) {
                val localStart = System.currentTimeMillis()
                val improved = localSearch(
                    candidate, corridorMap, adjacency, rewardCache, returnCosts,
                    homeId, homeLat, homeLon, targetDistanceM, config,
                )
                val localMs = System.currentTimeMillis() - localStart
                Log.d(TAG, "search: restart[$restart] corridors=${improved.corridors.size} dist=${improved.totalDistanceM.toInt()}m grasp=${graspMs}ms local=${localMs}ms")
                allCandidates.add(improved)
            } else {
                Log.d(TAG, "search: restart[$restart] no feasible candidate grasp=${graspMs}ms")
            }
        }

        Log.d(TAG, "search: ${allCandidates.size}/${config.graspRestarts} feasible candidates in ${System.currentTimeMillis() - searchStart}ms")

        if (allCandidates.isEmpty()) return emptyList()

        return selectDiverse(allCandidates, config)
    }

    private suspend fun graspConstruct(
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

    private suspend fun localSearch(
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
        val connDist = precomputeConnectionDistances(adjacency)
        val homeDistCache = precomputeHomeDistances(corridorMap, homeLat, homeLon)
        val visited = HashSet<Long>(candidate.corridors.size * 2)

        var best = candidate
        var improved = true
        var iteration = 0

        while (improved) {
            coroutineContext.ensureActive()
            improved = false
            val iterStart = System.currentTimeMillis()

            val twoOptResult = tryTwoOpt(
                best, rewardCache, connDist, returnCosts, homeDistCache,
                targetDistanceM, config, visited,
            )
            if (twoOptResult != null && twoOptResult.totalReward > best.totalReward) {
                best = twoOptResult
                improved = true
            }

            val orOptResult = tryOrOpt(
                best, rewardCache, connDist, returnCosts, homeDistCache,
                targetDistanceM, config, visited,
            )
            if (orOptResult != null && orOptResult.totalReward > best.totalReward) {
                best = orOptResult
                improved = true
            }

            Log.d(TAG, "localSearch: iter=$iteration routeSize=${best.corridors.size} improved=$improved in ${System.currentTimeMillis() - iterStart}ms")
            iteration++
        }

        return best
    }

    private fun precomputeConnectionDistances(
        adjacency: Map<Long, List<AdjEntry>>,
    ): HashMap<Long, Double> {
        val result = HashMap<Long, Double>()
        for ((fromId, entries) in adjacency) {
            for (entry in entries) {
                result[pairKey(fromId, entry.corridorId)] = entry.distanceM
            }
        }
        return result
    }

    private fun precomputeHomeDistances(
        corridorMap: Map<Long, Corridor>,
        homeLat: Double,
        homeLon: Double,
    ): HashMap<Long, Double> {
        val result = HashMap<Long, Double>(corridorMap.size * 2)
        for ((id, c) in corridorMap) {
            result[id] = GeoUtils.haversineDistance(homeLat, homeLon, c.centroidLat, c.centroidLon)
        }
        return result
    }

    private fun pairKey(a: Long, b: Long): Long = (a shl 32) or (b and 0xFFFFFFFFL)

    private suspend fun tryTwoOpt(
        candidate: CandidateLoop,
        rewardCache: Map<Long, ComposedReward>,
        connDist: HashMap<Long, Double>,
        returnCosts: Map<Long, Double>,
        homeDistCache: HashMap<Long, Double>,
        targetDistanceM: Double,
        config: OrienteerConfig,
        visited: HashSet<Long>,
    ): CandidateLoop? {
        val route = candidate.corridors
        val n = route.size
        if (n < 4) return null

        val buf = route.toLongArray()
        var bestCandidate: CandidateLoop? = null

        for (i in 1 until n - 2) {
            coroutineContext.ensureActive()
            for (j in i + 1 until n - 1) {
                var lo = i; var hi = j
                while (lo < hi) { val tmp = buf[lo]; buf[lo] = buf[hi]; buf[hi] = tmp; lo++; hi-- }

                val evaluated = evaluateRouteArray(
                    buf, n, rewardCache, connDist, returnCosts, homeDistCache,
                    targetDistanceM, config, visited,
                )
                if (evaluated != null) {
                    if (bestCandidate == null || evaluated.totalReward > bestCandidate.totalReward) {
                        bestCandidate = evaluated
                    }
                }

                lo = i; hi = j
                while (lo < hi) { val tmp = buf[lo]; buf[lo] = buf[hi]; buf[hi] = tmp; lo++; hi-- }
            }
        }

        return bestCandidate
    }

    private suspend fun tryOrOpt(
        candidate: CandidateLoop,
        rewardCache: Map<Long, ComposedReward>,
        connDist: HashMap<Long, Double>,
        returnCosts: Map<Long, Double>,
        homeDistCache: HashMap<Long, Double>,
        targetDistanceM: Double,
        config: OrienteerConfig,
        visited: HashSet<Long>,
    ): CandidateLoop? {
        val route = candidate.corridors
        val n = route.size
        if (n < 4) return null

        val buf = LongArray(n)
        var bestCandidate: CandidateLoop? = null

        for (i in 1 until n - 1) {
            coroutineContext.ensureActive()
            val segment = route[i]

            for (j in 1 until n - 1) {
                if (j == i || j == i - 1) continue

                var pos = 0
                val insertAt = if (j > i) j else j
                for (k in 0 until n) {
                    if (k == i) continue
                    if (pos == insertAt) { buf[pos++] = segment }
                    buf[pos++] = route[k]
                }
                if (pos == insertAt) { buf[pos++] = segment }
                val bufLen = pos

                val evaluated = evaluateRouteArray(
                    buf, bufLen, rewardCache, connDist, returnCosts, homeDistCache,
                    targetDistanceM, config, visited,
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

    private fun evaluateRouteArray(
        route: LongArray,
        len: Int,
        rewardCache: Map<Long, ComposedReward>,
        connDist: HashMap<Long, Double>,
        returnCosts: Map<Long, Double>,
        homeDistCache: HashMap<Long, Double>,
        targetDistanceM: Double,
        config: OrienteerConfig,
        visited: HashSet<Long>,
    ): CandidateLoop? {
        var distance = 0.0
        for (i in 0 until len - 1) {
            val d = connDist[pairKey(route[i], route[i + 1])] ?: return null
            distance += d
        }

        val returnDist = returnCosts[route[len - 1]] ?: return null
        val totalDist = distance + returnDist

        if (totalDist > targetDistanceM * 1.15 || totalDist < targetDistanceM * 0.85) return null

        visited.clear()
        var totalReward = 0.0
        var flowScore = 0.0
        var discoveryScore = 0.0
        for (k in 0 until len) {
            val cId = route[k]
            val reward = rewardCache[cId] ?: continue
            if (!visited.add(cId)) {
                val distFromHome = homeDistCache[cId] ?: continue
                val penalty = config.reusePenaltyWeight * reuseModulation(distFromHome, config.homeExitRadiusM)
                totalReward += reward.total - penalty
            } else {
                totalReward += reward.total
                flowScore += reward.flow
                discoveryScore += reward.explore
            }
        }

        val routeList = ArrayList<Long>(len)
        for (k in 0 until len) routeList.add(route[k])

        return CandidateLoop(
            corridors = routeList,
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

    internal fun filterByRadius(
        corridors: List<Corridor>,
        homeLat: Double,
        homeLon: Double,
        maxRadiusM: Double,
    ): List<Corridor> = corridors.filter { c ->
        GeoUtils.haversineDistance(homeLat, homeLon, c.centroidLat, c.centroidLon) <= maxRadiusM
    }

    internal fun filterByDirection(
        corridors: List<Corridor>,
        homeLat: Double,
        homeLon: Double,
        direction: RideDirection,
        homeExitRadiusM: Double,
    ): List<Corridor> = corridors.filter { c ->
        val dist = GeoUtils.haversineDistance(homeLat, homeLon, c.centroidLat, c.centroidLon)
        if (dist <= homeExitRadiusM) return@filter true
        val bearing = GeoUtils.computeBearing(homeLat, homeLon, c.centroidLat, c.centroidLon)
        val diff = abs(bearing - direction.bearingCenter) % 360
        val angularDiff = if (diff > 180) 360 - diff else diff
        angularDiff <= DIRECTION_CONE_HALF_ANGLE
    }

    internal data class AdjEntry(val corridorId: Long, val distanceM: Double)
    private data class ScoredCandidate(val adj: AdjEntry, val score: Double)
}
