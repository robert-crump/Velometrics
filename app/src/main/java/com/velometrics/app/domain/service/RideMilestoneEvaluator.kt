package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.RideRevealCandidate
import com.velometrics.app.domain.model.RideRevealFamily
import com.velometrics.app.domain.model.RideRevealPriority
import com.velometrics.app.domain.model.RideRevealScope
import com.velometrics.app.domain.repository.CyclingSessionRepository
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes ride-level milestone [RideRevealCandidate]s for a newly-revealed ride: whether it
 * ranks 1st-3rd all-time, or 1st-3rd within its own calendar year, on longest distance, most
 * elevation climbed, or fastest average speed. Plugs into [RideRevealEvaluator] alongside the
 * power-curve best-effort source (#160) and the guaranteed plain-stats fallback.
 *
 * "This year" is the calendar year the ride itself started in (system default time zone), not
 * wall-clock "now" — the two coincide for a ride that just synced, but this stays correct if a
 * batch is ever evaluated after the fact.
 */
@Singleton
class RideMilestoneEvaluator @Inject constructor(
    private val sessionRepository: CyclingSessionRepository
) {

    suspend fun candidates(session: CyclingSession): List<RideRevealCandidate> {
        val yearStart = startOfYear(session.sessionStart)
        val metrics = buildList {
            add(
                Metric(superlative = "longest", noun = "ride", value = session.distanceKm) { value, since ->
                    sessionRepository.countSessionsWithGreaterDistance(value, since)
                }
            )
            session.elevationGainM?.let { elevationGainM ->
                add(
                    Metric(superlative = "most", noun = "elevation climbed", value = elevationGainM) { value, since ->
                        sessionRepository.countSessionsWithGreaterElevationGain(value, since)
                    }
                )
            }
            if (session.netDurationSec > 0) {
                val averageSpeedKmh = session.distanceKm / session.netDurationSec * 3600
                add(
                    Metric(superlative = "fastest", noun = "average speed", value = averageSpeedKmh) { value, since ->
                        sessionRepository.countSessionsWithGreaterAverageSpeed(value, since)
                    }
                )
            }
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
            priority = RideRevealPriority(scope, rank, RideRevealFamily.RIDE_MILESTONE)
        )
    }

    private fun headline(metric: Metric, scope: RideRevealScope, rank: Int): String {
        val ordinalSuperlative = when (rank) {
            1 -> metric.superlative
            2 -> "2nd-${metric.superlative}"
            else -> "3rd-${metric.superlative}"
        }
        val scopeText = if (scope == RideRevealScope.ALL_TIME) "ever" else "this year"
        return "Your $ordinalSuperlative ${metric.noun} $scopeText!"
    }

    private fun startOfYear(instant: Instant): Instant {
        val zone = ZoneId.systemDefault()
        return instant.atZone(zone).toLocalDate().withDayOfYear(1).atStartOfDay(zone).toInstant()
    }

    /** One rankable ride-level metric: a headline vocabulary plus the count-greater-than query for it. */
    private class Metric(
        val superlative: String,
        val noun: String,
        val value: Double,
        val countGreater: suspend (value: Double, since: Instant?) -> Int
    )
}
