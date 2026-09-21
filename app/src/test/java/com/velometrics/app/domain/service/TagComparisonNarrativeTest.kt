package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.IntervalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TagComparisonNarrativeTest {

    private fun makeSession(
        distanceKm: Double = 30.0,
        hasPower: Boolean = false,
        averagePower: Int? = null,
        normalizedPower: Int? = null,
        fatEfficiencyScore: Int? = null,
        cardiacDriftPercent: Double? = null,
        tag: String? = "Zone 2",
        intervalCount: Int = 0,
        intervalTotalTimeSec: Int = 0,
        timeBelowSixtyPercentFtpSec: Int? = null,
        avgHeartRate: Int? = null
    ): CyclingSession {
        val start = Instant.now()
        return CyclingSession(
            fileName = "ride.fit",
            fileSha1 = "sha1",
            sessionStart = start,
            sessionEnd = start.plusSeconds(3600),
            totalDurationSec = 3600,
            pauseDurationSec = 0,
            netDurationSec = 3600,
            distanceKm = distanceKm,
            averagePower = averagePower,
            normalizedPower = normalizedPower,
            fatBurnedGrams = null,
            carbsBurnedGrams = null,
            powerZoneDistribution = null,
            speedHistogram = emptyMap(),
            intervalCount = intervalCount,
            intervalTotalTimeSec = intervalTotalTimeSec,
            gpsQualityPercent = 95.0,
            powerQualityPercent = null,
            hasPower = hasPower,
            fatEfficiencyScore = fatEfficiencyScore,
            cardiacDriftPercent = cardiacDriftPercent,
            tag = tag,
            timeBelowSixtyPercentFtpSec = timeBelowSixtyPercentFtpSec,
            avgHeartRate = avgHeartRate
        )
    }

    /** Only [IntervalSession.restBeforeNextIntervalSec] matters for these tests; the rest are filler. */
    private fun makeInterval(
        restBeforeNextIntervalSec: Int?,
        durationSec: Int = 300,
        avgPower: Int = 200
    ) = IntervalSession(
        cyclingSessionId = 1,
        startTimestamp = Instant.now(),
        durationSec = durationSec,
        durationNormalizedSec = durationSec,
        distanceM = 2000.0,
        avgPower = avgPower,
        avgSpeedKmh = 30.0,
        avgSpeedNormalizedKmh = 30.0,
        direction = "N",
        startLat = 0.0,
        startLon = 0.0,
        endLat = 0.0,
        endLon = 0.0,
        gpsTrack = "",
        restBeforeNextIntervalSec = restBeforeNextIntervalSec
    )

    /** Only the fields [TagComparisonNarrative] reads need real values; the rest default to null. */
    private fun makeComparison(
        last5SessionCount: Int = 5,
        medianDistanceKmLast5: Double? = null,
        medianAvgPowerLast5: Int? = null,
        medianFatEfficiencyLast5: Double? = null,
        medianCardiacDriftPercentLast5: Double? = null,
        medianNpToApRatioLast5: Double? = null,
        medianIntervalCountLast5: Int? = null,
        medianIntervalTotalTimeSecLast5: Int? = null,
        medianTimeBelowSixtyPercentFtpSecLast5: Int? = null,
        medianNetDurationSec: Int? = null,
        medianFatGrams: Double? = null,
        medianIntervalAvgPower: Int? = null,
        medianAvgHeartRate: Int? = null
    ) = TagComparison(
        sampleCount = last5SessionCount,
        medians = PoolMedians(
            netDurationSec = medianNetDurationSec,
            distanceKm = medianDistanceKmLast5,
            avgSpeedKmh = null,
            avgPower = medianAvgPowerLast5,
            normalizedPower = null,
            fatEfficiency = medianFatEfficiencyLast5,
            fatGrams = medianFatGrams,
            cardiacEfficiency = null,
            totalKcal = null,
            elevationGainM = null,
            elevGainPer100km = null,
            cardiacDriftPercent = medianCardiacDriftPercentLast5,
            npToApRatio = medianNpToApRatioLast5,
            intervalCount = medianIntervalCountLast5,
            intervalTotalTimeSec = medianIntervalTotalTimeSecLast5,
            timeBelowSixtyPercentFtpSec = medianTimeBelowSixtyPercentFtpSecLast5,
            intervalAvgPower = medianIntervalAvgPower,
            avgHeartRate = medianAvgHeartRate
        )
    )

    private fun lines(
        session: CyclingSession,
        comparison: TagComparison,
        tag: String = "Zone 2",
        intervals: List<IntervalSession> = emptyList()
    ) = TagComparisonNarrative.lines(session, tag, comparison, intervals)

    @Test
    fun `fewer than 2 tag-scoped sessions yields no lines`() {
        assertTrue(lines(makeSession(), makeComparison(last5SessionCount = 1)).isEmpty())
        assertTrue(lines(makeSession(), makeComparison(last5SessionCount = 0)).isEmpty())
    }

    @Test
    fun `zone 2 renders metrics and units only, in fixed order`() {
        val session = makeSession(hasPower = true, averagePower = 150, fatEfficiencyScore = 80, cardiacDriftPercent = 4.0)
        val comparison = makeComparison(
            medianFatEfficiencyLast5 = 69.0,
            medianNetDurationSec = 2700,
            medianAvgPowerLast5 = 140,
            medianCardiacDriftPercentLast5 = 5.0
        )
        assertEquals(
            listOf("80 fat efficiency (vs. 69)", "1h0min (vs. 45min)", "150 W (vs. 140 W)", "4.0% (vs. 5.0%)"),
            lines(session, comparison)
        )
    }

    @Test
    fun `metrics drop out independently when the ride or the pool lacks them`() {
        val session = makeSession(hasPower = true, averagePower = 150)
        val comparison = makeComparison(medianAvgPowerLast5 = 140, medianCardiacDriftPercentLast5 = 5.0)
        assertEquals(listOf("150 W (vs. 140 W)"), lines(session, comparison))
    }

    @Test
    fun `intervals renders count, time, interval power and overall power`() {
        val session = makeSession(
            tag = "Intervals", hasPower = true, averagePower = 180, intervalCount = 4, intervalTotalTimeSec = 1200
        )
        val comparison = makeComparison(
            medianIntervalCountLast5 = 5,
            medianIntervalTotalTimeSecLast5 = 1500,
            medianIntervalAvgPower = 250,
            medianAvgPowerLast5 = 170
        )
        val intervals = listOf(makeInterval(restBeforeNextIntervalSec = 150, avgPower = 260))
        assertEquals(
            listOf("4 intervals (vs. 5 intervals)", "20min (vs. 25min)", "260 W (vs. 250 W)", "180 W (vs. 170 W)"),
            lines(session, comparison, "Intervals", intervals)
        )
    }

    @Test
    fun `recovery renders duration, power, time below 60 percent FTP and heart rate`() {
        val session = makeSession(
            tag = "Recovery", hasPower = true, averagePower = 110, timeBelowSixtyPercentFtpSec = 3000, avgHeartRate = 118
        )
        val comparison = makeComparison(
            medianNetDurationSec = 3300,
            medianAvgPowerLast5 = 120,
            medianTimeBelowSixtyPercentFtpSecLast5 = 2700,
            medianAvgHeartRate = 121
        )
        assertEquals(
            listOf("1h0min (vs. 55min)", "110 W (vs. 120 W)", "50min (vs. 45min)", "118 bpm (vs. 121 bpm)"),
            lines(session, comparison, "Recovery")
        )
    }

    @Test
    fun `other tags get the generic metric list`() {
        val session = makeSession(tag = "Commute", distanceKm = 12.3, hasPower = true, averagePower = 130)
        val comparison = makeComparison(medianDistanceKmLast5 = 11.0, medianNetDurationSec = 3000, medianAvgPowerLast5 = 125)
        assertEquals(
            listOf("12.3 km (vs. 11.0 km)", "1h0min (vs. 50min)", "130 W (vs. 125 W)"),
            lines(session, comparison, "Commute")
        )
    }
}
