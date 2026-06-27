package com.velometrics.app.domain.service

import kotlin.math.roundToInt

data class DegradationConfig(
    val minDesiredCandidates: Int = 1,
    // Acceptance band on the *actual refined* distance vs target (+/- fraction). Base is ~[0.85,
    // 1.15]x target; the loosest tier widens it as a final fallback before failing.
    val baseDistanceBandFraction: Double = 0.15,
    val widenedDistanceBandFraction: Double = 0.30,
    // Skeleton levers: base value followed by the relaxed value each tier switches to. Every
    // successive tier turns one more lever on (cumulatively) so a retry genuinely changes the
    // geometry the orienteer can build, rather than tweaking knobs the deterministic skeleton
    // ignores.
    val baseHeadingConeCosine: Double = 0.0,        // cos 90deg cone
    val widenedHeadingConeCosine: Double = -0.5,    // cos 120deg cone
    val baseReachFraction: Double = 1.0 / 3.0,      // reach = target / 3
    val extendedReachFraction: Double = 0.5,        // reach = target / 2
    val baseSeparationM: Double = 2000.0,
    val shrunkSeparationM: Double = 1000.0,
)

object DegradationPolicy {

    /**
     * Relaxation order matches the skeleton's real levers, tightest first. Each tier turns its lever
     * on and keeps every earlier tier's lever on too (cumulative): widen the heading cone, then
     * extend the far-distance reach, then shrink the any-node separation, then widen the acceptable
     * distance band as a last resort.
     */
    enum class RelaxationTier {
        NONE,
        WIDEN_CONE,
        EXTEND_REACH,
        SHRINK_SEPARATION,
        WIDEN_BAND,
    }

    data class TierParams(
        val headingConeCosine: Double,
        val reachFraction: Double,
        val separationM: Double,
        val distanceBandFraction: Double,
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
        RelaxationTier.NONE -> RelaxationTier.WIDEN_CONE
        RelaxationTier.WIDEN_CONE -> RelaxationTier.EXTEND_REACH
        RelaxationTier.EXTEND_REACH -> RelaxationTier.SHRINK_SEPARATION
        RelaxationTier.SHRINK_SEPARATION -> RelaxationTier.WIDEN_BAND
        RelaxationTier.WIDEN_BAND -> null
    }

    fun tierParams(
        tier: RelaxationTier,
        config: DegradationConfig = DegradationConfig(),
    ): TierParams {
        val level = tier.ordinal
        val cone = if (level >= RelaxationTier.WIDEN_CONE.ordinal) {
            config.widenedHeadingConeCosine
        } else {
            config.baseHeadingConeCosine
        }
        val reach = if (level >= RelaxationTier.EXTEND_REACH.ordinal) {
            config.extendedReachFraction
        } else {
            config.baseReachFraction
        }
        val separation = if (level >= RelaxationTier.SHRINK_SEPARATION.ordinal) {
            config.shrunkSeparationM
        } else {
            config.baseSeparationM
        }
        val band = if (level >= RelaxationTier.WIDEN_BAND.ordinal) {
            config.widenedDistanceBandFraction
        } else {
            config.baseDistanceBandFraction
        }
        return TierParams(cone, reach, separation, band)
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

    /**
     * Acceptance is judged on the **actual refined distance**: a candidate counts only when it lands
     * within the current tier's `[1 - band, 1 + band]` x target window. When too few candidates
     * qualify, the next relaxation tier is requested; at the loosest tier any in-band candidate is
     * accepted, and zero in-band candidates is a hard failure with a clear reason.
     */
    fun evaluate(
        candidateDistances: List<Double>,
        requestedDistanceM: Double,
        currentTier: RelaxationTier,
        maxReachableDistanceM: Double? = null,
        config: DegradationConfig = DegradationConfig(),
    ): EvaluationOutcome {
        val band = tierParams(currentTier, config).distanceBandFraction
        val lower = requestedDistanceM * (1.0 - band)
        val upper = requestedDistanceM * (1.0 + band)
        val inBand = candidateDistances.filter { it in lower..upper }
        val reports = reportCandidates(inBand, requestedDistanceM)

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
