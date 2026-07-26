package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.BestEffortValues
import com.velometrics.app.domain.model.Datapoint
import com.velometrics.app.util.GeoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class BestEffortCalculatorTest {

    // ---------------------------------------------------------------------
    // bestTimeForDistance
    // ---------------------------------------------------------------------

    @Test
    fun `bestTimeForDistance finds exact match at a sample boundary`() {
        val cumDistM = doubleArrayOf(0.0, 500.0, 1000.0)
        val elapsedSec = doubleArrayOf(0.0, 50.0, 100.0)

        val result = BestEffortCalculator.bestTimeForDistance(cumDistM, elapsedSec, 1000.0)

        assertEquals(100.0, result!!, 0.001)
    }

    @Test
    fun `bestTimeForDistance interpolates within a mid-segment match`() {
        // Non-uniform speed: 400m in 40s, then a slower 800m in 120s.
        val cumDistM = doubleArrayOf(0.0, 400.0, 1200.0)
        val elapsedSec = doubleArrayOf(0.0, 40.0, 160.0)

        val result = BestEffortCalculator.bestTimeForDistance(cumDistM, elapsedSec, 1000.0)

        // 600m into the 800m/120s segment -> 40 + 0.75*120 = 130s
        assertEquals(130.0, result!!, 0.001)
    }

    @Test
    fun `bestTimeForDistance returns null when the ride never covers the target`() {
        val cumDistM = doubleArrayOf(0.0, 500.0)
        val elapsedSec = doubleArrayOf(0.0, 60.0)

        val result = BestEffortCalculator.bestTimeForDistance(cumDistM, elapsedSec, 1000.0)

        assertNull(result)
    }

    @Test
    fun `bestTimeForDistance handles target reachable only right at the end of the ride`() {
        // Total distance is exactly the target; starting from any later point falls short.
        val cumDistM = doubleArrayOf(0.0, 200.0, 500.0, 1000.0)
        val elapsedSec = doubleArrayOf(0.0, 20.0, 50.0, 100.0)

        val result = BestEffortCalculator.bestTimeForDistance(cumDistM, elapsedSec, 1000.0)

        assertEquals(100.0, result!!, 0.001)
    }

    @Test
    fun `bestTimeForDistance prefers a faster later window over a slower earlier one`() {
        // Slow 1000m/200s from the start, then a fast burst covering the next 500m in 10s.
        val cumDistM = doubleArrayOf(0.0, 1000.0, 1500.0, 2000.0)
        val elapsedSec = doubleArrayOf(0.0, 200.0, 210.0, 220.0)

        val result = BestEffortCalculator.bestTimeForDistance(cumDistM, elapsedSec, 500.0)

        // Earliest window (index 0) takes 100s (half of the slow 200s/1000m segment);
        // the later burst window (index 1) covers the same 500m in 10s and should win.
        assertEquals(10.0, result!!, 0.001)
    }

    // ---------------------------------------------------------------------
    // bestAvgPowerForDuration
    // ---------------------------------------------------------------------

    @Test
    fun `bestAvgPowerForDuration returns the constant power regardless of window position`() {
        val elapsedSec = doubleArrayOf(0.0, 10.0, 20.0, 30.0, 40.0)
        val power = 200.0
        val cumEnergy = DoubleArray(elapsedSec.size) { power * elapsedSec[it] }

        val result = BestEffortCalculator.bestAvgPowerForDuration(elapsedSec, cumEnergy, 20.0)

        assertEquals(200, result)
    }

    @Test
    fun `bestAvgPowerForDuration finds a short high-power burst inside a longer low-power ride`() {
        // 100W for 100s, then a 400W burst for 10s, then 100W for another 190s.
        val elapsedSec = doubleArrayOf(0.0, 100.0, 110.0, 300.0)
        val cumEnergy = doubleArrayOf(0.0, 10_000.0, 14_000.0, 33_000.0)

        val result = BestEffortCalculator.bestAvgPowerForDuration(elapsedSec, cumEnergy, 10.0)

        assertEquals(400, result)
    }

    @Test
    fun `bestAvgPowerForDuration returns null when the ride is shorter than the duration`() {
        val elapsedSec = doubleArrayOf(0.0, 50.0)
        val cumEnergy = doubleArrayOf(0.0, 10_000.0)

        val result = BestEffortCalculator.bestAvgPowerForDuration(elapsedSec, cumEnergy, 100.0)

        assertNull(result)
    }

    @Test
    fun `bestAvgPowerForDuration handles duration matching the ride length exactly`() {
        val elapsedSec = doubleArrayOf(0.0, 10.0, 20.0)
        val cumEnergy = doubleArrayOf(0.0, 1500.0, 3000.0)

        val result = BestEffortCalculator.bestAvgPowerForDuration(elapsedSec, cumEnergy, 20.0)

        assertEquals(150, result)
    }

    // ---------------------------------------------------------------------
    // compute
    // ---------------------------------------------------------------------

    @Test
    fun `compute nulls all power fields when hasPower is false, even with stray power values`() {
        val datapoints = straightLineRide(stepMeters = 5000.0, stepSeconds = 500L, steps = 5, power = 200)
            .mapIndexed { index, dp -> if (index % 2 == 0) dp.copy(power = 250) else dp }

        val result = BestEffortCalculator.compute(datapoints, hasPower = false)

        assertNull(result.power1s)
        assertNull(result.power3s)
        assertNull(result.power5s)
        assertNull(result.power20s)
        assertNull(result.power30s)
        assertNull(result.power1m)
        assertNull(result.power5m)
        assertNull(result.power20m)
        assertNull(result.power30m)
    }

    @Test
    fun `compute returns all-null values for fewer than 2 datapoints`() {
        assertEquals(BestEffortValues(), BestEffortCalculator.compute(emptyList(), hasPower = true))

        val single = straightLineRide(stepMeters = 1000.0, stepSeconds = 100L, steps = 0, power = 200)
        assertEquals(BestEffortValues(), BestEffortCalculator.compute(single, hasPower = true))
    }

    @Test
    fun `compute matches hand-calculated splits and power curve for a constant-speed constant-power ride`() {
        // Straight line north along a meridian, 5000m per 500s step (10 m/s, 36 km/h), 5 steps,
        // 200W constant throughout. Haversine along a pure meridian is exact (R * dLatRadians), so
        // each step is exactly 5000m and total ride distance is exactly 25000m over 2500s.
        val datapoints = straightLineRide(stepMeters = 5000.0, stepSeconds = 500L, steps = 5, power = 200)

        val result = BestEffortCalculator.compute(datapoints, hasPower = true)

        // 25km is the entire ride: only the whole-ride window reaches it, so the split is the
        // total elapsed time. 50km/100km are never reached.
        assertEquals(2500.0, result.split25kSec!!, 0.01)
        assertNull(result.split50kSec)
        assertNull(result.split100kSec)

        // Power is constant everywhere, so every bucket up to the ride's 2500s length reads
        // exactly the constant wattage.
        assertEquals(200, result.power1s)
        assertEquals(200, result.power3s)
        assertEquals(200, result.power5s)
        assertEquals(200, result.power20s)
        assertEquals(200, result.power30s)
        assertEquals(200, result.power1m)
        assertEquals(200, result.power5m)
        assertEquals(200, result.power20m)
        assertEquals(200, result.power30m)
    }

    private fun straightLineRide(stepMeters: Double, stepSeconds: Long, steps: Int, power: Int): List<Datapoint> {
        val latStepDeg = Math.toDegrees(stepMeters / GeoUtils.EARTH_RADIUS_M)
        val start = Instant.parse("2026-01-01T00:00:00Z")
        return (0..steps).map { i ->
            Datapoint(
                lat = i * latStepDeg,
                lon = 0.0,
                speedKmh = stepMeters / stepSeconds * 3.6,
                power = power,
                timestamp = start.plusSeconds(i * stepSeconds)
            )
        }
    }
}
