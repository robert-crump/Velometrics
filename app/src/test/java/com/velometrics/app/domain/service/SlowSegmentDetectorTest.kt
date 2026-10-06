package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.Datapoint
import com.velometrics.app.domain.model.SpeedIq
import com.velometrics.app.util.CyclingConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Slow segments (#225) through [SpeedIqAnalyzer], on synthetic one-record-per-second streams. */
class SlowSegmentDetectorTest {

    private val mass = 85.0
    private val start = Instant.parse("2026-07-01T09:00:00Z")

    /** Steady speed at 190 W on the flat, so k comes out at 1 for the steady parts. */
    private val cruiseMps = RidePhysics.steadySpeedMps(190.0, 0.0, mass)
    private val cruiseKmh = cruiseMps * 3.6

    private class Trace {
        val speeds = mutableListOf<Double>()
        val altitudes = mutableListOf<Double>()
        val powers = mutableListOf<Int>()
        var altitude = 100.0

        fun add(speedMps: Double, power: Int, grade: Double) {
            if (speeds.isNotEmpty()) altitude += grade * (speeds.last() + speedMps) / 2
            speeds += speedMps; altitudes += altitude; powers += power
        }

        fun steady(seconds: Int, speedMps: Double, power: Int, grade: Double = 0.0) = apply {
            repeat(seconds) { add(speedMps, power, grade) }
        }

        /** Linear speed change over [seconds], ending at [toMps]. */
        fun ramp(seconds: Int, toMps: Double, power: Int, grade: Double = 0.0) = apply {
            val from = speeds.last()
            for (s in 1..seconds) add(from + (toMps - from) * s / seconds, power, grade)
        }
    }

    private fun Trace.toDatapoints(): List<Datapoint> {
        var northM = 0.0
        return speeds.indices.map { i ->
            if (i > 0) northM += (speeds[i - 1] + speeds[i]) / 2
            Datapoint(
                lat = 50.0 + northM / 111_320.0, lon = 6.0, speedKmh = speeds[i] * 3.6,
                power = powers[i], timestamp = start.plusSeconds(i.toLong()), altitude = altitudes[i]
            )
        }
    }

    private fun analyze(trace: Trace): SpeedIq = SpeedIqAnalyzer.analyze(trace.toDatapoints(), massKg = mass)!!

    @Test
    fun `steady speed at the physics prediction is k 1 and no slow segment`() {
        val result = analyze(Trace().steady(600, cruiseMps, 190))

        assertEquals(1.0, result.speedFactor!!, 0.01)
        assertEquals(0.0, result.slowSec!!, 0.0)
        assertEquals(0, result.eventCount)
    }

    @Test
    fun `a gate on the flat is a slow-only event against the speed before it`() {
        // 30 → 15 km/h freewheeling over 10 s (too gentle for a braking event), 40 s at 15 km/h and
        // 120 W, 10 s back up at 300 W.
        val trace = Trace().steady(300, cruiseMps, 190)
            .ramp(10, 15 / 3.6, 0)
            .steady(40, 15 / 3.6, 120)
            .ramp(10, cruiseMps, 300)
            .steady(300, cruiseMps, 190)

        val result = analyze(trace)

        assertEquals(1, result.eventCount)
        val event = result.topEvents.single()
        assertEquals(0.0, event.brakingEnergyJ, 0.0)
        assertEquals(0.0, event.standingSec, 0.0)
        // 40 s at half the expected speed alone loses 20 s; the ramps add some
        assertTrue("${event.slowSec}", event.slowSec in 22.0..35.0)
        assertTrue("${event.slowAvgKmh}", event.slowAvgKmh!! < 20)
        assertEquals(cruiseKmh, event.slowExpectedKmh!!, 1.5)
        assertEquals(result.slowSec!!, event.slowSec, 1e-9)
    }

    @Test
    fun `a slow segment right after hard braking is part of the braking event`() {
        val trace = Trace().steady(300, cruiseMps, 190)
            .ramp(4, 5 / 3.6, 0)
            .steady(30, 12 / 3.6, 100)
            .ramp(10, cruiseMps, 300)
            .steady(300, cruiseMps, 190)

        val result = analyze(trace)

        assertEquals(1, result.eventCount)
        val event = result.topEvents.single()
        assertTrue(event.brakingEnergyJ > 0)
        assertTrue("${event.slowSec}", event.slowSec > 10)
        assertEquals(event.penaltySec + event.slowSec, event.lostSec, 1e-9)
    }

    @Test
    fun `climb power doesn't carry onto the flat at a junction at the top`() {
        // Mostly 190 W, so the ride's p75 pedalling power is 190 W, not the climb's 280 W
        val climbMps = RidePhysics.steadySpeedMps(280.0, 0.06, mass)
        val trace = Trace().steady(600, cruiseMps, 190)
            .steady(120, climbMps, 280, grade = 0.06)
            .steady(40, 12 / 3.6, 100)
            .ramp(10, cruiseMps, 300)
            .steady(600, cruiseMps, 190)

        val event = analyze(trace).topEvents.single { it.slowSec > 0 }

        // Frozen at 280 W the flat would expect ~33 km/h
        val at280Kmh = RidePhysics.steadySpeedMps(280.0, 0.0, mass) * 3.6
        assertTrue("${event.slowExpectedKmh} vs $at280Kmh", event.slowExpectedKmh!! < cruiseKmh + 1.0)
    }

    @Test
    fun `descents are no slow segments, steep or gentle`() {
        // Coasting a 6 % descent at 45 km/h, then easing off at 28 km/h on a 2 % one
        val trace = Trace().steady(300, cruiseMps, 190)
            .ramp(20, 45 / 3.6, 0, grade = -0.06)
            .steady(40, 45 / 3.6, 0, grade = -0.06)
            .ramp(25, 28 / 3.6, 0, grade = -0.02)
            .steady(60, 28 / 3.6, 0, grade = -0.02)
            .ramp(5, cruiseMps, 300)
            .steady(300, cruiseMps, 190)

        val result = analyze(trace)

        assertEquals(0.0, result.slowSec!!, 0.0)
        assertTrue(result.topEvents.none { it.slowSec > 0 })
    }

    @Test
    fun `a freeze ends after 5 minutes, splitting long gravel into separate events`() {
        val gravelKmh = 18.0
        val trace = Trace().steady(600, cruiseMps, 190)
            .ramp(5, gravelKmh / 3.6, 190)
            .steady(600, gravelKmh / 3.6, 190)
            .ramp(5, cruiseMps, 190)
            .steady(900, cruiseMps, 190)

        val result = analyze(trace)

        val slow = result.topEvents.filter { it.slowSec > 0 }
        assertTrue("${slow.size} events", slow.size >= 2)
        val perFreezeMax = CyclingConstants.SPEED_IQ_SLOW_MAX_FREEZE_SEC * (1 - gravelKmh / cruiseKmh) + 5
        slow.forEach { assertTrue("${it.slowSec}", it.slowSec <= perFreezeMax) }
        // Same 190 W throughout, so the gravel is counted all the way: 600 s at 18 instead of ~30 km/h
        assertEquals(600 * (1 - gravelKmh / cruiseKmh), result.slowSec!!, 20.0)
    }

    @Test
    fun `a ride without power has no slow segments`() {
        val trace = Trace().steady(300, cruiseMps, 190)
            .ramp(10, 15 / 3.6, 0)
            .steady(40, 15 / 3.6, 120)
            .steady(300, cruiseMps, 190)

        val result = SpeedIqAnalyzer.analyze(trace.toDatapoints(), massKg = mass, estimatedReferencePowerW = 165.0)!!

        assertNull(result.slowSec)
        assertNull(result.speedFactor)
        assertTrue(result.topEvents.all { it.slowSec == 0.0 })
    }

    @Test
    fun `potential average speed takes the slow loss off too`() {
        val result = analyze(
            Trace().steady(300, cruiseMps, 190).ramp(10, 15 / 3.6, 0).steady(40, 15 / 3.6, 120)
                .ramp(10, cruiseMps, 300).steady(300, cruiseMps, 190)
        )
        val slowSec = result.slowSec!!
        assertTrue(slowSec > 0)

        assertEquals(30.0 / ((3600 - slowSec) / 3600.0), result.potentialAvgKmh(30.0, 3600)!!, 1e-9)
    }

    @Test
    fun `steady speed inverts the power balance`() {
        val v = RidePhysics.steadySpeedMps(190.0, 0.02, mass)
        val power = RidePhysics.dragW(v) + RidePhysics.rollingW(mass, v) + mass * CyclingConstants.SPEED_IQ_GRAVITY * v * 0.02
        assertEquals(190.0, power, 0.01)
        assertEquals(0.0, RidePhysics.steadySpeedMps(0.0, 0.0, mass), 0.0)
    }
}
