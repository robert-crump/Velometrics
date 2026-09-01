package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.RideRevealCandidate
import com.velometrics.app.domain.model.RideRevealFamily
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.util.FormatUtils
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
        val yearStart = RankedMetricEvaluator.startOfYear(session.sessionStart)
        val metrics = buildList {
            add(
                RankedMetricEvaluator.Metric(
                    ordinalWord = "longest", noun = "ride", value = session.distanceKm, format = FormatUtils::formatDistance
                ) { value, since ->
                    sessionRepository.countSessionsWithGreaterDistance(value, since)
                }
            )
            session.elevationGainM?.let { elevationGainM ->
                add(
                    RankedMetricEvaluator.Metric(
                        ordinalWord = "most", noun = "elevation climbed", value = elevationGainM, format = FormatUtils::formatElevationGain
                    ) { value, since ->
                        sessionRepository.countSessionsWithGreaterElevationGain(value, since)
                    }
                )
            }
            if (session.netDurationSec > 0) {
                val averageSpeedKmh = session.distanceKm / session.netDurationSec * 3600
                add(
                    RankedMetricEvaluator.Metric(
                        ordinalWord = "fastest", noun = "average speed", value = averageSpeedKmh, format = FormatUtils::formatSpeed
                    ) { value, since ->
                        sessionRepository.countSessionsWithGreaterAverageSpeed(value, since)
                    }
                )
            }
        }

        return RankedMetricEvaluator.rank(metrics, yearStart, RideRevealFamily.RIDE_MILESTONE)
    }
}
