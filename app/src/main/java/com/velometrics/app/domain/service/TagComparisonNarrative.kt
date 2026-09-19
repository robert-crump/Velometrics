package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.RideTag
import com.velometrics.app.domain.model.energy
import com.velometrics.app.util.FormatUtils
import java.util.Locale
import kotlin.math.abs

/**
 * Templated tag-scoped comparison narrative (#171, all-time pool since #214): compares this ride to
 * every earlier ride sharing its tag ([comparison], from [SessionComparator.computeTagComparison]
 * with this same [tag] — [SessionNarrativeAssembler] is the single call path that guarantees it).
 *
 * [RideTag.ZONE_2], [RideTag.INTERVALS] and [RideTag.RECOVERY] render fixed metric lists
 * ([zone2Narrative], [intervalsNarrative], [recoveryNarrative]). Any other tag (custom or legacy)
 * uses the single-sentence model below: the sentence leads with whichever candidate KPI deviates
 * most from the pool's median, ranked by *relative* deviation (`|current - median| / median`) so
 * metrics on different scales (a percentage, a ratio near 1.0, watts) compare fairly. Distance is
 * the one candidate never gated on power/HR data, guaranteeing a sentence whenever there's enough
 * tag-scoped history at all,
 * even for a power-and-HR-less ride.
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
        comparison: TagComparison,
        intervals: List<IntervalSession> = emptyList()
    ): String {
        val notEnoughHistory = notEnoughHistory(tag)
        if (comparison.sampleCount < MIN_TAG_SCOPED_SAMPLES) return notEnoughHistory

        if (tag == RideTag.ZONE_2.label) return zone2Narrative(session, tag, comparison.medians) ?: notEnoughHistory
        if (tag == RideTag.RECOVERY.label) return recoveryNarrative(session, tag, comparison.medians) ?: notEnoughHistory
        if (tag == RideTag.INTERVALS.label) {
            return intervalsNarrative(session, tag, comparison.medians, intervals) ?: notEnoughHistory
        }

        val candidates = listOfNotNull(
            cardiacDriftCandidate(session, tag, comparison),
            npToApCandidate(session, tag, comparison),
            fatEfficiencyCandidate(session, tag, comparison),
            avgPowerCandidate(session, tag, comparison),
            distanceCandidate(session, tag, comparison)
        )

        return candidates.maxByOrNull { it.relativeDeviation }?.sentence
            ?: notEnoughHistory
    }

    fun notEnoughHistory(tag: String): String = "Not enough history for $tag rides yet."

    /** One "value (vs. median)" pair (see [zone2Narrative]). */
    private class Metric(val value: String, val median: String)

    /**
     * Zone 2's fixed metric list (#214): fat efficiency + fat grams, then duration + avg power, then
     * cardiac drift. Each metric drops out independently when this ride or the pool lacks it; the
     * first one that renders carries the "in a typical [tag] ride" qualifier. Null if none render.
     */
    private fun zone2Narrative(session: CyclingSession, tag: String, m: PoolMedians): String? {
        val fatEfficiency = session.fatEfficiencyScore?.let { cur ->
            m.fatEfficiency?.let { Metric("$cur", "%.0f".format(Locale.US, it)) }
        }
        val fatGrams = session.energy?.fatGrams?.let { cur ->
            m.fatGrams?.let { Metric("%.0fg".format(Locale.US, cur), "%.0fg".format(Locale.US, it)) }
        }
        val duration = m.netDurationSec?.let {
            Metric(compactDuration(session.netDurationSec), compactDuration(it))
        }
        val power = session.averagePower?.let { cur ->
            m.avgPower?.let { Metric("$cur W", "$it W") }
        }
        val drift = session.cardiacDriftPercent?.let { cur ->
            m.cardiacDriftPercent?.let { Metric("%.1f%%".format(Locale.US, cur), "%.1f%%".format(Locale.US, it)) }
        }

        var first = true
        fun vs(metric: Metric, unitSuffix: String = ""): String {
            val text = if (first) "${metric.value}$unitSuffix (vs. ${metric.median} in a typical $tag ride)"
            else "${metric.value}$unitSuffix (vs. ${metric.median})"
            first = false
            return text
        }

        val sentences = listOfNotNull(
            when {
                fatEfficiency != null && fatGrams != null ->
                    "Your fat efficiency score was ${vs(fatEfficiency)} and you burned ${vs(fatGrams, " of fat")}."
                fatEfficiency != null -> "Your fat efficiency score was ${vs(fatEfficiency)}."
                fatGrams != null -> "You burned ${vs(fatGrams, " of fat")}."
                else -> null
            },
            when {
                duration != null && power != null -> "You rode ${vs(duration)} at ${vs(power)}."
                duration != null -> "You rode ${vs(duration)}."
                power != null -> "Your average power was ${vs(power)}."
                else -> null
            },
            drift?.let { "Your cardiac drift was ${vs(it)}." }
        )
        return sentences.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /**
     * Intervals' fixed metric list (#215): interval count + time in intervals + power during
     * intervals (duration-weighted, see [durationWeightedAvgPower]) + overall power, then the
     * rest-gap flag. Each metric drops out independently; the first one rendered carries the
     * "in a typical [tag] ride" qualifier. Null if nothing renders.
     */
    private fun intervalsNarrative(
        session: CyclingSession,
        tag: String,
        m: PoolMedians,
        intervals: List<IntervalSession>
    ): String? {
        val count = m.intervalCount?.let { Metric("${session.intervalCount}", "$it") }
        val time = m.intervalTotalTimeSec?.let {
            Metric(compactDuration(session.intervalTotalTimeSec), compactDuration(it))
        }
        val intervalPower = intervals.durationWeightedAvgPower()?.let { cur ->
            m.intervalAvgPower?.let { Metric("$cur W", "$it W") }
        }
        val overall = session.averagePower?.let { cur ->
            m.avgPower?.let { Metric("$cur W", "$it W") }
        }

        var first = true
        fun vs(metric: Metric, unitSuffix: String = ""): String {
            val text = if (first) "${metric.value}$unitSuffix (vs. ${metric.median} in a typical $tag ride)"
            else "${metric.value}$unitSuffix (vs. ${metric.median})"
            first = false
            return text
        }

        var body: String? = when {
            count != null && time != null -> "You did ${vs(count, " intervals")} and spent ${vs(time, " in intervals")}"
            count != null -> "You did ${vs(count, " intervals")}"
            time != null -> "You spent ${vs(time, " in intervals")}"
            else -> null
        }
        if (intervalPower != null) {
            body = when {
                time != null -> "$body at ${vs(intervalPower)}"
                body != null -> "$body averaging ${vs(intervalPower)} in intervals"
                else -> "Your power in intervals averaged ${vs(intervalPower)}"
            }
        }
        if (overall != null) {
            body = if (body != null) "$body, with ${vs(overall, " overall")}"
            else "Your average power was ${vs(overall)}"
        }
        return listOfNotNull(body?.let { "$it." }, restGapSentence(intervals))
            .takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /**
     * Recovery's fixed metric list (#216): duration + avg power + time below 60% of FTP, then avg
     * heart rate. Each metric drops out independently; the first one rendered carries the
     * "in a typical [tag] ride" qualifier. Null if nothing renders.
     */
    private fun recoveryNarrative(session: CyclingSession, tag: String, m: PoolMedians): String? {
        val duration = m.netDurationSec?.let {
            Metric(compactDuration(session.netDurationSec), compactDuration(it))
        }
        val power = session.averagePower?.let { cur ->
            m.avgPower?.let { Metric("$cur W", "$it W") }
        }
        val belowSixty = session.timeBelowSixtyPercentFtpSec?.let { cur ->
            m.timeBelowSixtyPercentFtpSec?.let { Metric(compactDuration(cur), compactDuration(it)) }
        }
        val heartRate = session.avgHeartRate?.let { cur ->
            m.avgHeartRate?.let { Metric("${cur}bpm", "${it}bpm") }
        }

        var first = true
        fun vs(metric: Metric, unitSuffix: String = ""): String {
            val text = if (first) "${metric.value}$unitSuffix (vs. ${metric.median} in a typical $tag ride)"
            else "${metric.value}$unitSuffix (vs. ${metric.median})"
            first = false
            return text
        }

        var body: String? = when {
            duration != null && power != null -> "You rode ${vs(duration)} at ${vs(power)}"
            duration != null -> "You rode ${vs(duration)}"
            power != null -> "Your average power was ${vs(power)}"
            else -> null
        }
        if (belowSixty != null) {
            val spent = vs(belowSixty, " below 60% of FTP")
            body = if (body != null) "$body and spent $spent" else "You spent $spent"
        }
        val sentences = listOfNotNull(
            body?.let { "$it." },
            heartRate?.let { "Your average heart rate was ${vs(it)}." }
        )
        return sentences.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** "2h41min" / "45min" — compact, no space, as in the #214 recap wording. */
    private fun compactDuration(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val min = (totalSeconds % 3600) / 60
        return if (h > 0) "${h}h${min}min" else "${min}min"
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

    private fun relativeDeviation(current: Double, median: Double): Double =
        abs(current - median) / abs(median).coerceAtLeast(0.0001)

    private fun cardiacDriftCandidate(session: CyclingSession, tag: String, comparison: TagComparison): Candidate? {
        val current = session.cardiacDriftPercent ?: return null
        val median = comparison.medians.cardiacDriftPercent ?: return null
        val direction = if (current < median) "lower" else "higher"
        val sentence = "Your cardiac drift was %.1f%%, $direction than your typical %.1f%% for $tag rides."
            .format(Locale.US, current, median)
        return Candidate(relativeDeviation(current, median), sentence)
    }

    private fun npToApCandidate(session: CyclingSession, tag: String, comparison: TagComparison): Candidate? {
        val avg = session.averagePower ?: return null
        val np = session.normalizedPower ?: return null
        if (avg == 0) return null
        val median = comparison.medians.npToApRatio ?: return null
        val current = np.toDouble() / avg
        val direction = if (current < median) "steadier" else "more variable"
        val sentence = "Your power was $direction than usual for $tag rides (NP:AP %.2f vs. your typical %.2f)."
            .format(Locale.US, current, median)
        return Candidate(relativeDeviation(current, median), sentence)
    }

    private fun fatEfficiencyCandidate(session: CyclingSession, tag: String, comparison: TagComparison): Candidate? {
        val current = session.fatEfficiencyScore ?: return null
        val median = comparison.medians.fatEfficiency ?: return null
        val direction = if (current > median) "above" else "below"
        val sentence = "Your fat efficiency score was $current, $direction your typical %.0f for $tag rides."
            .format(Locale.US, median)
        return Candidate(relativeDeviation(current.toDouble(), median), sentence)
    }

    private fun avgPowerCandidate(session: CyclingSession, tag: String, comparison: TagComparison): Candidate? {
        val current = session.averagePower ?: return null
        val median = comparison.medians.avgPower ?: return null
        val direction = if (current > median) "above" else "below"
        val sentence = "Your average power was ${current}W, $direction your typical ${median}W for $tag rides."
        return Candidate(relativeDeviation(current.toDouble(), median.toDouble()), sentence)
    }

    private fun distanceCandidate(session: CyclingSession, tag: String, comparison: TagComparison): Candidate? {
        val median = comparison.medians.distanceKm ?: return null
        val current = session.distanceKm
        val direction = if (current > median) "longer" else "shorter"
        val sentence = "This ride was %.1f km, $direction than your typical %.1f km for $tag rides."
            .format(Locale.US, current, median)
        return Candidate(relativeDeviation(current, median), sentence)
    }
}
