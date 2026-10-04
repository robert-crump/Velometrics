package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.Datapoint
import com.velometrics.app.util.CyclingConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

class BrakingDetectorTest {

    private val mass = 85.0
    private val g = CyclingConstants.SPEED_IQ_GRAVITY
    private val start = Instant.parse("2026-07-01T09:00:00Z")

    /** One record per second; positions run due north by the distance covered. */
    private class Trace {
        val speeds = mutableListOf<Double>()
        val altitudes = mutableListOf<Double?>()
        val powers = mutableListOf<Int>()

        fun add(speedMps: Double, altitudeM: Double?, power: Int) {
            speeds += speedMps; altitudes += altitudeM; powers += power
        }
    }

    private fun Trace.toDatapoints(gapAfter: Int? = null, gapSec: Long = 0): List<Datapoint> {
        var northM = 0.0
        var t = 0L
        return speeds.indices.map { i ->
            if (i > 0) {
                northM += (speeds[i - 1] + speeds[i]) / 2
                t += 1 + if (gapAfter == i - 1) gapSec else 0
            }
            Datapoint(
                lat = 50.0 + northM / 111_320.0,
                lon = 6.0,
                speedKmh = speeds[i] * 3.6,
                power = powers[i],
                timestamp = start.plusSeconds(t),
                altitude = altitudes[i]
            )
        }
    }

    /**
     * Integrates the physics at [power] watts on [grade] in 0.1 s steps (so the trace is
     * consistent with the detector's own model) and records once a second, until [stop] holds.
     */
    private fun Trace.ride(power: Int, grade: Double, seconds: Int, stop: (Double) -> Boolean = { false }) {
        var v = speeds.last()
        var h = altitudes.last()!!
        repeat(seconds) {
            repeat(10) {
                val drive = power / maxOf(v, 0.5)
                val resist = mass * g * (sin(atan(grade)) + CyclingConstants.SPEED_IQ_CRR * cos(atan(grade))) +
                    0.5 * CyclingConstants.SPEED_IQ_AIR_DENSITY * CyclingConstants.SPEED_IQ_CDA_M2 * v * v
                val vNew = (v + (drive - resist) / mass * 0.1).coerceAtLeast(0.0)
                h += grade * (v + vNew) / 2 * 0.1
                v = vNew
            }
            add(v, h, power)
            if (stop(v)) return
        }
    }

    private fun pedalling(seconds: Int, trace: Trace = Trace(), speedMps: Double = 8.0, altitude: Double = 100.0) =
        trace.apply { repeat(seconds) { add(speedMps, altitude, 190) } }

    private fun standing(seconds: Int, trace: Trace) = trace.apply { repeat(seconds) { add(0.0, 100.0, 0) } }

    @Test
    fun `coasting from 50 to 25 kmh on the flat gives no events`() {
        val trace = Trace().apply { add(50 / 3.6, 100.0, 0) }
        trace.ride(power = 0, grade = 0.0, seconds = 600) { it <= 25 / 3.6 }
        pedalling(30, trace, speedMps = 25 / 3.6)

        val result = BrakingDetector.analyze(trace.toDatapoints())!!

        assertTrue(result.hasElevation)
        assertEquals(0, result.eventCount)
    }

    @Test
    fun `turning onto an 8 percent climb at constant 190 W gives no events`() {
        val trace = Trace().apply { add(30 / 3.6, 100.0, 190) }
        trace.ride(power = 190, grade = 0.0, seconds = 60)
        trace.ride(power = 190, grade = 0.08, seconds = 300) { it <= 14 / 3.6 }
        assertTrue("climb slowed the rider to 14 km/h", trace.speeds.last() <= 14 / 3.6 + 0.01)
        trace.ride(power = 190, grade = 0.08, seconds = 30)

        val result = BrakingDetector.analyze(trace.toDatapoints())!!

        assertEquals(0, result.eventCount)
    }

