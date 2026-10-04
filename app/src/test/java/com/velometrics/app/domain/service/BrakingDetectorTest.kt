package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.Datapoint
import com.velometrics.app.domain.model.SpeedIq
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

    /** 30 s pedalling on the flat at 190 W sets P; then a 5 % descent at 50 km/h, an even 15 s stop, standing. */
    private fun descentStop(): Trace {
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
        return trace
    }

    @Test
    fun `braking from 50 to 0 kmh evenly over 15 s on a 5 percent descent`() {
        val result = BrakingDetector.analyze(descentStop().toDatapoints())!!

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

        // The 30 s standing after the resume is a standing-only event, but nothing is braking.
        assertEquals(0.0, result.brakingPenaltySec, 0.0)
        assertTrue(result.topEvents.all { it.brakingEnergyJ == 0.0 })
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
        assertTrue(result.topEvents.zipWithNext().all { (a, b) -> a.lostSec >= b.lostSec })
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

    @Test
    fun `a ride without power is analysed at the estimated P with pedal work at 0`() {
        val measured = BrakingDetector.analyze(descentStop().toDatapoints())!!
        // The same ride without a power meter: power readings are ignored, not just missing.
        val noPower = descentStop().toDatapoints().map { it.copy(power = null) }
        val estimated = BrakingDetector.analyze(noPower, estimatedReferencePowerW = 165.0)!!

        assertTrue(estimated.referencePowerEstimated)
        assertFalse(measured.referencePowerEstimated)
        assertEquals(165, estimated.referencePowerW)
        assertEquals(1, estimated.eventCount)
        val event = estimated.topEvents.single()
        assertEquals(measured.topEvents.single().brakingEnergyJ, event.brakingEnergyJ, 1.0)
        assertEquals(event.brakingEnergyJ / 165.0, event.penaltySec, 1e-9)

        // Stray power readings on a ride flagged without power don't add pedal work.
        val stray = descentStop().toDatapoints().map { it.copy(power = 400) }
        assertEquals(event.brakingEnergyJ, BrakingDetector.analyze(stray, estimatedReferencePowerW = 165.0)!!.topEvents.single().brakingEnergyJ, 1e-9)
    }

    @Test
    fun `the estimated P is the median of the earlier rides' P, else 60 percent of FTP`() {
        assertEquals(165.0, BrakingDetector.estimateReferencePower((120..210 step 10).toList(), ftp = 250), 1e-9)
        assertEquals(190.0, BrakingDetector.estimateReferencePower(listOf(150, 190, 260), ftp = 250), 1e-9)
        assertEquals(150.0, BrakingDetector.estimateReferencePower(emptyList(), ftp = 250), 1e-9)
    }

    /** Pedal at 40 km/h, then brake evenly to [lowKmh] over 10 s with no power. */
    private fun approachLight(lowKmh: Double): Trace {
        val trace = pedalling(30, speedMps = 40 / 3.6)
        for (s in 1..10) trace.add((40 - (40 - lowKmh) * s / 10.0) / 3.6, 100.0, 0)
        return trace
    }

    @Test
    fun `a traffic light under auto-pause counts its standing once and not against net time`() {
        // Braking ends at 4 km/h, the timer pauses for 31 s, the ride resumes at 3 km/h.
        val trace = approachLight(lowKmh = 4.0)
        val lastBeforePause = trace.speeds.lastIndex
        trace.add(3 / 3.6, 100.0, 190)
        pedalling(30, trace)
        val datapoints = trace.toDatapoints(gapAfter = lastBeforePause, gapSec = 30)
        val pause = datapoints[lastBeforePause].timestamp..datapoints[lastBeforePause + 1].timestamp

        val result = BrakingDetector.analyze(datapoints, listOf(pause))!!

        assertEquals(1, result.eventCount)
        val event = result.topEvents.single()
        assertTrue(event.brakingEnergyJ > 0)
        assertEquals(31.0, event.standingSec, 1e-9)
        assertEquals(31.0, result.standingSec, 1e-9)
        assertEquals(0.0, result.standingInTimerSec, 0.0)
        // Potential speed only takes the braking off net time.
        val netSec = 3600
        assertEquals(
            30.0 / ((netSec - result.brakingPenaltySec) / 3600.0),
            result.potentialAvgKmh(30.0, netSec)!!, 1e-9
        )
    }

    @Test
    fun `a traffic light without auto-pause counts its standing and takes it off net time`() {
        // Braking ends at 0 km/h, then 20 records standing with the timer running.
        val trace = approachLight(lowKmh = 0.0)
        standing(20, trace)
        pedalling(30, trace)

        val result = BrakingDetector.analyze(trace.toDatapoints())!!

        assertEquals(1, result.eventCount)
        assertEquals(20.0, result.topEvents.single().standingSec, 1e-9)
        assertEquals(20.0, result.standingInTimerSec, 1e-9)
        val netSec = 3600
        assertEquals(
            30.0 / ((netSec - result.brakingPenaltySec - 20.0) / 3600.0),
            result.potentialAvgKmh(30.0, netSec)!!, 1e-9
        )
    }

    @Test
    fun `a 10 minute stop is a coffee stop and isn't counted`() {
        val trace = approachLight(lowKmh = 0.0)
        standing(601, trace)
        pedalling(30, trace)

        val result = BrakingDetector.analyze(trace.toDatapoints())!!

        assertEquals(1, result.eventCount)
        assertEquals(0.0, result.topEvents.single().standingSec, 0.0)
        assertEquals(0.0, result.standingSec, 0.0)
        assertEquals(0.0, result.standingInTimerSec, 0.0)
    }

    @Test
    fun `a stop with no braking is its own event from 5 s standing`() {
        // Standing at the start, then pedalling away: no braking above the threshold anywhere.
        val long = standing(6, Trace()).also { pedalling(30, it) }
        val longResult = BrakingDetector.analyze(long.toDatapoints())!!
        assertEquals(1, longResult.eventCount)
        val event = longResult.topEvents.single()
        assertEquals(0.0, event.brakingEnergyJ, 0.0)
        assertEquals(5.0, event.standingSec, 1e-9)
        assertEquals(5.0, longResult.standingInTimerSec, 1e-9)

        val short = standing(5, Trace()).also { pedalling(30, it) }
        val shortResult = BrakingDetector.analyze(short.toDatapoints())!!
        assertEquals(0, shortResult.eventCount)
        assertEquals(0.0, shortResult.standingSec, 0.0)
        assertEquals(0.0, shortResult.standingInTimerSec, 0.0)
    }

    @Test
    fun `penalty seconds scale linearly with system mass`() {
        // A hard stop on a descent, so every step brakes far above the threshold at all three masses
        // and the event spans the same steps (a gentle stop's tail would drop out at 70 kg).
        val trace = pedalling(30, altitude = 200.0)
        var h = 200.0
        repeat(10) { trace.add(50 / 3.6, h, 0); h -= 50 / 3.6 * 0.05 }
        for (s in 1..6) {
            val v = 50 / 3.6 * (1 - s / 6.0)
            h -= v * 0.05
            trace.add(v, h, 0)
        }
        standing(20, trace)
        val datapoints = trace.toDatapoints()
        val penalty = listOf(70.0, 85.0, 100.0).map { m ->
            BrakingDetector.analyze(datapoints, massKg = m)!!.also { assertEquals(m, it.systemMassKg, 0.0) }.brakingPenaltySec
        }

        // Kinetic, potential and rolling terms are all m-proportional; drag is not, so the line has an offset.
        assertTrue(penalty[0] < penalty[1] && penalty[1] < penalty[2])
        assertEquals(penalty[1] - penalty[0], penalty[2] - penalty[1], 1e-6)
    }

    @Test
    fun `potential average speed - 60 km in 2 h net, 3_12 braking, 1_00 in-timer standing`() {
        val speedIq = SpeedIq(
            hasElevation = true, brakingPenaltySec = 192.0, standingSec = 280.0, standingInTimerSec = 60.0,
            eventCount = 17, referencePowerW = 190, systemMassKg = mass, topEvents = emptyList()
        )
        assertEquals(31.1, speedIq.potentialAvgKmh(60.0, 7200)!!, 0.05)
    }
}
