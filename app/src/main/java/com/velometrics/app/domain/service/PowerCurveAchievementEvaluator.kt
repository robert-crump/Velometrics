package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.RideRevealCandidate
import com.velometrics.app.domain.model.RideRevealFamily
import com.velometrics.app.domain.repository.BestEffortRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes power-curve best-effort [RideRevealCandidate]s for a newly-revealed ride: whether its
 * 5-second, 1-minute, 5-minute, or 20-minute best power (as already computed by
 * [BestEffortCalculator] at import time) ranks 1st-3rd all-time, or 1st-3rd within its own
 * calendar year. Plugs into [RideRevealEvaluator] alongside the ride-level milestone source (#159)
 * and the guaranteed plain-stats fallback; per [RideRevealFamily]'s ordering, this loses a tie
 * against a ride-level milestone at the same scope and rank.
 *
 * "This year" is the calendar year the ride itself started in (system default time zone), matching
 * [RideMilestoneEvaluator]'s convention.
 */
@Singleton
class PowerCurveAchievementEvaluator @Inject constructor(
    private val bestEffortRepository: BestEffortRepository
) {

    suspend fun candidates(sessionId: Long, sessionStart: Instant): List<RideRevealCandidate> {
        val own = bestEffortRepository.getForSession(sessionId) ?: return emptyList()
        val yearStart = RankedMetricEvaluator.startOfYear(sessionStart)

        val metrics = buildList {
            own.power5s?.let { add(RankedMetricEvaluator.Metric(ordinalWord = "best", noun = "5-second power", value = it) { value, since ->
                bestEffortRepository.countBestEffortsWithGreaterPower5s(value, since)
            }) }
            own.power1m?.let { add(RankedMetricEvaluator.Metric(ordinalWord = "best", noun = "1-minute power", value = it) { value, since ->
                bestEffortRepository.countBestEffortsWithGreaterPower1m(value, since)
            }) }
            own.power5m?.let { add(RankedMetricEvaluator.Metric(ordinalWord = "best", noun = "5-minute power", value = it) { value, since ->
                bestEffortRepository.countBestEffortsWithGreaterPower5m(value, since)
            }) }
            own.power20m?.let { add(RankedMetricEvaluator.Metric(ordinalWord = "best", noun = "20-minute power", value = it) { value, since ->
                bestEffortRepository.countBestEffortsWithGreaterPower20m(value, since)
            }) }
        }

        return RankedMetricEvaluator.rank(metrics, yearStart, RideRevealFamily.POWER_CURVE_BEST_EFFORT)
    }
}