    @Test
    fun `braking from 50 to 0 kmh evenly over 15 s on a 5 percent descent`() {
        // 30 s pedalling on the flat at 190 W sets P; then the descent at 50 km/h, the stop, standing.
        val trace = pedalling(30, altitude = 200.0)
        var h = 200.0
        repeat(10) { trace.add(50 / 3.6, h, 0); h -= 50 / 3.6 * 0.05 }
        val v0 = 50 / 3.6
        for (s in 1..15) {
            val vPrev = v0 * (1 - (s - 1) / 15.0)
            val v = v0 * (1 - s / 15.0)
            h -= (vPrev + v) / 2 * 0.05
            trace.add(v, h, 0)
        }
        repeat(20) { trace.add(0.0, h, 0) }

        val result = BrakingDetector.analyze(trace.toDatapoints())!!

        // Over the ~104 m stop: ½mv² 8.2 kJ + m·g·Δh 4.3 kJ − drag 2.3 kJ − rolling 1.1 kJ ≈ 9.1 kJ.
        // (#223's worked example says ≈ 10.9 kJ / 57 s; with CdA 0.37 and ρ 1.225 the drag over the
        // stop is ~2.3 kJ, which puts the same formula at ≈ 9.1 kJ / ≈ 48 s.)
        assertEquals(1, result.eventCount)
        assertEquals(190, result.referencePowerW)
        val event = result.topEvents.single()
        assertEquals(9100.0, event.brakingEnergyJ, 500.0)
        assertEquals(48.0, event.penaltySec, 3.0)
        assertEquals(50.0, event.peakKmh, 0.01)
        assertEquals(0.0, event.lowKmh, 0.01)
        assertEquals(result.brakingPenaltySec, event.penaltySec, 1e-9)
    }

    @Test
    fun `braking stretches less than 5 s apart are joined, further apart are not`() {
        val brake = 400.0
        val idle = 0.0
        val dt = DoubleArray(30) { 1.0 }

        val close = DoubleArray(30) { idle }.also { for (k in 2..4) it[k] = brake; for (k in 9..11) it[k] = brake }
        assertEquals(listOf(2..11), BrakingDetector.groupEvents(close, dt))

        val apart = DoubleArray(30) { idle }.also { for (k in 2..4) it[k] = brake; for (k in 10..12) it[k] = brake }
        assertEquals(listOf(2..4, 10..12), BrakingDetector.groupEvents(apart, dt))
    }

    @Test
    fun `a single second above the threshold is not braking`() {
        val brakeW = DoubleArray(10).also { it[3] = 400.0; it[7] = 400.0; it[8] = 400.0 }
        assertEquals(listOf(7..8), BrakingDetector.groupEvents(brakeW, DoubleArray(10) { 1.0 }))
    }

    @Test
    fun `events under 1_5 kJ are dropped`() {
        // 30 -> 20 km/h on the flat in 3 s: ½m(v₁²−v₂²) ≈ 1.6 kJ, minus drag and rolling < 1.5 kJ.
        val trace = pedalling(30, speedMps = 30 / 3.6)
        listOf(26.0, 23.0, 20.0).forEach { trace.add(it / 3.6, 100.0, 0) }
        pedalling(30, trace, speedMps = 20 / 3.6)

        assertEquals(0, BrakingDetector.analyze(trace.toDatapoints())!!.eventCount)
    }

    @Test
    fun `a timer pause splits the stream instead of reading as braking`() {
        // Arrive at 30 km/h, pause the timer for 2 min, resume at 0 km/h.
        val trace = pedalling(30, speedMps = 30 / 3.6)
        standing(30, trace)
        pedalling(10, trace)

        val result = BrakingDetector.analyze(trace.toDatapoints(gapAfter = 29, gapSec = 120))!!

        assertEquals(0, result.eventCount)
    }

    @Test
    fun `only the top 5 events are kept, the totals count them all`() {
        val trace = pedalling(30, speedMps = 40 / 3.6)
        repeat(7) { i ->
            val peak = (30 + i * 3) / 3.6
            for (s in 0..10) trace.add(peak * (1 - s / 10.0), 100.0, 0)
            standing(30, trace)
            pedalling(30, trace)
        }

        val result = BrakingDetector.analyze(trace.toDatapoints())!!

        assertEquals(7, result.eventCount)
        assertEquals(5, result.topEvents.size)
        assertTrue(result.topEvents.zipWithNext().all { (a, b) -> a.penaltySec >= b.penaltySec })
        assertTrue(result.brakingPenaltySec > result.topEvents.sumOf { it.penaltySec })
    }

    @Test
    fun `a ride without altitude has no elevation and no events`() {
        val trace = Trace().apply { repeat(60) { add(8.0, null, 190) } }

        val result = BrakingDetector.analyze(trace.toDatapoints())

        assertNotNull(result)
        assertFalse(result!!.hasElevation)
        assertEquals(0, result.eventCount)
        assertTrue(result.topEvents.isEmpty())
    }

    @Test
    fun `a ride without power has no Speed IQ`() {
        val trace = Trace().apply { repeat(60) { add(8.0, 100.0, 0) } }
        assertNull(BrakingDetector.analyze(trace.toDatapoints()))
    }
}
