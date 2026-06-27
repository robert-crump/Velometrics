package com.velometrics.app.domain.service

import android.util.Log
import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.util.GeoUtils
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class GeneratorConfig(
    val orienteerConfig: OrienteerConfig = OrienteerConfig(),
    val refinerConfig: RefinerConfig = RefinerConfig(),
    val degradationConfig: DegradationConfig = DegradationConfig(),
    val exitLegConfig: ExitLegConfig = ExitLegConfig(),
    val direction: RideDirection? = null,
    val seed: Long = System.currentTimeMillis(),
)

data class RankedCandidate(
    val refinedRoute: RefinedRoute,
    val coarseLoop: CandidateLoop,
    val rank: Int,
    val distanceDeviationPercent: Double,
    val corridorEdges: List<MapEdge>,
)

data class ExitLegPlan(
    val exitLeg: ExitLeg,
    val exitCorridorId: Long,
    val adjustedTargetM: Double,
    val estimatedReturnDistM: Double,
)

sealed interface RoutePlanResult {
    data class Success(
        val candidate: RankedCandidate,
        val appliedTier: DegradationPolicy.RelaxationTier,
    ) : RoutePlanResult

    data class Failure(
        val reason: String,
    ) : RoutePlanResult
}

object RouteGenerator {

    private const val TAG = "RouteGenerator"
    private const val REFINED_BUFFER_FACTOR = 2
    private const val REFINEMENT_CANDIDATE_MULTIPLIER = 5
    internal const val ROAD_DISTANCE_FACTOR = 1.3
    internal const val MIN_CORRIDOR_BUDGET_FRACTION = 0.3

