package com.velometrics.app.domain.service

import com.velometrics.app.data.fitimport.HrDistanceSeriesBuilder
import com.velometrics.app.domain.model.BrakingEvent
import com.velometrics.app.domain.model.Datapoint
import com.velometrics.app.domain.model.SpeedIq
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.median
import java.time.Duration
import java.time.Instant

/**
 * Speed IQ braking events (#226), energy-based: per second, the energy that went neither into
 * speed, height, drag nor rolling resistance was braked away.
 *
 * `E = ½m(v₁²−v₂²) + m·g·(h₁−h₂) + pedal work − drag − rolling` per record-to-record step, so an
 * event's energy is just the sum of its steps. Speed is smoothed over 3 s and altitude over ~10 s
 * (both centred). A step braking above [CyclingConstants.SPEED_IQ_BRAKE_POWER_W] for at least
 * [CyclingConstants.SPEED_IQ_MIN_BRAKE_SEC] starts a stretch, stretches less than
 * [CyclingConstants.SPEED_IQ_JOIN_GAP_SEC] apart are one event, and an event counts from
 * [CyclingConstants.SPEED_IQ_MIN_EVENT_ENERGY_J]. Its penalty is E / P, with P the median of the
 * ride's pedalling samples: the seconds of pedalling the braked energy was worth.
 *
 * Standing (#227) is clock seconds below [CyclingConstants.SPEED_IQ_STANDING_KMH] or with the timer
 * paused. A stop touching a braking event belongs to it; one with no braking is its own event from
 * [CyclingConstants.SPEED_IQ_MIN_STANDING_ONLY_SEC]. A stop longer than
 * [CyclingConstants.SPEED_IQ_COFFEE_STOP_SEC] isn't counted at all.
 */
object BrakingDetector {

    /**
     * A ride without power passes [estimatedReferencePowerW] (#229, see [estimateReferencePower]):
     * its power readings are ignored, so pedal work counts as 0, and P is that estimate. Otherwise
     * null without pedalling samples. A ride where too few records carry an altitude gets
     * [SpeedIq.hasElevation] false and no events. [pauses] are the FIT timer's stop→start
     * intervals, the same ones that make up the ride's pause duration.
     */
    fun analyze(
        datapoints: List<Datapoint>,
        pauses: List<ClosedRange<Instant>> = emptyList(),
        massKg: Double = CyclingConstants.SPEED_IQ_DEFAULT_SYSTEM_MASS_KG,
        estimatedReferencePowerW: Double? = null
    ): SpeedIq? {
        val estimated = estimatedReferencePowerW != null
        val points = if (estimated) datapoints.map { it.copy(power = null) } else datapoints
        val referencePower = estimatedReferencePowerW?.takeIf { it > 0 } ?: points
            .filter { (it.power ?: 0) > 0 && (it.speedKmh ?: 0.0) >= CyclingConstants.SPEED_IQ_MOVING_KMH }
            .map { it.power!!.toDouble() }
            .median() ?: return null
        return analyzeAt(points, pauses, massKg, referencePower).copy(referencePowerEstimated = estimated)
    }

    /**
     * P for a ride without power (#229): the median of [priorReferencePowersW], the per-ride P of
     * the last [CyclingConstants.SPEED_IQ_FALLBACK_RIDES] rides with power before it, else
     * [CyclingConstants.SPEED_IQ_FALLBACK_FTP_FRACTION] of the FTP as of the ride.
     */
    fun estimateReferencePower(priorReferencePowersW: List<Int>, ftp: Int): Double =
        priorReferencePowersW.filter { it > 0 }.map { it.toDouble() }.median()
            ?: (ftp * CyclingConstants.SPEED_IQ_FALLBACK_FTP_FRACTION)

