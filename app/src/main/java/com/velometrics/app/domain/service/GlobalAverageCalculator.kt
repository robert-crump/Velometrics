package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession

/**
 * Computes an equal-weight average distribution across rides: each ride is first normalized to
 * its own percentage-of-time per label, then those percentages are averaged across rides — so a
 * 20-minute ride and a 5-hour ride count the same, matching the existing per-route speed
 * histogram average in RepeatedRouteDetailViewModel.
 */
object GlobalAverageCalculator {

    fun computePowerZoneAverages(sessions: List<CyclingSession>): Map<String, Float> =
        averagePercentages(sessions.mapNotNull { it.powerZoneDistribution })

    fun computeHrZoneAverages(sessions: List<CyclingSession>): Map<String, Float> =
        averagePercentages(sessions.mapNotNull { it.hrZoneDistribution })

    fun computeSpeedHistogramAverages(sessions: List<CyclingSession>): Map<String, Float> =
        averagePercentages(sessions.map { it.speedHistogram })

    private fun averagePercentages(distributions: List<Map<String, Int>>): Map<String, Float> {
        if (distributions.isEmpty()) return emptyMap()

        val labels = distributions.flatMap { it.keys }.toSet()
        return labels.associateWith { label ->
            distributions.map { dist ->
                val total = dist.values.sum().coerceAtLeast(1)
                (dist[label] ?: 0).toFloat() / total * 100f
            }.average().toFloat()
        }
    }
}
