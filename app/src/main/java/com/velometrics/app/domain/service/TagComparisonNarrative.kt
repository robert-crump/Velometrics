package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.RideTag
import com.velometrics.app.domain.model.energy
import java.util.Locale

/**
 * Tag-scoped comparison stat lines (#171, all-time pool since #214): one "value (vs. median)" line
 * per metric, comparing this ride to every earlier ride sharing its tag ([comparison], from
 * [SessionComparator.computeTagComparison] with this same [tag] — [SessionNarrativeAssembler] is
 * the single call path that guarantees it). Values carry only their unit, no prose.
 *
 * [RideTag.ZONE_2], [RideTag.INTERVALS] and [RideTag.RECOVERY] have fixed metric lists; any other
 * tag (custom or legacy) gets a generic one. Each metric drops out independently when this ride or
 * the pool lacks it. The list is empty below [MIN_TAG_SCOPED_SAMPLES] prior rides.
 */
object TagComparisonNarrative {

    /** Below this many tag-scoped prior rides, there's nothing meaningful to compare against. */
    private const val MIN_TAG_SCOPED_SAMPLES = 2

    fun lines(
        session: CyclingSession,
        tag: String,
        comparison: TagComparison,
        intervals: List<IntervalSession> = emptyList()
    ): List<String> {
        if (comparison.sampleCount < MIN_TAG_SCOPED_SAMPLES) return emptyList()
        val m = comparison.medians

        val duration = pair(session.netDurationSec, m.netDurationSec) { compactDuration(it) }
        val power = pair(session.averagePower, m.avgPower) { "$it W" }
        val heartRate = pair(session.avgHeartRate, m.avgHeartRate) { "$it bpm" }
        val drift = pair(session.cardiacDriftPercent, m.cardiacDriftPercent) { "%.1f%%".format(Locale.US, it) }
        val fatEfficiency = pair(session.fatEfficiencyScore?.toDouble(), m.fatEfficiency, " fat efficiency") { "%.0f".format(Locale.US, it) }

        return listOfNotNull(
            *when (tag) {
                RideTag.ZONE_2.label -> arrayOf(
                    fatEfficiency,
                    pair(session.energy?.fatGrams, m.fatGrams, " fat") { "%.0f g".format(Locale.US, it) },
                    duration, power, drift
                )
                RideTag.INTERVALS.label -> arrayOf(
                    pair(session.intervalCount, m.intervalCount) { "$it intervals" },
                    pair(session.intervalTotalTimeSec, m.intervalTotalTimeSec) { compactDuration(it) },
                    pair(intervals.durationWeightedAvgPower(), m.intervalAvgPower) { "$it W" },
                    power
                )
                RideTag.RECOVERY.label -> arrayOf(
                    duration, power,
                    pair(session.timeBelowSixtyPercentFtpSec, m.timeBelowSixtyPercentFtpSec) { compactDuration(it) },
                    heartRate
                )
                else -> arrayOf(
                    pair(session.distanceKm, m.distanceKm) { "%.1f km".format(Locale.US, it) },
                    duration, power, heartRate, drift, fatEfficiency
                )
            }
        )
    }

    /** "value[label] (vs. median)", or null when either side is missing; [label] names the metric on the current value only. */
    private fun <T : Number> pair(current: T?, median: T?, label: String = "", format: (T) -> String): String? {
        if (current == null || median == null) return null
        return "${format(current)}$label (vs. ${format(median)})"
    }

    /** "2h41min" / "45min" — compact, no space, as in the #214 recap wording. */
    private fun compactDuration(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val min = (totalSeconds % 3600) / 60
        return if (h > 0) "${h}h${min}min" else "${min}min"
    }
}
