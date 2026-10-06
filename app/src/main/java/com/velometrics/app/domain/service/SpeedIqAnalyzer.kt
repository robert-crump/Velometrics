package com.velometrics.app.domain.service

import com.velometrics.app.data.fitimport.HrDistanceSeriesBuilder
import com.velometrics.app.domain.model.Datapoint
import com.velometrics.app.domain.model.SpeedIq
import com.velometrics.app.domain.model.SpeedIqEvent
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.median
import java.time.Instant

/**
 * Speed IQ for one ride (#223): braking and standing events from [BrakingDetector], then slow
 * segments from [SlowSegmentDetector] around them (#225). A slow segment within
 * [CyclingConstants.SPEED_IQ_JOIN_GAP_SEC] of a braking or standing event is part of that event
 * (brake, crawl through the gate, accelerate is one place); any other is its own slow-only event.
 */
object SpeedIqAnalyzer {

    /**
     * A ride without power passes [estimatedReferencePowerW] (#229, see [estimateReferencePower]):
     * its power readings are ignored, so pedal work counts as 0, P is that estimate, and there are no
     * slow segments (no power to expect a speed from). Otherwise null without pedalling samples. A
     * ride where too few records carry an altitude gets [SpeedIq.hasElevation] false and no events.
     * [pauses] are the FIT timer's stop→start intervals, the same ones that make up the ride's pause
     * duration. [ftp] is the FTP in force on the ride date (ADR 0001) for the pedalling veto; null,
     * or a ride without power, skips the veto.
     */
    fun analyze(
        datapoints: List<Datapoint>,
        pauses: List<ClosedRange<Instant>> = emptyList(),
        massKg: Double = CyclingConstants.SPEED_IQ_DEFAULT_SYSTEM_MASS_KG,
        estimatedReferencePowerW: Double? = null,
        ftp: Int? = null,
        topEventCount: Int = CyclingConstants.SPEED_IQ_TOP_EVENTS
    ): SpeedIq? {
        val estimated = estimatedReferencePowerW != null
        val points = if (estimated) datapoints.map { it.copy(power = null) } else datapoints
        val referencePower = estimatedReferencePowerW?.takeIf { it > 0 } ?: points
            .filter { (it.power ?: 0) > 0 && (it.speedKmh ?: 0.0) >= CyclingConstants.SPEED_IQ_MOVING_KMH }
            .map { it.power!!.toDouble() }
            .median() ?: return null
        val vetoPowerW = ftp?.takeIf { !estimated && it > 0 }?.let { it * CyclingConstants.SPEED_IQ_PEDAL_VETO_FTP_FRACTION }

        val altitudeCount = points.count { it.altitude != null }
        if (altitudeCount == 0 || altitudeCount < points.size * CyclingConstants.POWER_DATA_COVERAGE_THRESHOLD) {
            return SpeedIq(
                false, 0.0, 0.0, 0.0, 0, referencePower.toInt(), massKg,
                referencePowerEstimated = estimated, topEvents = emptyList()
            )
        }

        val cumulativeM = HrDistanceSeriesBuilder.cumulativeMeters(points)
        val timed = BrakingDetector.detect(points, pauses, cumulativeM, massKg, referencePower, vetoPowerW)
        val slow = if (estimated) null else SlowSegmentDetector.detect(points, cumulativeM, massKg, timed.map { it.start..it.end })

        val merged = timed.map { Merged(it.event, it.start, it.end) }.toMutableList()
        for (segment in slow?.segments.orEmpty()) {
            // Only braking and standing events take slow segments; slow-only ones are never this close
            val target = merged.firstOrNull {
                (it.event.brakingEnergyJ > 0 || it.event.standingSec > 0) &&
                    BrakingDetector.seconds(it.end, segment.start) < CyclingConstants.SPEED_IQ_JOIN_GAP_SEC &&
                    BrakingDetector.seconds(segment.end, it.start) < CyclingConstants.SPEED_IQ_JOIN_GAP_SEC
            }
            if (target != null) {
                target.add(segment)
            } else {
                val at = points[segment.slowestIndex]
                merged += Merged(
                    SpeedIqEvent(
                        km = cumulativeM[segment.slowestIndex] / 1000.0, brakingEnergyJ = 0.0, penaltySec = 0.0,
                        peakKmh = 0.0, lowKmh = 0.0, lat = at.lat, lon = at.lon
                    ),
                    segment.start, segment.end
                ).also { it.add(segment) }
            }
        }
        val events = merged.map { it.toEvent() }

        return SpeedIq(
            hasElevation = true,
            brakingPenaltySec = events.sumOf { it.penaltySec },
            standingSec = events.sumOf { it.standingSec },
            standingInTimerSec = timed.sumOf { it.standingInTimerSec },
            eventCount = events.size,
            referencePowerW = referencePower.toInt(),
            systemMassKg = massKg,
            referencePowerEstimated = estimated,
            topEvents = events.sortedByDescending { it.lostSec }.take(topEventCount).sortedBy { it.km },
            slowSec = slow?.let { events.sumOf { it.slowSec } },
            speedFactor = slow?.speedFactor
        )
    }

    /**
     * P for a ride without power (#229): the median of [priorReferencePowersW], the per-ride P of
     * the last [CyclingConstants.SPEED_IQ_FALLBACK_RIDES] rides with power before it, else
     * [CyclingConstants.SPEED_IQ_FALLBACK_FTP_FRACTION] of the FTP as of the ride.
     */
    fun estimateReferencePower(priorReferencePowersW: List<Int>, ftp: Int): Double =
        priorReferencePowersW.filter { it > 0 }.map { it.toDouble() }.median()
            ?: (ftp * CyclingConstants.SPEED_IQ_FALLBACK_FTP_FRACTION)

    /** An event collecting the slow segments merged into it. */
    private class Merged(val event: SpeedIqEvent, val start: Instant, val end: Instant) {
        private var lostSec = 0.0
        private var distanceM = 0.0
        private var sec = 0.0
        private var expectedSec = 0.0

        fun add(segment: SlowSegmentDetector.SlowSegment) {
            lostSec += segment.lostSec
            distanceM += segment.distanceM
            sec += segment.sec
            expectedSec += segment.expectedSec
        }

        fun toEvent(): SpeedIqEvent = if (sec > 0) {
            event.copy(
                slowSec = lostSec,
                slowAvgKmh = distanceM / sec * CyclingConstants.MTS_PER_SEC_TO_KMH,
                slowExpectedKmh = if (expectedSec > 0) distanceM / expectedSec * CyclingConstants.MTS_PER_SEC_TO_KMH else null
            )
        } else event
    }
}
