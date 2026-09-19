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
        timeBelowSixtyPercentFtpSec: Int? = null
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
            timeBelowSixtyPercentFtpSec = timeBelowSixtyPercentFtpSec
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
        medianIntervalAvgPower: Int? = null
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
            intervalAvgPower = medianIntervalAvgPower
        )
    )

    @Test
    fun `fewer than 2 tag-scoped sessions shows not-enough-history state`() {
        val session = makeSession()
        val comparison = makeComparison(last5SessionCount = 1)

        val result = TagComparisonNarrative.generate(session, "Zone 2", comparison)

        assertEquals("Not enough history for Zone 2 rides yet.", result)
    }

    @Test
    fun `zero tag-scoped sessions shows not-enough-history state`() {
        val session = makeSession()
        val comparison = makeComparison(last5SessionCount = 0)

        val result = TagComparisonNarrative.generate(session, "Zone 2", comparison)

        assertEquals("Not enough history for Zone 2 rides yet.", result)
    }

    @Test
    fun `leads with cardiac drift when it deviates most`() {
        // Drift: 2.0 vs median 4.0 -> 50% relative deviation.
        // Avg power: 200 vs median 195 -> ~2.6% relative deviation (far smaller).
        val session = makeSession(
            hasPower = true,
            averagePower = 200,
            normalizedPower = 210,
            cardiacDriftPercent = 2.0
        )
        val comparison = makeComparison(
            medianAvgPowerLast5 = 195,
            medianCardiacDriftPercentLast5 = 4.0,
            medianNpToApRatioLast5 = 1.05,
            medianDistanceKmLast5 = 30.0
        )

        val result = TagComparisonNarrative.generate(session, "Recovery", comparison)

        assertEquals(
            "Your cardiac drift was 2.0%, lower than your typical 4.0% for Recovery rides.",
            result
        )
    }

    @Test
    fun `leads with average power when it deviates most`() {
        // Avg power: 260 vs median 200 -> 30% relative deviation, biggest of the candidates.
        val session = makeSession(
            hasPower = true,
            averagePower = 260,
            normalizedPower = 270,
            cardiacDriftPercent = 4.1
        )
        val comparison = makeComparison(
            medianAvgPowerLast5 = 200,
            medianCardiacDriftPercentLast5 = 4.0,
            medianNpToApRatioLast5 = 1.04,
            medianDistanceKmLast5 = 30.0
        )

        val result = TagComparisonNarrative.generate(session, "Recovery", comparison)

        assertEquals(
            "Your average power was 260W, above your typical 200W for Recovery rides.",
            result
        )
    }

    @Test
    fun `leads with fat efficiency when it deviates most`() {
        val session = makeSession(
            hasPower = true,
            averagePower = 150,
            normalizedPower = 155,
            fatEfficiencyScore = 90,
            cardiacDriftPercent = 4.0
        )
        val comparison = makeComparison(
            medianAvgPowerLast5 = 149,
            medianFatEfficiencyLast5 = 60.0,
            medianCardiacDriftPercentLast5 = 4.0,
            medianNpToApRatioLast5 = 1.03,
            medianDistanceKmLast5 = 30.0
        )

        val result = TagComparisonNarrative.generate(session, "Recovery", comparison)

        assertEquals(
            "Your fat efficiency score was 90, above your typical 60 for Recovery rides.",
            result
        )
    }

    @Test
    fun `leads with power steadiness when NP-to-AP ratio deviates most`() {
        // Current NP:AP = 220/200 = 1.10 vs median 1.00 -> 10% relative deviation, biggest.
        val session = makeSession(
            hasPower = true,
            averagePower = 200,
            normalizedPower = 220,
            cardiacDriftPercent = 4.0
        )
        val comparison = makeComparison(
            medianAvgPowerLast5 = 198,
            medianCardiacDriftPercentLast5 = 4.0,
            medianNpToApRatioLast5 = 1.00,
            medianDistanceKmLast5 = 30.0
        )

        val result = TagComparisonNarrative.generate(session, "Recovery", comparison)

        assertEquals(
            "Your power was more variable than usual for Recovery rides (NP:AP 1.10 vs. your typical 1.00).",
            result
        )
    }

    @Test
    fun `falls back to distance when the ride has no power or heart-rate data`() {
        val session = makeSession(distanceKm = 45.0, hasPower = false)
        val comparison = makeComparison(medianDistanceKmLast5 = 30.0)

        val result = TagComparisonNarrative.generate(session, "Recovery", comparison)

        assertEquals(
            "This ride was 45.0 km, longer than your typical 30.0 km for Recovery rides.",
            result
        )
    }

    @Test
    fun `Recovery leads with time below 60% of FTP when available`() {
        val session = makeSession(
            tag = "Recovery",
            hasPower = true,
            timeBelowSixtyPercentFtpSec = 1800
        )
        val comparison = makeComparison(medianTimeBelowSixtyPercentFtpSecLast5 = 1200, medianDistanceKmLast5 = 30.0)

        val result = TagComparisonNarrative.generate(session, "Recovery", comparison)

        assertEquals(
            "You spent 30m 0s below 60% of FTP, more than your typical 20m 0s for Recovery rides.",
            result
        )
    }

    @Test
    fun `no candidate KPI at all still falls back to not-enough-history text`() {
        // last5SessionCount says there's history, but every per-metric median is null
        // (e.g. a same-tag pool that never itself had 2+ samples for any single metric).
        val session = makeSession(hasPower = false)
        val comparison = makeComparison(medianDistanceKmLast5 = null)

        val result = TagComparisonNarrative.generate(session, "Recovery", comparison)

        assertTrue(result.startsWith("Not enough history"))
    }

    // -- Zone 2 fixed metric list (#214) --------------------------------------------------------

    private fun zone2Session(
        fatEfficiencyScore: Int? = 83,
        fatBurnedGrams: Double? = 21.0,
        carbsBurnedGrams: Double? = 90.0,
        netDurationSec: Int = 2 * 3600 + 41 * 60,
        averagePower: Int? = 178,
        cardiacDriftPercent: Double? = 4.2
    ): CyclingSession = makeSession(
        hasPower = true,
        averagePower = averagePower,
        fatEfficiencyScore = fatEfficiencyScore,
        cardiacDriftPercent = cardiacDriftPercent
    ).copy(
        fatBurnedGrams = fatBurnedGrams,
        carbsBurnedGrams = carbsBurnedGrams,
        netDurationSec = netDurationSec
    )

    private fun zone2Comparison(
        medianFatEfficiency: Double? = 81.0,
        medianFatGrams: Double? = 43.0,
        medianNetDurationSec: Int? = 75 * 60,
        medianAvgPower: Int? = 183,
        medianCardiacDrift: Double? = 3.7,
        sampleCount: Int = 6
    ) = makeComparison(
        last5SessionCount = sampleCount,
        medianFatEfficiencyLast5 = medianFatEfficiency,
        medianFatGrams = medianFatGrams,
        medianNetDurationSec = medianNetDurationSec,
        medianAvgPowerLast5 = medianAvgPower,
        medianCardiacDriftPercentLast5 = medianCardiacDrift
    )

    private fun zone2(session: CyclingSession, comparison: TagComparison) =
        TagComparisonNarrative.generate(session, "Zone 2", comparison)

    @Test
    fun `Zone 2 renders all five metrics in the fixed order and grouping`() {
        assertEquals(
            "Your fat efficiency score was 83 (vs. 81 in a typical Zone 2 ride) and you burned 21g of fat (vs. 43g). " +
                "You rode 2h41min (vs. 1h15min) at 178 W (vs. 183 W). Your cardiac drift was 4.2% (vs. 3.7%).",
            zone2(zone2Session(), zone2Comparison())
        )
    }

    @Test
    fun `Zone 2 without fat efficiency moves the typical-ride qualifier to fat grams`() {
        assertEquals(
            "You burned 21g of fat (vs. 43g in a typical Zone 2 ride). " +
                "You rode 2h41min (vs. 1h15min) at 178 W (vs. 183 W). Your cardiac drift was 4.2% (vs. 3.7%).",
            zone2(zone2Session(fatEfficiencyScore = null), zone2Comparison())
        )
    }

    @Test
    fun `Zone 2 without fat grams keeps fat efficiency alone`() {
        val expected = "Your fat efficiency score was 83 (vs. 81 in a typical Zone 2 ride). " +
            "You rode 2h41min (vs. 1h15min) at 178 W (vs. 183 W). Your cardiac drift was 4.2% (vs. 3.7%)."
        assertEquals(expected, zone2(zone2Session(fatBurnedGrams = null), zone2Comparison()))
        assertEquals(expected, zone2(zone2Session(), zone2Comparison(medianFatGrams = null)))
    }

    @Test
    fun `Zone 2 without duration median drops duration and rephrases power`() {
        assertEquals(
            "Your fat efficiency score was 83 (vs. 81 in a typical Zone 2 ride) and you burned 21g of fat (vs. 43g). " +
                "Your average power was 178 W (vs. 183 W). Your cardiac drift was 4.2% (vs. 3.7%).",
            zone2(zone2Session(), zone2Comparison(medianNetDurationSec = null))
        )
    }

    @Test
    fun `Zone 2 without power keeps duration alone`() {
        assertEquals(
            "Your fat efficiency score was 83 (vs. 81 in a typical Zone 2 ride) and you burned 21g of fat (vs. 43g). " +
                "You rode 2h41min (vs. 1h15min). Your cardiac drift was 4.2% (vs. 3.7%).",
            zone2(zone2Session(averagePower = null), zone2Comparison())
        )
    }

    @Test
    fun `Zone 2 without cardiac drift omits the last sentence`() {
        assertEquals(
            "Your fat efficiency score was 83 (vs. 81 in a typical Zone 2 ride) and you burned 21g of fat (vs. 43g). " +
                "You rode 2h41min (vs. 1h15min) at 178 W (vs. 183 W).",
            zone2(zone2Session(cardiacDriftPercent = null), zone2Comparison(medianCardiacDrift = null))
        )
    }

    @Test
    fun `Zone 2 with fewer than 2 prior rides shows not-enough-history`() {
        assertEquals(
            "Not enough history for Zone 2 rides yet.",
            zone2(zone2Session(), zone2Comparison(sampleCount = 1))
        )
    }

    @Test
    fun `Zone 2 with no computable metric shows not-enough-history`() {
        assertEquals(
            "Not enough history for Zone 2 rides yet.",
            zone2(zone2Session(), zone2Comparison(null, null, null, null, null))
        )
    }

    // -- Intervals fixed metric list (#215) -----------------------------------------------------

    private val intervalGaps = listOf(150, 150, 150, 150, null)

    /** 5 intervals, 22min in them at a duration-weighted 330 W; the ride's overall average is 245 W. */
    private fun intervalsSession(
        intervalCount: Int = 5,
        intervalTotalTimeSec: Int = 22 * 60,
        averagePower: Int? = 245
    ) = makeSession(
        tag = "Intervals",
        hasPower = true,
        averagePower = averagePower,
        intervalCount = intervalCount,
        intervalTotalTimeSec = intervalTotalTimeSec
    )

    private fun intervalsList(gaps: List<Int?> = intervalGaps) =
        gaps.map { makeInterval(it, durationSec = 264, avgPower = 330) }

    private fun intervalsComparison(
        count: Int? = 4,
        totalSec: Int? = 18 * 60,
        intervalPower: Int? = 320,
        overallPower: Int? = 230
    ) = makeComparison(
        medianIntervalCountLast5 = count,
        medianIntervalTotalTimeSecLast5 = totalSec,
        medianIntervalAvgPower = intervalPower,
        medianAvgPowerLast5 = overallPower
    )

    private val intervalsMetrics =
        "You did 5 intervals (vs. 4 in a typical Intervals ride) and spent 22min in intervals " +
            "(vs. 18min) at 330 W (vs. 320 W), with 245 W overall (vs. 230 W)."

    @Test
    fun `Intervals renders the fixed metric list with all metrics present`() {
        val result = TagComparisonNarrative.generate(
            intervalsSession(), "Intervals", intervalsComparison(), intervalsList()
        )

        assertEquals(intervalsMetrics, result)
    }

    @Test
    fun `Intervals appends the rest-gap flag after the metrics`() {
        val result = TagComparisonNarrative.generate(
            intervalsSession(), "Intervals", intervalsComparison(), intervalsList(listOf(150, 100, 150, 150, null))
        )

        assertEquals(
            "$intervalsMetrics 1 of 4 rest gaps were shorter than the recommended 2-3min.",
            result
        )
    }

    @Test
    fun `Intervals flags rest gaps that are too long`() {
        val result = TagComparisonNarrative.generate(
            intervalsSession(), "Intervals", intervalsComparison(), intervalsList(listOf(150, 200, 210, 150, null))
        )

        assertEquals("$intervalsMetrics 2 of 4 rest gaps were longer than the recommended 2-3min.", result)
    }

    @Test
    fun `Intervals reports both rest-gap directions in one sentence`() {
        val result = TagComparisonNarrative.generate(
            intervalsSession(), "Intervals", intervalsComparison(), intervalsList(listOf(90, 100, 200, 150, null))
        )

        assertEquals(
            "$intervalsMetrics 2 of 4 rest gaps were shorter than the recommended 2-3min, 1 was longer.",
            result
        )
    }

    @Test
    fun `Intervals with nothing to flag omits the rest-gap sentence`() {
        val result = TagComparisonNarrative.generate(
            intervalsSession(), "Intervals", intervalsComparison(), intervalsList()
        )

        assertFalse(result.contains("rest gap"))
    }

    @Test
    fun `Intervals with a single interval has no rest-gap sentence`() {
        val result = TagComparisonNarrative.generate(
            intervalsSession(intervalCount = 1), "Intervals", intervalsComparison(),
            intervalsList(listOf<Int?>(null))
        )

        assertFalse(result.contains("rest gap"))
    }

    @Test
    fun `Intervals drops the interval count when its median is missing`() {
        val result = TagComparisonNarrative.generate(
            intervalsSession(), "Intervals", intervalsComparison(count = null), intervalsList()
        )

        assertEquals(
            "You spent 22min in intervals (vs. 18min in a typical Intervals ride) at 330 W (vs. 320 W), " +
                "with 245 W overall (vs. 230 W).",
            result
        )
    }

    @Test
    fun `Intervals drops time in intervals when its median is missing`() {
        val result = TagComparisonNarrative.generate(
            intervalsSession(), "Intervals", intervalsComparison(totalSec = null), intervalsList()
        )

        assertEquals(
            "You did 5 intervals (vs. 4 in a typical Intervals ride) averaging 330 W (vs. 320 W) in intervals, " +
                "with 245 W overall (vs. 230 W).",
            result
        )
    }

    @Test
    fun `Intervals drops interval power when the pool median is missing`() {
        val result = TagComparisonNarrative.generate(
            intervalsSession(), "Intervals", intervalsComparison(intervalPower = null), intervalsList()
        )

        assertEquals(
            "You did 5 intervals (vs. 4 in a typical Intervals ride) and spent 22min in intervals " +
                "(vs. 18min), with 245 W overall (vs. 230 W).",
            result
        )
    }

    @Test
    fun `Intervals drops interval power when the ride has no intervals to average`() {
        val result = TagComparisonNarrative.generate(
            intervalsSession(), "Intervals", intervalsComparison(), emptyList()
        )

        assertFalse(result.contains("330"))
        assertTrue(result.contains("245 W overall"))
    }

    @Test
    fun `Intervals drops overall power when the ride has none`() {
        val result = TagComparisonNarrative.generate(
            intervalsSession(averagePower = null), "Intervals", intervalsComparison(), intervalsList()
        )

        assertEquals(
            "You did 5 intervals (vs. 4 in a typical Intervals ride) and spent 22min in intervals " +
                "(vs. 18min) at 330 W (vs. 320 W).",
            result
        )
    }

    @Test
    fun `Intervals drops overall power when its median is missing`() {
        val result = TagComparisonNarrative.generate(
            intervalsSession(), "Intervals", intervalsComparison(overallPower = null), intervalsList()
        )

        assertFalse(result.contains("overall"))
    }

    @Test
    fun `Intervals with only overall power carries the qualifier there`() {
        val result = TagComparisonNarrative.generate(
            intervalsSession(), "Intervals",
            intervalsComparison(count = null, totalSec = null, intervalPower = null), emptyList()
        )

        assertEquals("Your average power was 245 W (vs. 230 W in a typical Intervals ride).", result)
    }

    @Test
    fun `Intervals with insufficient pool shows not-enough-history`() {
        val result = TagComparisonNarrative.generate(
            intervalsSession(), "Intervals", makeComparison(last5SessionCount = 1), intervalsList()
        )

        assertEquals("Not enough history for Intervals rides yet.", result)
    }

    @Test
    fun `Intervals with no renderable metric falls back to not-enough-history`() {
        val result = TagComparisonNarrative.generate(
            intervalsSession(), "Intervals",
            intervalsComparison(count = null, totalSec = null, intervalPower = null, overallPower = null),
            intervalsList()
        )

        assertEquals("Not enough history for Intervals rides yet.", result)
    }

    // -- Duration-weighted interval power (#215) ------------------------------------------------

    @Test
    fun `duration-weighted power counts longer intervals proportionally more`() {
        // (400 W x 60s + 200 W x 180s) / 240s = 250 W; an unweighted mean would be 300 W.
        val intervals = listOf(
            makeInterval(150, durationSec = 60, avgPower = 400),
            makeInterval(null, durationSec = 180, avgPower = 200)
        )

        assertEquals(250, intervals.durationWeightedAvgPower())
    }

    @Test
    fun `duration-weighted power is null without intervals`() {
        assertEquals(null, emptyList<IntervalSession>().durationWeightedAvgPower())
    }
}
