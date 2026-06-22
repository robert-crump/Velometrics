package com.velometrics.app.domain.service

import kotlin.math.roundToInt

data class DegradationConfig(
    val minDesiredCandidates: Int = 3,
    val baseDistanceBandFraction: Double = 0.15,
    val widenedDistanceBandFraction: Double = 0.30,
    val easedExploreExploitBalance: Double = 0.6,
    val easedReusePenaltyWeight: Double = 0.5,
)

object DegradationPolicy {

    enum class RelaxationTier {
        NONE,
        WIDEN_BAND,
        EASE_DISCOVERY,
        EASE_PENALTIES,
    }

    data class TierParams(
        val distanceBandFraction: Double,
        val exploreExploitBalance: Double,
        val reusePenaltyWeight: Double,
    )

    data class CandidateReport(
        val actualDistanceM: Double,
        val requestedDistanceM: Double,
        val distanceDeviationPercent: Double,
    )

    sealed interface EvaluationOutcome {
        data class Sufficient(
            val candidates: List<CandidateReport>,
            val appliedTier: RelaxationTier,
        ) : EvaluationOutcome

        data class NeedsRelaxation(
            val candidates: List<CandidateReport>,
            val nextTier: RelaxationTier,
        ) : EvaluationOutcome

        data class HardFailure(
            val reason: String,
        ) : EvaluationOutcome
    }

    fun nextTier(currentTier: RelaxationTier): RelaxationTier? = when (currentTier) {
        RelaxationTier.NONE -> RelaxationTier.WIDEN_BAND
        RelaxationTier.WIDEN_BAND -> RelaxationTier.EASE_DISCOVERY
        RelaxationTier.EASE_DISCOVERY -> RelaxationTier.EASE_PENALTIES
        RelaxationTier.EASE_PENALTIES -> null
    }

    fun tierParams(
        tier: RelaxationTier,
        baseExploreExploitBalance: Double = RewardContext().exploreExploitBalance,
        baseReusePenaltyWeight: Double = OrienteerConfig().reusePenaltyWeight,
        config: DegradationConfig = DegradationConfig(),
    ): TierParams {
        val bandFraction = if (tier == RelaxationTier.NONE) {
            config.baseDistanceBandFraction
        } else {
            config.widenedDistanceBandFraction
        }
        val explore = when (tier) {
            RelaxationTier.NONE, RelaxationTier.WIDEN_BAND -> baseExploreExploitBalance
            else -> config.easedExploreExploitBalance
        }
        val reuse = if (tier == RelaxationTier.EASE_PENALTIES) {
            config.easedReusePenaltyWeight
        } else {
            baseReusePenaltyWeight
        }
        return TierParams(bandFraction, explore, reuse)
    }

    fun reportCandidates(
        candidateDistances: List<Double>,
        requestedDistanceM: Double,
    ): List<CandidateReport> = candidateDistances.map { actual ->
        CandidateReport(
            actualDistanceM = actual,
            requestedDistanceM = requestedDistanceM,
            distanceDeviationPercent = if (requestedDistanceM > 0) {
                (actual - requestedDistanceM) / requestedDistanceM * 100.0
            } else {
                0.0
            },
        )
    }

    fun evaluate(
        candidateDistances: List<Double>,
        requestedDistanceM: Double,
        currentTier: RelaxationTier,
        maxReachableDistanceM: Double? = null,
        config: DegradationConfig = DegradationConfig(),
    ): EvaluationOutcome {
        val reports = reportCandidates(candidateDistances, requestedDistanceM)

        if (reports.size >= config.minDesiredCandidates) {
            return EvaluationOutcome.Sufficient(reports, currentTier)
        }

        val next = nextTier(currentTier)
        if (next != null) {
            return EvaluationOutcome.NeedsRelaxation(reports, next)
        }

        return if (reports.isNotEmpty()) {
            EvaluationOutcome.Sufficient(reports, currentTier)
        } else {
            EvaluationOutcome.HardFailure(
                failureReason(requestedDistanceM, maxReachableDistanceM),
            )
        }
    }

    fun failureReason(
        requestedDistanceM: Double,
        maxReachableDistanceM: Double?,
    ): String {
        if (maxReachableDistanceM != null && requestedDistanceM > maxReachableDistanceM) {
            val requestedKm = (requestedDistanceM / 1000.0).roundToInt()
            val reachableKm = (maxReachableDistanceM / 1000.0).roundToInt()
            return "Requested distance ($requestedKm km) exceeds the maximum reachable loop" +
                " distance from home ($reachableKm km). Try a shorter distance."
        }
        return "Could not generate a loop from your home location." +
            " Check that your home is set near mapped cycling roads."
    }
}
