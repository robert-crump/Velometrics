package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.RideRevealCandidate
import com.velometrics.app.domain.model.RideRevealFamily
import com.velometrics.app.domain.model.RideRevealPriority
import com.velometrics.app.domain.model.RideRevealScope
import com.velometrics.app.domain.repository.BestEffortRepository
import java.time.Instant
import java.time.ZoneId
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
        val yearStart = startOfYear(sessionStart)

        val metrics = buildList {
            own.power5s?.let { add(Metric(label = "5-second power", value = it) { value, since ->
                bestEffortRepository.countBestEffortsWithGreaterPower5s(value, since)
            }) }
            own.power1m?.let { add(Metric(label = "1-minute power", value = it) { value, since ->
                bestEffortRepository.countBestEffortsWithGreaterPower1m(value, since)
            }) }
            own.power5m?.let { add(Metric(label = "5-minute power", value = it) { value, since ->
                bestEffortRepository.countBestEffortsWithGreaterPower5m(value, since)
            }) }
            own.power20m?.let { add(Metric(label = "20-minute power", value = it) { value, since ->
                bestEffortRepository.countBestEffortsWithGreaterPower20m(value, since)
            }) }
        }

        return metrics.flatMap { metric ->
            val allTimeCount = metric.countGreater(metric.value, null)
            val thisYearCount = metric.countGreater(metric.value, yearStart)
            listOfNotNull(
                rankCandidate(metric, RideRevealScope.ALL_TIME, allTimeCount),
                rankCandidate(metric, RideRevealScope.THIS_YEAR, thisYearCount)
            )
        }
    }

    private fun rankCandidate(metric: Metric, scope: RideRevealScope, countGreater: Int): RideRevealCandidate? {
        val rank = countGreater + 1
        if (rank > 3) return null
        return RideRevealCandidate(
            headline = headline(metric, scope, rank),
            priority = RideRevealPriority(scope, rank, RideRevealFamily.POWER_CURVE_BEST_EFFORT)
        )
    }

    private fun headline(metric: Metric, scope: RideRevealScope, rank: Int): String {
        val ordinalBest = when (rank) {
            1 -> "best"
            2 -> "2nd-best"
            else -> "3rd-best"
        }
        val scopeText = if (scope == RideRevealScope.ALL_TIME) "ever" else "this year"
        return "Your $ordinalBest ${metric.label} $scopeText!"
    }

    private fun startOfYear(instant: Instant): Instant {
        val zone = ZoneId.systemDefault()
        return instant.atZone(zone).toLocalDate().withDayOfYear(1).atStartOfDay(zone).toInstant()
    }

    /** One rankable power-curve duration: a headline label plus the count-greater-than query for it. */
    private class Metric(
        val label: String,
        val value: Int,
        val countGreater: suspend (value: Int, since: Instant?) -> Int
    )
}
