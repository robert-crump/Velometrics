package com.velometrics.app.domain.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideEstimatorTest {

    private val segmentA = ScoredSegment(
        lengthM = 1000.0,
        speedP25 = 20.0, speedP50 = 25.0, speedP75 = 30.0,
        powerP25 = 150.0, powerP50 = 180.0, powerP75 = 210.0
    )

    private val segmentB = ScoredSegment(
        lengthM = 2000.0,
        speedP25 = 15.0, speedP50 = 20.0, speedP75 = 25.0,
        powerP25 = 140.0, powerP50 = 170.0, powerP75 = 200.0
    )

    @Test
    fun `slow percentile produces hand-computed duration and power`() {
        val estimate = RideEstimator.estimateRide(listOf(segmentA, segmentB), Percentile.SLOW)

        requireNotNull(estimate)
        // avgSpeed = (20*1000 + 15*2000) / 3000 = 16.6667 km/h -> duration = 648 s
        assertEquals(648.0, estimate.durationSec, 0.01)
        // segment durations: A = 180s, B = 480s -> weighted power = (180*150 + 480*140) / 660
        assertEquals(142.7273, estimate.avgPowerW, 0.01)
    }

    @Test
    fun `avg percentile produces hand-computed duration and power`() {
        val estimate = RideEstimator.estimateRide(listOf(segmentA, segmentB), Percentile.AVG)

        requireNotNull(estimate)
        // avgSpeed = (25*1000 + 20*2000) / 3000 = 21.6667 km/h -> duration = 498.46 s
        assertEquals(498.4615, estimate.durationSec, 0.01)
        // segment durations: A = 144s, B = 360s -> weighted power = (144*180 + 360*170) / 504
        assertEquals(172.8571, estimate.avgPowerW, 0.01)
    }

    @Test
    fun `fast percentile produces hand-computed duration and power`() {
        val estimate = RideEstimator.estimateRide(listOf(segmentA, segmentB), Percentile.FAST)

        requireNotNull(estimate)
        // avgSpeed = (30*1000 + 25*2000) / 3000 = 26.6667 km/h -> duration = 405 s
        assertEquals(405.0, estimate.durationSec, 0.01)
        // segment durations: A = 120s, B = 288s -> weighted power = (120*210 + 288*200) / 408
        assertEquals(202.9412, estimate.avgPowerW, 0.01)
    }

    @Test
    fun `zero segments returns null`() {
        val estimate = RideEstimator.estimateRide(emptyList(), Percentile.AVG)

        assertNull(estimate)
    }

    @Test
    fun `segments with no speed data anywhere return null`() {
        val noData = ScoredSegment(
            lengthM = 1000.0,
            speedP25 = null, speedP50 = null, speedP75 = null,
            powerP25 = null, powerP50 = null, powerP75 = null
        )

        val estimate = RideEstimator.estimateRide(listOf(noData), Percentile.AVG)

        assertNull(estimate)
    }

    @Test
    fun `segments without power data are excluded from the power average`() {
        val noPowerSegment = segmentA.copy(
            lengthM = 5000.0,
            powerP25 = null, powerP50 = null, powerP75 = null
        )

        val withPower = RideEstimator.estimateRide(listOf(segmentB), Percentile.AVG)
        val mixed = RideEstimator.estimateRide(listOf(segmentB, noPowerSegment), Percentile.AVG)

        requireNotNull(withPower)
        requireNotNull(mixed)
        // The no-power segment contributes to duration/speed but is skipped in the power weighting,
        // so the average power is unchanged from the power-only-segment case.
        assertEquals(withPower.avgPowerW, mixed.avgPowerW, 0.0001)
        assertTrue(mixed.durationSec > withPower.durationSec)
    }

    @Test
    fun `segments without speed data for the percentile still extend total distance for duration`() {
        val noSpeedSegment = ScoredSegment(
            lengthM = 1000.0,
            speedP25 = null, speedP50 = null, speedP75 = null,
            powerP25 = null, powerP50 = null, powerP75 = null
        )

        val withoutExtra = RideEstimator.estimateRide(listOf(segmentA), Percentile.AVG)
        val withExtra = RideEstimator.estimateRide(listOf(segmentA, noSpeedSegment), Percentile.AVG)

        requireNotNull(withoutExtra)
        requireNotNull(withExtra)
        // Same average speed (only segmentA has speed data), but total path length is longer.
        assertTrue(withExtra.durationSec > withoutExtra.durationSec)
    }
}
