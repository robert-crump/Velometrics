package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteShapeMetricsTest {

    private fun edge(fromNode: Long, toNode: Long, lengthM: Double) = MapEdge(
        fromNode = fromNode, toNode = toNode, lengthM = lengthM,
        highway = "residential", name = null,
        isTraversed = false, geometryEncoded = "",
        speedMedian = null, speedMean = null, speedCount = null,
        speedP25 = null, speedP75 = null, speedP90 = null,
        powerMedian = null, powerMean = null, powerCount = null,
        powerP25 = null, powerP75 = null, powerP90 = null,
        slopePercent = null, traversalCount = null, lastTraversal = null, timeOfDayDist = null,
    )

    @Test
    fun `empty route yields zeroed report`() {
        val report = RouteShapeMetrics.evaluate(emptyList())
        assertEquals(0.0, report.totalLengthM, 0.0)
        assertEquals(0.0, report.repeatFraction, 0.0)
        assertEquals(0.0, report.compactness, 0.0)
    }

    @Test
    fun `square loop has zero repeat and near-square compactness`() {
        // 10km sides around Aachen latitude.
        val latDelta = 10_000.0 / 111_320.0
        val lonDelta = 10_000.0 / (111_320.0 * Math.cos(Math.toRadians(50.78)))
        val nodes = mapOf(
            1L to (50.78 to 6.07),
            2L to (50.78 + latDelta to 6.07),
            3L to (50.78 + latDelta to 6.07 + lonDelta),
            4L to (50.78 to 6.07 + lonDelta),
        )
        val route = listOf(edge(1, 2, 10_000.0), edge(2, 3, 10_000.0), edge(3, 4, 10_000.0), edge(4, 1, 10_000.0))

        val report = RouteShapeMetrics.evaluate(route, nodes)

        assertEquals(40_000.0, report.totalLengthM, 0.0)
        assertEquals(0.0, report.repeatFraction, 0.0)
        assertEquals(0.0, report.rawRepeatFraction, 0.0)
        // Isoperimetric quotient of a square is pi/4 ~ 0.785; allow projection slack.
        assertTrue("compactness ${report.compactness} should be near pi/4", report.compactness in 0.74..0.83)
    }

    @Test
    fun `pure out-and-back repeats half its length and encloses nothing`() {
        val latDelta = 10_000.0 / 111_320.0
        val nodes = mapOf(
            1L to (50.78 to 6.07),
            2L to (50.78 + latDelta to 6.07),
            3L to (50.78 + 2 * latDelta to 6.07),
        )
        val route = listOf(edge(1, 2, 10_000.0), edge(2, 3, 10_000.0), edge(3, 2, 10_000.0), edge(2, 1, 10_000.0))

        val report = RouteShapeMetrics.evaluate(route, nodes)

        // Return traversals are direction-agnostic repeats; both roads have a traversal outside
        // the 8km stem windows, so nothing is exempt.
        assertEquals(0.5, report.repeatFraction, 1e-9)
        assertEquals(0.5, report.rawRepeatFraction, 1e-9)
        assertEquals(2, report.repeatedRoadCount)
        assertTrue("compactness ${report.compactness} should be ~0", report.compactness < 0.01)
    }

    @Test
    fun `road repeated only within the head and tail stem windows is exempt`() {
        // 20km route: road 1-2 ridden at [0,2]km (head window) and again at [18,20]km (tail window).
        val route = listOf(
            edge(1, 2, 2_000.0),
            edge(2, 3, 4_000.0),
            edge(3, 4, 4_000.0),
            edge(4, 5, 4_000.0),
            edge(5, 6, 4_000.0),
            edge(2, 1, 2_000.0),
        )

        val report = RouteShapeMetrics.evaluate(route)

        assertEquals(0.0, report.repeatFraction, 0.0)
        assertEquals(0, report.repeatedRoadCount)
        // The raw (no-exemption) figure still surfaces the stem for diagnosis.
        assertEquals(2_000.0 / 20_000.0, report.rawRepeatFraction, 1e-9)
    }

    @Test
    fun `repetition counts when any traversal falls outside the stem windows`() {
        // 20km route, windows are [0,8] and [12,20]: road 1-2 ridden at [0,2] and again at
        // [9,11] — the second traversal is mid-route, so the repetition counts.
        val route = listOf(
            edge(1, 2, 2_000.0),
            edge(2, 3, 7_000.0),
            edge(2, 1, 2_000.0),
            edge(3, 4, 9_000.0),
        )

        val report = RouteShapeMetrics.evaluate(route)

        assertEquals(2_000.0 / 20_000.0, report.repeatFraction, 1e-9)
        assertEquals(1, report.repeatedRoadCount)
    }
}
