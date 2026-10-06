package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.IntervalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun paragraph(
        session: CyclingSession,
        comparison: TagComparison,
        tag: String = "Zone 2",
        intervals: List<IntervalSession> = emptyList()
    ) = TagComparisonNarrative.paragraph(session, tag, comparison, intervals)

    @Test
    fun `fewer than 2 tag-scoped sessions yields no paragraph`() {
        assertNull(paragraph(makeSession(), makeComparison(last5SessionCount = 1)))
        assertNull(paragraph(makeSession(), makeComparison(last5SessionCount = 0)))
    }

    @Test
    fun `zone 2 anchors on average power and ranks the rest by deviation`() {
        val session = makeSession(hasPower = true, averagePower = 150, fatEfficiencyScore = 80, cardiacDriftPercent = 4.0)
        val comparison = makeComparison(
            medianFatEfficiencyLast5 = 69.0,
            medianNetDurationSec = 2700,
            medianAvgPowerLast5 = 140,
            medianCardiacDriftPercentLast5 = 5.0
        )
        assertEquals(
            "Compared with other Zone 2 rides, average power was higher (150 W vs. 140 W) and fat efficiency was higher (80 vs. 69). " +
                "Your ride was also longer (1h00min vs. 45min).",
            paragraph(session, comparison)
        )
    }

    @Test
    fun `cardiac drift is left to the drift advice paragraph when the ride has one`() {
        val comparison = makeComparison(medianAvgPowerLast5 = 150, medianCardiacDriftPercentLast5 = 3.0)
        val noAdvice = makeSession(hasPower = true, averagePower = 150, cardiacDriftPercent = 6.0)
        assertEquals(
            "Compared with other Zone 2 rides, cardiac drift was higher (6.0% vs. 3.0%). Average power (150 W vs. 150 W) was comparable.",
            paragraph(noAdvice, comparison)
        )
        val withAdvice = noAdvice.copy(cardiacDriftCauses = emptyList())
        assertEquals(
            "This ride was close to your typical Zone 2 ride. Average power (150 W vs. 150 W) was comparable.",
            paragraph(withAdvice, comparison)
        )
    }

    @Test
    fun `metrics drop out independently when the ride or the pool lacks them`() {
        val session = makeSession(hasPower = true, averagePower = 150)
        val comparison = makeComparison(medianAvgPowerLast5 = 140, medianCardiacDriftPercentLast5 = 5.0)
        assertEquals("Compared with other Zone 2 rides, average power was higher (150 W vs. 140 W).", paragraph(session, comparison))
    }

    @Test
    fun `intervals anchors on interval power`() {
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
        // Interval time sits exactly on the 20% boundary (300 s of 1500 s), so it counts as comparable.
        assertEquals(
            "Compared with other Intervals rides, average power was higher (180 W vs. 170 W). " +
                "Interval power (260 W vs. 250 W) and interval time (20min vs. 25min) were comparable.",
            paragraph(session, comparison, "Intervals", intervals)
        )
    }

    @Test
    fun `recovery anchors on average power`() {
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
            "Compared with other Recovery rides, average power was lower (110 W vs. 120 W). " +
                "Time below 60% FTP (50min vs. 45min) and heart rate (118 bpm vs. 121 bpm) were comparable.",
            paragraph(session, comparison, "Recovery")
        )
    }

    @Test
    fun `other tags get the generic list, anchored on duration`() {
        val session = makeSession(tag = "Commute", distanceKm = 12.3, hasPower = true, averagePower = 130)
        val comparison = makeComparison(medianDistanceKmLast5 = 11.0, medianNetDurationSec = 3000, medianAvgPowerLast5 = 125)
        assertEquals(
            "This ride was close to your typical Commute ride. " +
                "Duration (1h00min vs. 50min), average power (130 W vs. 125 W) and distance (12.3 km vs. 11.0 km) were all comparable.",
            paragraph(session, comparison, "Commute")
        )
    }
}