    suspend fun generate(
        homeLat: Double,
        homeLon: Double,
        targetDistanceM: Double,
        repository: MapGraphRepository,
        weights: RewardWeights = RewardWeights(),
        rewardContext: RewardContext = RewardContext(),
        config: GeneratorConfig = GeneratorConfig(),
    ): RoutePlanResult {
        val genStart = System.currentTimeMillis()
        Log.d(TAG, "generate: start targetDistance=${targetDistanceM.toInt()}m direction=${config.direction}")

        val corridors = repository.getAllCorridors()
        val connectors = repository.getAllCorridorConnectors()
        Log.d(TAG, "generate: loaded ${corridors.size} corridors, ${connectors.size} connectors in ${System.currentTimeMillis() - genStart}ms")

        if (corridors.isEmpty()) {
            return RoutePlanResult.Failure(
                "No corridor data available. Check that your home is set near mapped cycling roads.",
            )
        }

        val corridorMap = corridors.associateBy { it.id }

        val exitPlan = planExitLeg(
            homeLat, homeLon, targetDistanceM, config.direction,
            corridors, repository, config.exitLegConfig,
        )
        val effectiveTargetM = exitPlan?.adjustedTargetM ?: targetDistanceM
        Log.d(TAG, "generate: exitPlan=${if (exitPlan != null) "exitCorridor=${exitPlan.exitCorridorId} exitDist=${exitPlan.exitLeg.distanceM.toInt()}m estReturn=${exitPlan.estimatedReturnDistM.toInt()}m adjustedTarget=${exitPlan.adjustedTargetM.toInt()}m" else "null (fallback to home)"}")

        val maxCandidates = config.orienteerConfig.candidateCount
        var currentTier = DegradationPolicy.RelaxationTier.NONE
        val allRefined = mutableListOf<Pair<CandidateLoop, RefinedRoute>>()

        while (true) {
            coroutineContext.ensureActive()
            val tierStart = System.currentTimeMillis()
            Log.d(TAG, "generate: tier=$currentTier starting")

            val tierParams = DegradationPolicy.tierParams(currentTier, config.degradationConfig)

            val refinementCount = maxCandidates * REFINEMENT_CANDIDATE_MULTIPLIER
            val tierOrienteerConfig = config.orienteerConfig.copy(
                candidateCount = refinementCount,
                reachFraction = tierParams.reachFraction,
                separationM = tierParams.separationM,
                headingConeCosine = tierParams.headingConeCosine,
            )

            val coarseStart = System.currentTimeMillis()
            val coarseCandidates = CorridorOrienteer.search(
                corridors, homeLat, homeLon,
                effectiveTargetM,
                weights, tierOrienteerConfig,
                config.direction,
                startCorridorId = exitPlan?.exitCorridorId,
                nodeResolver = { ids ->
                    if (ids.isEmpty()) {
                        emptyMap()
                    } else {
                        repository.getNodesByIds(*ids.toLongArray())
                            .associate { it.id to (it.lat to it.lon) }
                    }
                },
                edgeResolver = { minLat, minLon, maxLat, maxLon ->
                    repository.getEdgesNear(minLat, minLon, maxLat, maxLon)
                },
            )
            Log.d(TAG, "generate: coarse search found ${coarseCandidates.size} candidates in ${System.currentTimeMillis() - coarseStart}ms")

            for ((idx, candidate) in coarseCandidates.withIndex()) {
                if (allRefined.any { it.first.corridors == candidate.corridors }) continue

                // Pseudo-corridors synthesized for empty quadrants are not in the repository-backed
                // map, so merge them in before refining/stitching this candidate.
                val effectiveCorridorMap = if (candidate.syntheticCorridors.isEmpty()) {
                    corridorMap
                } else {
                    corridorMap + candidate.syntheticCorridors
                }

                val refineStart = System.currentTimeMillis()
                val refined = RouteRefiner.refine(
                    candidate, effectiveCorridorMap, repository,
                    config.refinerConfig,
                    closeLoop = exitPlan == null,
                )
                if (refined == null) {
                    Log.d(TAG, "generate: refine candidate[$idx] corridors=${candidate.corridors.size} -> null in ${System.currentTimeMillis() - refineStart}ms")
                    continue
                }

                val finalRoute = if (exitPlan != null) {
                    stitchRoute(
                        exitPlan.exitLeg, refined, candidate, effectiveCorridorMap,
                        homeLat, homeLon, repository, config.exitLegConfig,
                    )
                } else {
                    refined
                }

                Log.d(TAG, "generate: refine candidate[$idx] corridors=${candidate.corridors.size} -> ${finalRoute.edges.size} edges, ${finalRoute.actualDistanceM.toInt()}m${if (exitPlan != null) " (stitched)" else ""} in ${System.currentTimeMillis() - refineStart}ms")
                allRefined.add(candidate to finalRoute)
            }

            val band = tierParams.distanceBandFraction
            trimRefined(allRefined, maxCandidates * REFINED_BUFFER_FACTOR, targetDistanceM, band)

            val refinedDistances = allRefined.map { it.second.actualDistanceM }
            val maxReachable = estimateMaxReachable(corridors, connectors)

            val bandLo = (targetDistanceM * (1.0 - band) / 1000.0).toInt()
            val bandHi = (targetDistanceM * (1.0 + band) / 1000.0).toInt()
            val distSummary = if (refinedDistances.isEmpty()) "none" else
                refinedDistances.joinToString { d ->
                    val km = (d / 1000.0).toInt()
                    if (d in targetDistanceM * (1.0 - band)..targetDistanceM * (1.0 + band)) "${km}km✓" else "${km}km✗"
                }
            Log.d(TAG, "generate: tier=$currentTier band=[${bandLo}km,${bandHi}km] distances=[$distSummary]")

            val outcome = DegradationPolicy.evaluate(
                refinedDistances, targetDistanceM, currentTier,
                maxReachable, config.degradationConfig,
            )
            Log.d(TAG, "generate: tier=$currentTier outcome=${outcome::class.simpleName} refined=${allRefined.size} tierTime=${System.currentTimeMillis() - tierStart}ms")

            when (outcome) {
                is DegradationPolicy.EvaluationOutcome.Sufficient -> {
                    val band = tierParams.distanceBandFraction
                    val lower = targetDistanceM * (1.0 - band)
                    val upper = targetDistanceM * (1.0 + band)
                    val (coarse, refined) = allRefined
                        .filter { it.second.actualDistanceM in lower..upper }
                        .maxByOrNull { it.first.totalReward }
                        ?: continue
                    val deviation = if (targetDistanceM > 0) {
                        (refined.actualDistanceM - targetDistanceM) / targetDistanceM * 100.0
                    } else {
                        0.0
                    }
                    val effectiveMap = if (coarse.syntheticCorridors.isEmpty()) corridorMap
                        else corridorMap + coarse.syntheticCorridors
                    val corridorEdgePairs = coarse.corridors
                        .mapNotNull { effectiveMap[it] }
                        .flatMapTo(HashSet()) { c -> c.edgeList }
                    val corridorEdges = refined.edges.filter { (it.fromNode to it.toNode) in corridorEdgePairs }
                    Log.d(TAG, "generate: done in ${System.currentTimeMillis() - genStart}ms, corridorEdges=${corridorEdges.size}")
                    return RoutePlanResult.Success(
                        RankedCandidate(refined, coarse, 1, deviation, corridorEdges),
                        outcome.appliedTier,
                    )
                }

                is DegradationPolicy.EvaluationOutcome.NeedsRelaxation -> {
                    currentTier = outcome.nextTier
                }

                is DegradationPolicy.EvaluationOutcome.HardFailure -> {
                    Log.d(TAG, "generate: hard failure in ${System.currentTimeMillis() - genStart}ms: ${outcome.reason}")
                    return RoutePlanResult.Failure(outcome.reason)
                }
            }
        }
    }