    private fun analyzeAt(
        datapoints: List<Datapoint>,
        pauses: List<ClosedRange<Instant>>,
        massKg: Double,
        referencePower: Double
    ): SpeedIq {
        val altitudeCount = datapoints.count { it.altitude != null }
        if (altitudeCount == 0 || altitudeCount < datapoints.size * CyclingConstants.POWER_DATA_COVERAGE_THRESHOLD) {
            return SpeedIq(false, 0.0, 0.0, 0.0, 0, referencePower.toInt(), massKg, topEvents = emptyList())
        }

        val cumulativeM = HrDistanceSeriesBuilder.cumulativeMeters(datapoints)
        val braking = segments(datapoints).flatMap { detect(datapoints, it, cumulativeM, massKg, referencePower) }

        val standingSec = DoubleArray(braking.size)
        val standingOnly = mutableListOf<BrakingEvent>()
        var standingInTimerSec = 0.0
        for (stop in stops(datapoints, cumulativeM, pauses)) {
            val k = braking.indexOfFirst {
                seconds(it.end, stop.start) < CyclingConstants.SPEED_IQ_JOIN_GAP_SEC && stop.end >= it.start
            }
            if (k >= 0) {
                standingSec[k] += stop.sec
            } else if (stop.sec >= CyclingConstants.SPEED_IQ_MIN_STANDING_ONLY_SEC) {
                val at = datapoints.indexOfLast { it.timestamp <= stop.start }.coerceAtLeast(0)
                standingOnly += BrakingEvent(
                    km = cumulativeM[at] / 1000.0, brakingEnergyJ = 0.0, penaltySec = 0.0,
                    peakKmh = 0.0, lowKmh = 0.0, lat = datapoints[at].lat, lon = datapoints[at].lon,
                    standingSec = stop.sec
                )
            } else {
                continue
            }
            standingInTimerSec += stop.inTimerSec
        }
        val events = braking.mapIndexed { k, it -> it.event.copy(standingSec = standingSec[k]) } + standingOnly

        return SpeedIq(
            hasElevation = true,
            brakingPenaltySec = events.sumOf { it.penaltySec },
            standingSec = events.sumOf { it.standingSec },
            standingInTimerSec = standingInTimerSec,
            eventCount = events.size,
            referencePowerW = referencePower.toInt(),
            systemMassKg = massKg,
            topEvents = events.sortedByDescending { it.lostSec }.take(CyclingConstants.SPEED_IQ_TOP_EVENTS)
        )
    }

    /** A braking event with the time span of its records, for matching stops to it. */
    private class Detected(val event: BrakingEvent, val start: Instant, val end: Instant)

    /** One standing episode: timer-paused seconds plus seconds below the standing speed with the timer running. */
    private class Stop(val start: Instant, val end: Instant, val pausedSec: Double, val inTimerSec: Double) {
        val sec get() = pausedSec + inTimerSec
    }

    /**
     * The ride's stops, coffee stops left out. Pieces are the timer pauses and the record-to-record
     * steps with both ends below [CyclingConstants.SPEED_IQ_STANDING_KMH] (a step inside a pause
     * isn't counted twice); pieces closer than [CyclingConstants.SPEED_IQ_JOIN_GAP_SEC] are one stop.
     */
    private fun stops(datapoints: List<Datapoint>, cumulativeM: DoubleArray, pauses: List<ClosedRange<Instant>>): List<Stop> {
        val pieces = pauses.filter { it.endInclusive > it.start }
            .map { Stop(it.start, it.endInclusive, seconds(it.start, it.endInclusive), 0.0) }
            .toMutableList()
        val standingKmh = CyclingConstants.SPEED_IQ_STANDING_KMH
        for (i in 1 until datapoints.size) {
            val a = datapoints[i - 1].timestamp
            val b = datapoints[i].timestamp
            val dt = seconds(a, b)
            if (dt <= 0 || dt > CyclingConstants.SPEED_IQ_MAX_SAMPLE_GAP_SEC) continue
            if (speedKmh(datapoints, cumulativeM, i - 1) >= standingKmh || speedKmh(datapoints, cumulativeM, i) >= standingKmh) continue
            val mid = a.plusMillis((dt * 500).toLong())
            if (pauses.any { mid in it }) continue
            pieces += Stop(a, b, 0.0, dt)
        }
        pieces.sortBy { it.start }

        val merged = mutableListOf<Stop>()
        for (piece in pieces) {
            val last = merged.lastOrNull()
            if (last != null && seconds(last.end, piece.start) < CyclingConstants.SPEED_IQ_JOIN_GAP_SEC) {
                merged[merged.lastIndex] = Stop(
                    last.start, maxOf(last.end, piece.end),
                    last.pausedSec + piece.pausedSec, last.inTimerSec + piece.inTimerSec
                )
            } else {
                merged += piece
            }
        }
        return merged.filter { it.sec <= CyclingConstants.SPEED_IQ_COFFEE_STOP_SEC }
    }

