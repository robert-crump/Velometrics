package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.RepeatedInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OverlayAggregationTest {

    // --- groupIntervals ---

    @Test
    fun `groupIntervals - empty list returns empty list`() {
        val groups = OverlayAggregation.groupIntervals(emptyList())
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `groupIntervals - archetype with assigned intervals is included`() {
        val matched = listOf(
            makeInterval(id = 1, durationNormalizedSec = 300, avgPower = 250),
            makeInterval(id = 2, durationNormalizedSec = 320, avgPower = 260)
        )
        val archetype = makeRepeatedInterval(id = 10, name = "Hill Climb", intervals = matched)
        val groups = OverlayAggregation.groupIntervals(listOf(archetype))
        assertEquals(1, groups.size)
        assertEquals("Hill Climb", groups[0].name)
        assertEquals(2, groups[0].intervals.size)
    }

    @Test
    fun `groupIntervals - archetype with no assigned intervals is excluded`() {
        val emptyArchetype = makeRepeatedInterval(id = 10, name = "Unmatched", intervals = emptyList())
        val groups = OverlayAggregation.groupIntervals(listOf(emptyArchetype))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `avgDurationNormalizedSec and avgPower compute correct averages`() {
        val matched = listOf(
            makeInterval(id = 1, durationNormalizedSec = 300, avgPower = 200),
            makeInterval(id = 2, durationNormalizedSec = 400, avgPower = 300)
        )
        val archetype = makeRepeatedInterval(id = 10, intervals = matched)
        assertEquals(350, OverlayAggregation.avgDurationNormalizedSec(archetype))
        assertEquals(250, OverlayAggregation.avgPower(archetype))
    }

    @Test
    fun `groupIntervals - mixed populated and empty archetypes filtered correctly`() {
        val populated = makeRepeatedInterval(id = 10, name = "Populated", intervals = listOf(makeInterval(id = 1)))
        val empty = makeRepeatedInterval(id = 11, name = "Empty", intervals = emptyList())
        val groups = OverlayAggregation.groupIntervals(listOf(populated, empty))
        assertEquals(1, groups.size)
        assertEquals("Populated", groups[0].name)
    }

    // --- isFlowSegment ---

    @Test
    fun `isFlowSegment - sum below threshold is false`() {
        assertFalse(OverlayAggregation.isFlowSegment(edgeOf(pedalFlowCount = 1, gravityFlowCount = 1)))
    }

    @Test
    fun `isFlowSegment - sum at threshold is true`() {
        assertTrue(OverlayAggregation.isFlowSegment(edgeOf(pedalFlowCount = 2, gravityFlowCount = 1)))
    }

    @Test
    fun `isFlowSegment - sum above threshold is true`() {
        assertTrue(OverlayAggregation.isFlowSegment(edgeOf(pedalFlowCount = 3, gravityFlowCount = 2)))
    }

    @Test
    fun `isFlowSegment - null counts are false`() {
        assertFalse(OverlayAggregation.isFlowSegment(edgeOf(pedalFlowCount = null, gravityFlowCount = null)))
    }

    @Test
    fun `isFlowSegment - one null count uses the other`() {
        assertTrue(OverlayAggregation.isFlowSegment(edgeOf(pedalFlowCount = 3, gravityFlowCount = null)))
    }

    private fun edgeOf(pedalFlowCount: Int?, gravityFlowCount: Int?) = MapEdge(
        fromNode = 1L,
        toNode = 2L,
        lengthM = 100.0,
        highway = "cycleway",
        name = null,
        isTraversed = true,
        geometryEncoded = "",
        speedMedian = null,
        speedMean = null,
        speedCount = null,
        speedP25 = null,
        speedP75 = null,
        speedP90 = null,
        powerMedian = null,
        powerMean = null,
        powerCount = null,
        powerP25 = null,
        powerP75 = null,
        powerP90 = null,
        slopePercent = null,
        traversalCount = null,
        lastTraversal = null,
        timeOfDayDist = null,
        pedalFlowCount = pedalFlowCount,
        gravityFlowCount = gravityFlowCount,
    )

    // --- helpers ---

    private fun makeInterval(
        id: Long = 0,
        durationNormalizedSec: Int = 300,
        avgPower: Int = 250
    ) = IntervalSession(
        id = id,
        cyclingSessionId = 1,
        startTimestamp = Instant.parse("2025-01-01T10:00:00Z"),
        durationSec = durationNormalizedSec,
        durationNormalizedSec = durationNormalizedSec,
        distanceM = 1500.0,
        avgPower = avgPower,
        avgSpeedKmh = 30.0,
        avgSpeedNormalizedKmh = 30.0,
        direction = "N",
        startLat = 50.78,
        startLon = 6.07,
        endLat = 50.79,
        endLon = 6.08,
        gpsTrack = "[[50.78,6.07],[50.79,6.08]]"
    )

    private fun makeRepeatedInterval(
        id: Long = 0,
        name: String = "Test Route",
        intervals: List<IntervalSession> = emptyList()
    ) = RepeatedInterval(
        id = id,
        name = name,
        intervals = intervals,
        edges = emptyList(),
        startLat = 50.78,
        startLon = 6.07,
        endLat = 50.79,
        endLon = 6.08,
        distanceM = 1500.0
    )
}
