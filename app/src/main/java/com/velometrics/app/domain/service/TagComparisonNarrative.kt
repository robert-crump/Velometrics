package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.RideTag
import com.velometrics.app.domain.model.energy
import com.velometrics.app.domain.service.RecapMetricKind.AVERAGE_POWER
import com.velometrics.app.domain.service.RecapMetricKind.CARDIAC_DRIFT
import com.velometrics.app.domain.service.RecapMetricKind.DISTANCE
import com.velometrics.app.domain.service.RecapMetricKind.DURATION
import com.velometrics.app.domain.service.RecapMetricKind.FAT_BURNED
import com.velometrics.app.domain.service.RecapMetricKind.FAT_EFFICIENCY
import com.velometrics.app.domain.service.RecapMetricKind.HEART_RATE
import com.velometrics.app.domain.service.RecapMetricKind.INTERVAL_COUNT
import com.velometrics.app.domain.service.RecapMetricKind.INTERVAL_POWER
import com.velometrics.app.domain.service.RecapMetricKind.INTERVAL_TIME
import com.velometrics.app.domain.service.RecapMetricKind.TIME_BELOW_60_FTP

/**
 * Tag-scoped recap paragraph (#171, all-time pool since #214, prose since #224): compares this ride
 * to every earlier ride sharing its tag ([comparison], from [SessionComparator.computeTagComparison]
 * with this same [tag] — [SessionNarrativeAssembler] is the single call path that guarantees it)
 * and hands the candidates to [ComparisonProse].
 *
 * [RideTag.ZONE_2], [RideTag.INTERVALS] and [RideTag.RECOVERY] have fixed candidate lists and
 * anchors; any other tag (custom or legacy) gets a generic one. Each metric drops out independently
 * when this ride or the pool lacks it. Cardiac drift is only a candidate when the ride has no #222
 * drift advice paragraph, which already states the percentage. Null below [MIN_TAG_SCOPED_SAMPLES]
 * prior rides.
 */
object TagComparisonNarrative {

    /** Below this many tag-scoped prior rides, there's nothing meaningful to compare against. */
    private const val MIN_TAG_SCOPED_SAMPLES = 2

    fun paragraph(
        session: CyclingSession,
        tag: String,
        comparison: TagComparison,
        intervals: List<IntervalSession> = emptyList()
    ): String? {
        if (comparison.sampleCount < MIN_TAG_SCOPED_SAMPLES) return null
        val m = comparison.medians

        val duration = RecapMetric.of(DURATION, session.netDurationSec, m.netDurationSec)
        val power = RecapMetric.of(AVERAGE_POWER, session.averagePower, m.avgPower)
        val heartRate = RecapMetric.of(HEART_RATE, session.avgHeartRate, m.avgHeartRate)
        val drift = RecapMetric.of(CARDIAC_DRIFT, session.cardiacDriftPercent, m.cardiacDriftPercent)
            .takeIf { session.cardiacDriftCauses == null }
        val fatEfficiency = RecapMetric.of(FAT_EFFICIENCY, session.fatEfficiencyScore, m.fatEfficiency)

        val (candidates, anchor) = when (tag) {
            RideTag.ZONE_2.label -> listOf(
                fatEfficiency,
                RecapMetric.of(FAT_BURNED, session.energy?.fatGrams, m.fatGrams),
                duration, power, drift
            ) to AVERAGE_POWER
            RideTag.INTERVALS.label -> listOf(
                RecapMetric.of(INTERVAL_COUNT, session.intervalCount, m.intervalCount),
                RecapMetric.of(INTERVAL_TIME, session.intervalTotalTimeSec, m.intervalTotalTimeSec),
                RecapMetric.of(INTERVAL_POWER, intervals.durationWeightedAvgPower(), m.intervalAvgPower),
                power
            ) to INTERVAL_POWER
            RideTag.RECOVERY.label -> listOf(
                duration, power,
                RecapMetric.of(TIME_BELOW_60_FTP, session.timeBelowSixtyPercentFtpSec, m.timeBelowSixtyPercentFtpSec),
                heartRate
            ) to AVERAGE_POWER
            else -> listOf(
                RecapMetric.of(DISTANCE, session.distanceKm, m.distanceKm),
                duration, power, heartRate, drift, fatEfficiency
            ) to DURATION
        }
        return ComparisonProse.paragraph(
            comparedWith = "other $tag rides",
            typical = "your typical $tag ride",
            candidates = candidates.filterNotNull(),
            anchor = anchor
        )
    }
}
