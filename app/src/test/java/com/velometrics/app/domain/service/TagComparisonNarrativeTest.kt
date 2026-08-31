package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import org.junit.Assert.assertEquals
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
        powerZoneDistribution: Map<String, Int>? = null
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
            powerZoneDistribution = powerZoneDistribution,
            speedHistogram = emptyMap(),
            intervalCount = intervalCount,
            intervalTotalTimeSec = 0,
            gpsQualityPercent = 95.0,
            powerQualityPercent = null,
            hasPower = hasPower,
            fatEfficiencyScore = fatEfficiencyScore,
            cardiacDriftPercent = cardiacDriftPercent,
            tag = tag
        )
    }

    /** Only the fields [TagComparisonNarrative] reads need real values; the rest default to null/0. */
    private fun makeComparison(
        last5SessionCount: Int = 5,
        medianDistanceKmLast5: Double? = null,
        medianAvgPowerLast5: Int? = null,
        medianFatEfficiencyLast5: Double? = null,
        medianCardiacDriftPercentLast5: Double? = null,
        medianNpToApRatioLast5: Double? = null,
        medianIntervalCountLast5: Int? = null,
        medianPowerZone1SecLast5: Int? = null
    ) = SessionComparison(
        medianNetDurationSecLast5 = null,
        medianNetDurationSecAllPrevious = null,
        medianDistanceKmLast5 = medianDistanceKmLast5,
        medianDistanceKmAllPrevious = null,
        medianAvgSpeedKmhLast5 = null,
        medianAvgSpeedKmhAllPrevious = null,
        medianAvgPowerLast5 = medianAvgPowerLast5,
        medianAvgPowerAllPrevious = null,
        medianNormalizedPowerLast5 = null,
        medianNormalizedPowerAllPrevious = null,
        medianFatEfficiencyLast5 = medianFatEfficiencyLast5,
        medianFatEfficiencyAllPrevious = null,
        medianCardiacEfficiencyLast5 = null,
        medianCardiacEfficiencyAllPrevious = null,
        medianTotalKcalLast5 = null,
        medianTotalKcalAllPrevious = null,
        medianElevationGainMLast5 = null,
        medianElevationGainMAllPrevious = null,
        medianElevGainPer100kmLast5 = null,
        medianElevGainPer100kmAllPrevious = null,
        medianCardiacDriftPercentLast5 = medianCardiacDriftPercentLast5,
        medianCardiacDriftPercentAllPrevious = null,
        medianNpToApRatioLast5 = medianNpToApRatioLast5,
        medianNpToApRatioAllPrevious = null,
        medianIntervalCountLast5 = medianIntervalCountLast5,
        medianIntervalCountAllPrevious = null,
        medianPowerZone1SecLast5 = medianPowerZone1SecLast5,
        medianPowerZone1SecAllPrevious = null,
        last5SessionCount = last5SessionCount,
        allPreviousSessionCount = last5SessionCount
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

        val result = TagComparisonNarrative.generate(session, "Zone 2", comparison)

        assertEquals(
            "Your cardiac drift was 2.0%, lower than your typical 4.0% for Zone 2 rides.",
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

        val result = TagComparisonNarrative.generate(session, "Intervals", comparison)

        assertEquals(
            "Your average power was 260W, above your typical 200W for Intervals rides.",
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

        val result = TagComparisonNarrative.generate(session, "Zone 2", comparison)

        assertEquals(
            "Your fat efficiency score was 90, above your typical 60 for Zone 2 rides.",
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

        val result = TagComparisonNarrative.generate(session, "Intervals", comparison)

        assertEquals(
            "Your power was more variable than usual for Intervals rides (NP:AP 1.10 vs. your typical 1.00).",
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
    fun `Zone 2 leads with fat efficiency even when another metric deviates more`() {
        // Cardiac drift deviates far more (75%) than fat efficiency (10%), but Zone 2's main
        // value is always fat efficiency when it's available.
        val session = makeSession(
            hasPower = true,
            averagePower = 150,
            normalizedPower = 155,
            fatEfficiencyScore = 66,
            cardiacDriftPercent = 1.0,
            tag = "Zone 2"
        )
        val comparison = makeComparison(
            medianAvgPowerLast5 = 149,
            medianFatEfficiencyLast5 = 60.0,
            medianCardiacDriftPercentLast5 = 4.0,
            medianNpToApRatioLast5 = 1.03,
            medianDistanceKmLast5 = 30.0
        )

        val result = TagComparisonNarrative.generate(session, "Zone 2", comparison)

        assertEquals(
            "Your fat efficiency score was 66, above your typical 60 for Zone 2 rides.",
            result
        )
    }

    @Test
    fun `Intervals leads with interval count when available`() {
        val session = makeSession(tag = "Intervals", intervalCount = 8)
        val comparison = makeComparison(medianIntervalCountLast5 = 5, medianDistanceKmLast5 = 30.0)

        val result = TagComparisonNarrative.generate(session, "Intervals", comparison)

        assertEquals(
            "You did 8 intervals, more than your typical 5 for Intervals rides.",
            result
        )
    }

    @Test
    fun `Intervals falls back to deviation ranking when interval count median is unavailable`() {
        val session = makeSession(
            tag = "Intervals",
            intervalCount = 8,
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

        val result = TagComparisonNarrative.generate(session, "Intervals", comparison)

        assertEquals(
            "Your average power was 260W, above your typical 200W for Intervals rides.",
            result
        )
    }

    @Test
    fun `Recovery leads with time in Power Zone 1 when available`() {
        val session = makeSession(
            tag = "Recovery",
            hasPower = true,
            powerZoneDistribution = mapOf("Zone 1" to 1800)
        )
        val comparison = makeComparison(medianPowerZone1SecLast5 = 1200, medianDistanceKmLast5 = 30.0)

        val result = TagComparisonNarrative.generate(session, "Recovery", comparison)

        assertEquals(
            "You spent 30m 0s in Zone 1, more than your typical 20m 0s for Recovery rides.",
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
}
