package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.util.CyclingConstants.FLOW_SEGMENT_MIN_COUNT

/** Domain aggregation over repeated intervals and map edges that the map overlays display. */
object OverlayAggregation {

    /** [RepeatedInterval] archetypes that have at least one matched raw interval assigned. */
    fun groupIntervals(repeatedIntervals: List<RepeatedInterval>): List<RepeatedInterval> =
        repeatedIntervals.filter { it.intervals.isNotEmpty() }

    fun avgDurationNormalizedSec(repeatedInterval: RepeatedInterval): Int =
        repeatedInterval.intervals.map { it.durationNormalizedSec }.average().toInt()

    fun avgPower(repeatedInterval: RepeatedInterval): Int =
        repeatedInterval.intervals.map { it.avgPower }.average().toInt()

    /**
     * An edge qualifies as a flow segment when the sum of its pedal-flow and gravity-flow run
     * counts meets [FLOW_SEGMENT_MIN_COUNT]. Null/missing counts are treated as 0.
     */
    fun isFlowSegment(edge: MapEdge): Boolean =
        ((edge.pedalFlowCount ?: 0) + (edge.gravityFlowCount ?: 0)) >= FLOW_SEGMENT_MIN_COUNT
}