    /** The record's speed, or the speed over the step before it when it has none. */
    private fun speedKmh(datapoints: List<Datapoint>, cumulativeM: DoubleArray, i: Int): Double {
        datapoints[i].speedKmh?.let { return it }
        if (i == 0) return 0.0
        val dt = seconds(datapoints[i - 1].timestamp, datapoints[i].timestamp)
        return if (dt > 0) (cumulativeM[i] - cumulativeM[i - 1]) / dt * CyclingConstants.MTS_PER_SEC_TO_KMH else 0.0
    }

    private fun seconds(from: Instant, to: Instant) = Duration.between(from, to).toMillis() / 1000.0

    /** Index ranges of records with no gap over [CyclingConstants.SPEED_IQ_MAX_SAMPLE_GAP_SEC] (timer pauses). */
    private fun segments(datapoints: List<Datapoint>): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var start = 0
        for (i in 1..datapoints.size) {
            val split = i == datapoints.size || run {
                val dt = Duration.between(datapoints[i - 1].timestamp, datapoints[i].timestamp).seconds
                dt <= 0 || dt > CyclingConstants.SPEED_IQ_MAX_SAMPLE_GAP_SEC
            }
            if (split) {
                if (i - start >= 2) result += start until i
                start = i
            }
        }
        return result
    }

    private fun detect(
        datapoints: List<Datapoint>,
        segment: IntRange,
        cumulativeM: DoubleArray,
        massKg: Double,
        referencePower: Double
    ): List<Detected> {
        val first = segment.first
        val n = segment.last - first + 1
        val t = DoubleArray(n) { Duration.between(datapoints[first].timestamp, datapoints[first + it].timestamp).seconds.toDouble() }
        val rawSpeed = DoubleArray(n) { k ->
            val i = first + k
            datapoints[i].speedKmh?.div(CyclingConstants.MTS_PER_SEC_TO_KMH)
                ?: if (k == 0) 0.0 else (cumulativeM[i] - cumulativeM[i - 1]) / (t[k] - t[k - 1])
        }
        val v = smooth(t, rawSpeed, CyclingConstants.SPEED_IQ_SPEED_SMOOTH_HALF_SEC)
        val h = smooth(t, filledAltitudes(datapoints, segment), CyclingConstants.SPEED_IQ_ALTITUDE_SMOOTH_HALF_SEC)

        // Step k runs from record k to k + 1; a record's power covers the second before it.
        val g = CyclingConstants.SPEED_IQ_GRAVITY
        val stepEnergy = DoubleArray(n - 1)
        val stepDt = DoubleArray(n - 1)
        for (k in 0 until n - 1) {
            val dt = t[k + 1] - t[k]
            val vAvg = (v[k] + v[k + 1]) / 2
            val kinetic = 0.5 * massKg * (v[k] * v[k] - v[k + 1] * v[k + 1])
            val potential = massKg * g * (h[k] - h[k + 1])
            val pedal = (datapoints[first + k + 1].power ?: 0) * dt
            val drag = 0.5 * CyclingConstants.SPEED_IQ_AIR_DENSITY * CyclingConstants.SPEED_IQ_CDA_M2 * vAvg * vAvg * vAvg * dt
            val rolling = CyclingConstants.SPEED_IQ_CRR * massKg * g * vAvg * dt
            stepEnergy[k] = kinetic + potential + pedal - drag - rolling
            stepDt[k] = dt
        }

        return groupEvents(DoubleArray(n - 1) { stepEnergy[it] / stepDt[it] }, stepDt).mapNotNull { steps ->
            val energy = steps.sumOf { stepEnergy[it] }
            if (energy < CyclingConstants.SPEED_IQ_MIN_EVENT_ENERGY_J) return@mapNotNull null
            // Records first..last+1 bound the event's steps, extended while the speed keeps falling
            // (the last metres to a halt brake too little to stay above the threshold); peak is the
            // fastest record before the slowest.
            var lastRecord = steps.last + 1
            while (lastRecord + 1 < n && rawSpeed[lastRecord + 1] < rawSpeed[lastRecord]) lastRecord++
            val records = steps.first..lastRecord
            val lowK = records.minBy { rawSpeed[it] }
            val peakK = (records.first..lowK).maxBy { rawSpeed[it] }
            val low = datapoints[first + lowK]
            val event = BrakingEvent(
                km = cumulativeM[first + lowK] / 1000.0,
                brakingEnergyJ = energy,
                penaltySec = energy / referencePower,
                peakKmh = rawSpeed[peakK] * CyclingConstants.MTS_PER_SEC_TO_KMH,
                lowKmh = rawSpeed[lowK] * CyclingConstants.MTS_PER_SEC_TO_KMH,
                lat = low.lat,
                lon = low.lon
            )
            Detected(event, datapoints[first + records.first].timestamp, datapoints[first + records.last].timestamp)
        }
    }

    /**
     * Step ranges of braking events: runs of steps above [CyclingConstants.SPEED_IQ_BRAKE_POWER_W]
     * lasting at least [CyclingConstants.SPEED_IQ_MIN_BRAKE_SEC], joined across gaps shorter than
     * [CyclingConstants.SPEED_IQ_JOIN_GAP_SEC]. The energy threshold is applied by the caller.
     */
    internal fun groupEvents(brakeW: DoubleArray, dtSec: DoubleArray): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var k = 0
        while (k < brakeW.size) {
            if (brakeW[k] <= CyclingConstants.SPEED_IQ_BRAKE_POWER_W) { k++; continue }
            val start = k
            while (k < brakeW.size && brakeW[k] > CyclingConstants.SPEED_IQ_BRAKE_POWER_W) k++
            if ((start until k).sumOf { dtSec[it] } >= CyclingConstants.SPEED_IQ_MIN_BRAKE_SEC) runs += start until k
        }
        val events = mutableListOf<IntRange>()
        for (run in runs) {
            val previous = events.lastOrNull()
            val gapSec = previous?.let { (previous.last + 1 until run.first).sumOf { dtSec[it] } }
            if (previous != null && gapSec!! < CyclingConstants.SPEED_IQ_JOIN_GAP_SEC) {
                events[events.lastIndex] = previous.first..run.last
            } else {
                events += run
            }
        }
        return events
    }

    /** Altitudes over [segment], missing ones carried from the nearest earlier (else later) record. */
    private fun filledAltitudes(datapoints: List<Datapoint>, segment: IntRange): DoubleArray {
        val firstKnown = segment.firstNotNullOfOrNull { datapoints[it].altitude } ?: 0.0
        var last = firstKnown
        return DoubleArray(segment.last - segment.first + 1) { k ->
            datapoints[segment.first + k].altitude?.also { last = it } ?: last
        }
    }

    /** Centred moving average over records within [halfSec] seconds of each record. */
    private fun smooth(t: DoubleArray, values: DoubleArray, halfSec: Int): DoubleArray {
        val result = DoubleArray(values.size)
        var lo = 0
        var hi = 0
        var sum = 0.0
        for (i in values.indices) {
            while (hi < values.size && t[hi] - t[i] <= halfSec) { sum += values[hi]; hi++ }
            while (t[i] - t[lo] > halfSec) { sum -= values[lo]; lo++ }
            result[i] = sum / (hi - lo)
        }
        return result
    }
}
