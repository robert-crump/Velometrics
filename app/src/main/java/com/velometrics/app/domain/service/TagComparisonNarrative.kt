package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import java.util.Locale
import kotlin.math.abs

/**
 * Templated tag-scoped comparison narrative (#171), shown when a ride's tag label is expanded on
 * Session Detail: compares this ride to its own tag-scoped "last 5 [tag] rides" pool ([comparison],
 * which must come from [SessionComparator.computeComparison] called with this same [tag]) and
 * leads with whichever KPI deviates most from that pool's median — a low-drift Zone 2 ride leads
 * with drift, a high-power Intervals ride leads with power, rather than a hardcoded metric order.
 *
 * Candidate KPIs are ranked by *relative* deviation (`|current - median| / median`) so metrics on
 * different scales (a percentage, a ratio near 1.0, watts) compare fairly. Distance is the one
 * candidate never gated on power/HR data, guaranteeing a sentence whenever there's enough
 * tag-scoped history at all, even for a power-and-HR-less ride.
 */
object TagComparisonNarrative {

    /** Below this many tag-scoped prior rides, there's nothing meaningful to compare against. */
    private const val MIN_TAG_SCOPED_SAMPLES = 2

    private class Candidate(val relativeDeviation: Double, val sentence: String)

    fun generate(session: CyclingSession, tag: String, comparison: SessionComparison): String {
        if (comparison.last5SessionCount < MIN_TAG_SCOPED_SAMPLES) {
            return "Not enough history for $tag rides yet."
        }

        val candidates = listOfNotNull(
            cardiacDriftCandidate(session, tag, comparison),
            npToApCandidate(session, tag, comparison),
            fatEfficiencyCandidate(session, tag, comparison),
            avgPowerCandidate(session, tag, comparison),
            distanceCandidate(session, tag, comparison)
        )

        return candidates.maxByOrNull { it.relativeDeviation }?.sentence
            ?: "Not enough history for $tag rides yet."
    }

    private fun relativeDeviation(current: Double, median: Double): Double =
        abs(current - median) / abs(median).coerceAtLeast(0.0001)

    private fun cardiacDriftCandidate(session: CyclingSession, tag: String, comparison: SessionComparison): Candidate? {
        val current = session.cardiacDriftPercent ?: return null
        val median = comparison.medianCardiacDriftPercentLast5 ?: return null
        val direction = if (current < median) "lower" else "higher"
        val sentence = "Your cardiac drift was %.1f%%, $direction than your typical %.1f%% for $tag rides."
            .format(Locale.US, current, median)
        return Candidate(relativeDeviation(current, median), sentence)
    }

    private fun npToApCandidate(session: CyclingSession, tag: String, comparison: SessionComparison): Candidate? {
        val avg = session.averagePower ?: return null
        val np = session.normalizedPower ?: return null
        if (avg == 0) return null
        val median = comparison.medianNpToApRatioLast5 ?: return null
        val current = np.toDouble() / avg
        val direction = if (current < median) "steadier" else "more variable"
        val sentence = "Your power was $direction than usual for $tag rides (NP:AP %.2f vs. your typical %.2f)."
            .format(Locale.US, current, median)
        return Candidate(relativeDeviation(current, median), sentence)
    }

    private fun fatEfficiencyCandidate(session: CyclingSession, tag: String, comparison: SessionComparison): Candidate? {
        val current = session.fatEfficiencyScore ?: return null
        val median = comparison.medianFatEfficiencyLast5 ?: return null
        val direction = if (current > median) "above" else "below"
        val sentence = "Your fat efficiency score was $current, $direction your typical %.0f for $tag rides."
            .format(Locale.US, median)
        return Candidate(relativeDeviation(current.toDouble(), median), sentence)
    }

    private fun avgPowerCandidate(session: CyclingSession, tag: String, comparison: SessionComparison): Candidate? {
        val current = session.averagePower ?: return null
        val median = comparison.medianAvgPowerLast5 ?: return null
        val direction = if (current > median) "above" else "below"
        val sentence = "Your average power was ${current}W, $direction your typical ${median}W for $tag rides."
        return Candidate(relativeDeviation(current.toDouble(), median.toDouble()), sentence)
    }

    private fun distanceCandidate(session: CyclingSession, tag: String, comparison: SessionComparison): Candidate? {
        val median = comparison.medianDistanceKmLast5 ?: return null
        val current = session.distanceKm
        val direction = if (current > median) "longer" else "shorter"
        val sentence = "This ride was %.1f km, $direction than your typical %.1f km for $tag rides."
            .format(Locale.US, current, median)
        return Candidate(relativeDeviation(current, median), sentence)
    }
}