    internal suspend fun planExitLeg(
        homeLat: Double,
        homeLon: Double,
        targetDistanceM: Double,
        direction: RideDirection?,
        corridors: List<Corridor>,
        repository: MapGraphRepository,
        config: ExitLegConfig,
    ): ExitLegPlan? {
        val exitLeg = ExitLegPlanner.computeExitLeg(
            homeLat, homeLon, direction, corridors, targetDistanceM, repository, config,
        ) ?: return null

        val exitCorridor = corridors.firstOrNull { it.entryNode == exitLeg.targetNode }
            ?: return null

        val homeCorridor = CorridorOrienteer.findNearestCorridor(corridors, homeLat, homeLon)
        if (homeCorridor != null && exitCorridor.id == homeCorridor.id) {
            Log.d(TAG, "planExitLeg: exit corridor is home corridor, skipping exit leg")
            return null
        }

        val estimatedReturnDistM = GeoUtils.haversineDistance(
            exitCorridor.centroidLat, exitCorridor.centroidLon, homeLat, homeLon,
        ) * ROAD_DISTANCE_FACTOR

        val adjustedTargetM = targetDistanceM - exitLeg.distanceM - estimatedReturnDistM
        if (adjustedTargetM < targetDistanceM * MIN_CORRIDOR_BUDGET_FRACTION) {
            Log.d(TAG, "planExitLeg: adjusted budget ${adjustedTargetM.toInt()}m < ${(targetDistanceM * MIN_CORRIDOR_BUDGET_FRACTION).toInt()}m min, skipping exit leg")
            return null
        }

        return ExitLegPlan(exitLeg, exitCorridor.id, adjustedTargetM, estimatedReturnDistM)
    }

    private suspend fun stitchRoute(
        exitLeg: ExitLeg,
        corridorRoute: RefinedRoute,
        candidate: CandidateLoop,
        corridorMap: Map<Long, Corridor>,
        homeLat: Double,
        homeLon: Double,
        repository: MapGraphRepository,
        exitLegConfig: ExitLegConfig,
    ): RefinedRoute {
        val lastCorridorId = candidate.corridors.last()
        val lastCorridor = corridorMap[lastCorridorId]
        val returnLeg = if (lastCorridor != null) {
            ExitLegPlanner.computeReturnLeg(
                lastCorridor.exitNode,
                lastCorridor.centroidLat, lastCorridor.centroidLon,
                homeLat, homeLon,
                repository, exitLegConfig,
            )
        } else {
            null
        }
        if (returnLeg != null) {
            Log.d(TAG, "stitchRoute: return leg ${returnLeg.edges.size} edges, ${returnLeg.distanceM.toInt()}m from corridor $lastCorridorId")
        } else {
            Log.d(TAG, "stitchRoute: no return leg from corridor $lastCorridorId")
        }

        val edges = exitLeg.edges + corridorRoute.edges + (returnLeg?.edges ?: emptyList())
        val distance = exitLeg.distanceM + corridorRoute.actualDistanceM + (returnLeg?.distanceM ?: 0.0)
        return RefinedRoute(edges, distance)
    }

    private fun trimRefined(
        refined: MutableList<Pair<CandidateLoop, RefinedRoute>>,
        maxKeep: Int,
        targetDistanceM: Double,
        bandFraction: Double,
    ) {
        if (refined.size <= maxKeep) return
        val lo = targetDistanceM * (1.0 - bandFraction)
        val hi = targetDistanceM * (1.0 + bandFraction)
        // In-band routes are kept before out-of-band routes; within each group, higher reward wins.
        refined.sortWith(
            compareByDescending<Pair<CandidateLoop, RefinedRoute>> { it.second.actualDistanceM in lo..hi }
                .thenByDescending { it.first.totalReward },
        )
        while (refined.size > maxKeep) {
            refined.removeAt(refined.lastIndex)
        }
    }

    internal fun estimateMaxReachable(
        corridors: List<Corridor>,
        connectors: List<com.velometrics.app.domain.model.CorridorConnector>,
    ): Double? {
        if (corridors.isEmpty() || connectors.isEmpty()) return null
        val totalCorridorLength = corridors.sumOf { it.lengthM }
        val avgConnectorDistance = connectors.map { it.distanceM }.average()
        return totalCorridorLength + avgConnectorDistance * corridors.size
    }
}
