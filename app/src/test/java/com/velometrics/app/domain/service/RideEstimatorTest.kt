package com.velometrics.app.domain.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideEstimatorTest {

    private val segmentA = ScoredSegment(
        lengthM = 1000.0,
        speedP25 = 20.0, speedP50 = 25.0, speedP75 = 30.0,
        powerP25 = 150.0, powerP50 = 180.0, powerP75 = 210.0,
        isEstimated = false
    )

    private val segmentB = ScoredSegment(
        lengthM = 2000.0,
        speedP25 = 15.0, speedP50 = 20.0, speedP75 = 25.0,
        powerP25 = 140.0, powerP50 = 170.0, powerP75 = 200.0,
        isEstimated = false
    )

    @Test
    fun `slow percentile produces hand-computed duration and power`() {
        val estimate = RideEstimator.estimateRide(listOf(segmentA, segmentB), Percentile.SLOW)

        // avgSpeed = (20*1000 + 15*2000) / 3000 = 16.6667 km/h -> duration = 648 s
        assertEquals(648.0, estimate.durationSec, 0.01)
        // segment durations: A = 180s, B = 480s -> weighted power = (180*150 + 480*140) / 660
        assertEquals(142.7273, estimate.avgPowerW, 0.01)
        assertFalse(estimate.anyEstimatedSegments)
    }

    @Test
    fun `avg percentile produces hand-computed duration and power`() {
        val estimate = RideEstimator.estimateRide(listOf(segmentA, segmentB), Percentile.AVG)

        // avgSpeed = (25*1000 + 20*2000) / 3000 = 21.6667 km/h -> duration = 498.46 s
        assertEquals(498.4615, estimate.durationSec, 0.01)
        // segment durations: A = 144s, B = 360s -> weighted power = (144*180 + 360*170) / 504
        assertEquals(172.8571, estimate.avgPowerW, 0.01)
        assertFalse(estimate.anyEstimatedSegments)
    }

    @Test
    fun `fast percentile produces hand-computed duration and power`() {
        val estimate = RideEstimator.estimateRide(listOf(segmentA, segmentB), Percentile.FAST)

        // avgSpeed = (30*1000 + 25*2000) / 3000 = 26.6667 km/h -> duration = 405 s
        assertEquals(405.0, estimate.durationSec, 0.01)
        // segment durations: A = 120s, B = 288s -> weighted power = (120*210 + 288*200) / 408
        assertEquals(202.9412, estimate.avgPowerW, 0.01)
        assertFalse(estimate.anyEstimatedSegments)
    }

    @Test
    fun `zero segments returns zeroed estimate`() {
        val estimate = RideEstimator.estimateRide(emptyList(), Percentile.AVG)

        assertEquals(Percentile.AVG, estimate.percentile)
        assertEquals(0.0, estimate.durationSec, 0.0)
        assertEquals(0.0, estimate.avgPowerW, 0.0)
        assertFalse(estimate.anyEstimatedSegments)
    }

    @Test
    fun `segments without power data are excluded from the power average`() {
        val noPowerSegment = segmentA.copy(
            lengthM = 5000.0,
            powerP25 = null, powerP50 = null, powerP75 = null
        )

        val withPower = RideEstimator.estimateRide(listOf(segmentB), Percentile.AVG)
        val mixed = RideEstimator.estimateRide(listOf(segmentB, noPowerSegment), Percentile.AVG)

        // The no-power segment contributes to duration/speed but is skipped in the power weighting,
        // so the average power is unchanged from the power-only-segment case.
        assertEquals(withPower.avgPowerW, mixed.avgPowerW, 0.0001)
        assertTrue(mixed.durationSec > withPower.durationSec)
    }

    @Test
    fun `flags result as estimated when any input segment is estimated`() {
        val estimated = segmentB.copy(isEstimated = true)

        val estimate = RideEstimator.estimateRide(listOf(segmentA, estimated), Percentile.AVG)

        assertTrue(estimate.anyEstimatedSegments)
    }
}
