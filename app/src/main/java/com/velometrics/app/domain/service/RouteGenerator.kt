package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.domain.repository.MapGraphRepository

data class GeneratorConfig(
    val orienteerConfig: OrienteerConfig = OrienteerConfig(),
    val refinerConfig: RefinerConfig = RefinerConfig(),
    val degradationConfig: DegradationConfig = DegradationConfig(),
    val hazardConfig: HazardConfig = HazardConfig(),
    val junctionCostConfig: JunctionCostConfig = JunctionCostConfig(),
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

    suspend fun generate(
        homeLat: Double,
        homeLon: Double,
        targetDistanceM: Double,
        repository: MapGraphRepository,
        weights: RewardWeights = RewardWeights(),
        rewardContext: RewardContext = RewardContext(),
        config: GeneratorConfig = GeneratorConfig(),
    ): RoutePlanResult {
        val corridors = repository.getAllCorridors()
        val connectors = repository.getAllCorridorConnectors()

        if (corridors.isEmpty()) {
            return RoutePlanResult.Failure(
                "No corridor data available. Check that your home is set near mapped cycling roads.",
            )
        }

        val corridorMap = corridors.associateBy { it.id }

        var currentTier = DegradationPolicy.RelaxationTier.NONE
        val allRefined = mutableListOf<Pair<CandidateLoop, RefinedRoute>>()

        while (true) {
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
            val tierOrienteerConfig = config.orienteerConfig.copy(
                reusePenaltyWeight = tierParams.reusePenaltyWeight,
            )

            val coarseCandidates = CorridorOrienteer.search(
                corridors, connectors, homeLat, homeLon,
                targetDistanceM,
                weights, tierRewardContext, tierOrienteerConfig, config.seed,
            )

            for (candidate in coarseCandidates) {
                if (allRefined.any { it.first.corridors == candidate.corridors }) continue

                val refined = RouteRefiner.refine(
                    candidate, corridorMap, repository,
                    weights, tierRewardContext,
                    config.hazardConfig, config.junctionCostConfig, config.refinerConfig,
                )
                if (refined != null) {
                    allRefined.add(candidate to refined)
                }
            }

            val refinedDistances = allRefined.map { it.second.actualDistanceM }
            val maxReachable = estimateMaxReachable(corridors, connectors)

            val outcome = DegradationPolicy.evaluate(
                refinedDistances, targetDistanceM, currentTier,
                maxReachable, config.degradationConfig,
            )

            when (outcome) {
                is DegradationPolicy.EvaluationOutcome.Sufficient -> {
                    val ranked = allRefined
                        .sortedByDescending { it.second.totalReward }
                        .mapIndexed { index, (coarse, refined) ->
                            val deviation = if (targetDistanceM > 0) {
                                (refined.actualDistanceM - targetDistanceM) / targetDistanceM * 100.0
                            } else {
                                0.0
                            }
                            RankedCandidate(refined, coarse, index + 1, deviation)
                        }
                    return RoutePlanResult.Success(ranked, outcome.appliedTier)
                }

                is DegradationPolicy.EvaluationOutcome.NeedsRelaxation -> {
                    currentTier = outcome.nextTier
                }

                is DegradationPolicy.EvaluationOutcome.HardFailure -> {
                    return RoutePlanResult.Failure(outcome.reason)
                }
            }
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
