package com.velometrics.app.domain.service

import android.util.Log
import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.domain.repository.MapGraphRepository
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class GeneratorConfig(
    val orienteerConfig: OrienteerConfig = OrienteerConfig(),
    val refinerConfig: RefinerConfig = RefinerConfig(),
    val degradationConfig: DegradationConfig = DegradationConfig(),
    val direction: RideDirection? = null,
    val seed: Long = System.currentTimeMillis(),
)

data class RankedCandidate(
    val refinedRoute: RefinedRoute,
    val coarseLoop: CandidateLoop,
    val rank: Int,
    val distanceDeviationPercent: Double,
)

sealed interface RoutePlanResult {
    data class Success(
        val candidates: List<RankedCandidate>,
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

        val maxCandidates = config.orienteerConfig.candidateCount
        var currentTier = DegradationPolicy.RelaxationTier.NONE
        val allRefined = mutableListOf<Pair<CandidateLoop, RefinedRoute>>()

        while (true) {
            coroutineContext.ensureActive()
            val tierStart = System.currentTimeMillis()
            Log.d(TAG, "generate: tier=$currentTier starting")

            val tierParams = DegradationPolicy.tierParams(
                currentTier,
                baseExploreExploitBalance = rewardContext.exploreExploitBalance,
                baseReusePenaltyWeight = config.orienteerConfig.reusePenaltyWeight,
                config = config.degradationConfig,
            )

            val tierRewardContext = RewardContext(
                exploreExploitBalance = tierParams.exploreExploitBalance,
                confidenceFloor = rewardContext.confidenceFloor,
            )
            val refinementCount = maxCandidates * REFINEMENT_CANDIDATE_MULTIPLIER
            val tierOrienteerConfig = config.orienteerConfig.copy(
                reusePenaltyWeight = tierParams.reusePenaltyWeight,
                candidateCount = refinementCount,
            )

            val coarseStart = System.currentTimeMillis()
            val coarseCandidates = CorridorOrienteer.search(
                corridors, connectors, homeLat, homeLon,
                targetDistanceM,
                weights, tierRewardContext, tierOrienteerConfig, config.seed,
                config.direction,
            )
            Log.d(TAG, "generate: coarse search found ${coarseCandidates.size} candidates in ${System.currentTimeMillis() - coarseStart}ms")

            for ((idx, candidate) in coarseCandidates.withIndex()) {
                if (allRefined.any { it.first.corridors == candidate.corridors }) continue

                val refineStart = System.currentTimeMillis()
                val refined = RouteRefiner.refine(
                    candidate, corridorMap, repository,
                    config.refinerConfig,
                )
                Log.d(TAG, "generate: refine candidate[$idx] corridors=${candidate.corridors.size} -> ${if (refined != null) "${refined.edges.size} edges, ${refined.actualDistanceM.toInt()}m" else "null"} in ${System.currentTimeMillis() - refineStart}ms")
                if (refined != null) {
                    allRefined.add(candidate to refined)
                }
            }

            trimRefined(allRefined, maxCandidates * REFINED_BUFFER_FACTOR)

            val refinedDistances = allRefined.map { it.second.actualDistanceM }
            val maxReachable = estimateMaxReachable(corridors, connectors)

            val outcome = DegradationPolicy.evaluate(
                refinedDistances, targetDistanceM, currentTier,
                maxReachable, config.degradationConfig,
            )
            Log.d(TAG, "generate: tier=$currentTier outcome=${outcome::class.simpleName} refined=${allRefined.size} tierTime=${System.currentTimeMillis() - tierStart}ms")

            when (outcome) {
                is DegradationPolicy.EvaluationOutcome.Sufficient -> {
                    val ranked = allRefined
                        .sortedByDescending { it.first.totalReward }
                        .take(maxCandidates)
                        .mapIndexed { index, (coarse, refined) ->
                            val deviation = if (targetDistanceM > 0) {
                                (refined.actualDistanceM - targetDistanceM) / targetDistanceM * 100.0
                            } else {
                                0.0
                            }
                            RankedCandidate(refined, coarse, index + 1, deviation)
                        }
                    Log.d(TAG, "generate: done in ${System.currentTimeMillis() - genStart}ms, ${ranked.size} candidates")
                    return RoutePlanResult.Success(ranked, outcome.appliedTier)
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

    private fun trimRefined(
        refined: MutableList<Pair<CandidateLoop, RefinedRoute>>,
        maxKeep: Int,
    ) {
        if (refined.size <= maxKeep) return
        refined.sortByDescending { it.first.totalReward }
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
