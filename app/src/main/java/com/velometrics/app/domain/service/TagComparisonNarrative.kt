package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.RideTag
import com.velometrics.app.util.FormatUtils
import java.util.Locale
import kotlin.math.abs

/**
 * Templated tag-scoped comparison narrative (#171), shown when a ride's tag label is expanded on
 * Session Detail: compares this ride to its own tag-scoped "last 5 [tag] rides" pool ([comparison],
 * which must come from [SessionComparator.computeComparison] called with this same [tag]).
 *
 * Each [RideTag] has one designated "main value" the sentence leads with (per-user feedback,
 * 2026-08-31) — the metric that actually defines what that tag is about, not just whichever moved
 * the most this ride: [RideTag.ZONE_2] leads with fat efficiency, [RideTag.INTERVALS] with the
 * interval count, [RideTag.RECOVERY] with time spent below 60% of FTP. When that main value isn't
 * available for this ride or its comparison pool (missing power data, say), the sentence falls
 * back to whichever remaining candidate KPI deviates most from the pool's median, ranked by
 * *relative* deviation (`|current - median| / median`) so metrics on different scales (a
 * percentage, a ratio near 1.0, watts) compare fairly. Distance is the one candidate never gated
 * on power/HR data, guaranteeing a sentence whenever there's enough tag-scoped history at all,
 * even for a power-and-HR-less ride.
 *
 * For [RideTag.INTERVALS], a second sentence is appended naming the total time spent in
 * intervals against the pool's median, whenever that median is available (older rides that
 * predate [CyclingSession.intervalTotalTimeSec]'s tracking may not have one), and a third (#186)
 * flags how many of this ride's rest gaps between intervals ([IntervalSession.restBeforeNextIntervalSec])
 * fell outside the recommended 2-3min recovery band, whenever there are at least 2 intervals (i.e.
 * at least one gap to evaluate).
 */
object TagComparisonNarrative {

    /** Below this many tag-scoped prior rides, there's nothing meaningful to compare against. */
    private const val MIN_TAG_SCOPED_SAMPLES = 2

    /** Recommended recovery band (#186): gaps outside [MIN_REST_GAP_SEC, MAX_REST_GAP_SEC] are flagged. */
    private const val MIN_REST_GAP_SEC = 120
    private const val MAX_REST_GAP_SEC = 180

    private class Candidate(val relativeDeviation: Double, val sentence: String)

    fun generate(
        session: CyclingSession,
        tag: String,
        comparison: SessionComparison,
        intervals: List<IntervalSession> = emptyList()
    ): String {
        if (comparison.last5SessionCount < MIN_TAG_SCOPED_SAMPLES) {
            return "Not enough history for $tag rides yet."
        }

        val mainValue = mainValueCandidate(session, tag, comparison, intervals)
        if (mainValue != null) return mainValue.sentence

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

    /** The one KPI that defines each tag (see class doc) — null when that tag has no ride data
     *  for it yet, or isn't [RideTag]-recognized, so [generate] falls back to deviation ranking. */
    private fun mainValueCandidate(
        session: CyclingSession,
        tag: String,
        comparison: SessionComparison,
        intervals: List<IntervalSession>
    ): Candidate? =
        when (tag) {
            RideTag.ZONE_2.label -> fatEfficiencyCandidate(session, tag, comparison)
            RideTag.INTERVALS.label -> intervalCountCandidate(session, tag, comparison, intervals)
            RideTag.RECOVERY.label -> timeBelowSixtyPercentFtpCandidate(session, tag, comparison)
            else -> null
        }

    private fun intervalCountCandidate(
        session: CyclingSession,
        tag: String,
        comparison: SessionComparison,
        intervals: List<IntervalSession>
    ): Candidate? {
        val current = session.intervalCount
        val median = comparison.medianIntervalCountLast5 ?: return null
        val direction = if (current > median) "more" else "fewer"
        var sentence = "You did $current intervals, $direction than your typical $median for $tag rides."
        timeInIntervalsSentence(session, comparison)?.let { sentence = "$sentence $it" }
        restGapSentence(intervals)?.let { sentence = "$sentence $it" }
        return Candidate(relativeDeviation(current.toDouble(), median.toDouble()), sentence)
    }

    private fun timeInIntervalsSentence(session: CyclingSession, comparison: SessionComparison): String? {
        val current = session.intervalTotalTimeSec
        val median = comparison.medianIntervalTotalTimeSecLast5 ?: return null
        return "You spent ${FormatUtils.formatDuration(current)} in intervals " +
            "(median: ${FormatUtils.formatDuration(median)})."
    }

    /**
     * Flags rest gaps between intervals (#186) that fall outside the recommended 2-3min recovery
     * band, in either direction. Needs at least 2 intervals (i.e. at least one gap to evaluate) —
     * null below that — and also returns null when every gap is within the band (nothing to flag).
     */
    private fun restGapSentence(intervals: List<IntervalSession>): String? {
        if (intervals.size < 2) return null
        val gaps = intervals.mapNotNull { it.restBeforeNextIntervalSec }
        if (gaps.isEmpty()) return null

        val tooShort = gaps.count { it < MIN_REST_GAP_SEC }
        val tooLong = gaps.count { it > MAX_REST_GAP_SEC }
        if (tooShort == 0 && tooLong == 0) return null

        val total = gaps.size
        return when {
            tooShort > 0 && tooLong > 0 ->
                "$tooShort of $total rest gaps were shorter than the recommended 2-3min, " +
                    "$tooLong ${if (tooLong == 1) "was" else "were"} longer."
            tooShort > 0 ->
                "$tooShort of $total rest gaps were shorter than the recommended 2-3min."
            else ->
                "$tooLong of $total rest gaps were longer than the recommended 2-3min."
        }
    }

    private fun timeBelowSixtyPercentFtpCandidate(session: CyclingSession, tag: String, comparison: SessionComparison): Candidate? {
        val current = session.timeBelowSixtyPercentFtpSec ?: return null
        val median = comparison.medianTimeBelowSixtyPercentFtpSecLast5 ?: return null
        val direction = if (current > median) "more" else "less"
        val sentence = "You spent ${FormatUtils.formatDuration(current)} below 60% of FTP, $direction than your " +
            "typical ${FormatUtils.formatDuration(median)} for $tag rides."
        return Candidate(relativeDeviation(current.toDouble(), median.toDouble()), sentence)
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
