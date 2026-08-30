package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.RideRevealCandidate
import com.velometrics.app.domain.model.RideRevealFamily
import com.velometrics.app.domain.model.RideRevealPriority
import com.velometrics.app.domain.model.RideRevealScope
import java.time.Instant
import java.time.ZoneId

/**
 * Shared ranking machinery for [RideRevealCandidate] sources that rank a session's own metric
 * value against every other session/best-effort via a count-greater-than query, producing a
 * candidate whenever that rank is 1st-3rd, at both [RideRevealScope.ALL_TIME] and
 * [RideRevealScope.THIS_YEAR]. Used by [RideMilestoneEvaluator] and
 * [PowerCurveAchievementEvaluator], which differ only in which metrics they build and which
 * repository queries back the count-greater-than lookups.
 */
object RankedMetricEvaluator {

    /** One rankable metric: a headline vocabulary plus the count-greater-than query for it. */
    class Metric<T>(
        val ordinalWord: String,
        val noun: String,
        val value: T,
        val countGreater: suspend (value: T, since: Instant?) -> Int
    )

    suspend fun <T> rank(
        metrics: List<Metric<T>>,
        yearStart: Instant,
        family: RideRevealFamily
    ): List<RideRevealCandidate> {
        return metrics.flatMap { metric ->
            val allTimeCount = metric.countGreater(metric.value, null)
            val thisYearCount = metric.countGreater(metric.value, yearStart)
            listOfNotNull(
                rankCandidate(metric, RideRevealScope.ALL_TIME, allTimeCount, family),
                rankCandidate(metric, RideRevealScope.THIS_YEAR, thisYearCount, family)
            )
        }
    }

    fun startOfYear(instant: Instant): Instant {
        val zone = ZoneId.systemDefault()
        return instant.atZone(zone).toLocalDate().withDayOfYear(1).atStartOfDay(zone).toInstant()
    }

    private fun <T> rankCandidate(
        metric: Metric<T>,
        scope: RideRevealScope,
        countGreater: Int,
        family: RideRevealFamily
    ): RideRevealCandidate? {
        val rank = countGreater + 1
        if (rank > 3) return null
        return RideRevealCandidate(
            headline = headline(metric, scope, rank),
            priority = RideRevealPriority(scope, rank, family)
        )
    }

    private fun <T> headline(metric: Metric<T>, scope: RideRevealScope, rank: Int): String {
        val ordinal = when (rank) {
            1 -> metric.ordinalWord
            2 -> "2nd-${metric.ordinalWord}"
            else -> "3rd-${metric.ordinalWord}"
        }
        val scopeText = if (scope == RideRevealScope.ALL_TIME) "ever" else "this year"
        return "Your $ordinal ${metric.noun} $scopeText!"
    }
}
