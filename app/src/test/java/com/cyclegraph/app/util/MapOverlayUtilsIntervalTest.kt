package com.cyclegraph.app.util

import com.cyclegraph.app.domain.model.IntervalPrototypeRoute
import com.cyclegraph.app.domain.model.IntervalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MapOverlayUtilsIntervalTest {

    // --- normalizedDurationToColor ---

    @Test
    fun `normalizedDurationToColor - at min 120s returns lightest color`() {
        assertEquals("#FFFFB2", MapOverlayUtils.normalizedDurationToColor(120))
    }

    @Test
    fun `normalizedDurationToColor - at max 480s returns darkest color`() {
        assertEquals("#BD0026", MapOverlayUtils.normalizedDurationToColor(480))
    }

    @Test
    fun `normalizedDurationToColor - at midpoint 300s returns orange`() {
        assertEquals("#FD8D3C", MapOverlayUtils.normalizedDurationToColor(300))
    }

    @Test
    fun `normalizedDurationToColor - below min clamps to lightest`() {
        assertEquals("#FFFFB2", MapOverlayUtils.normalizedDurationToColor(60))
    }

    @Test
    fun `normalizedDurationToColor - above max clamps to darkest`() {
        assertEquals("#BD0026", MapOverlayUtils.normalizedDurationToColor(600))
    }

    @Test
    fun `normalizedDurationToColor - interpolates between stops`() {
        // 210s is exactly at the second stop
        val color = MapOverlayUtils.normalizedDurationToColor(210)
        assertEquals("#FECC5C", color)

        // 255s is between stops 1 (210s) and 2 (300s) — halfway = (210+300)/2 = 255
        val midColor = MapOverlayUtils.normalizedDurationToColor(255)
        // Should be between #FECC5C and #FD8D3C — an intermediate orange
        // Verify it starts with # and has 7 chars
        assertTrue(midColor.startsWith("#"))
        assertEquals(7, midColor.length)
        // R should be between 0xFE (254) and 0xFD (253) — very close
        val r = midColor.substring(1, 3).toInt(16)
        assertTrue(r in 253..254)
    }

    // --- groupIntervals ---

    @Test
    fun `groupIntervals - empty list returns empty pair`() {
        val (groups, ungrouped) = MapOverlayUtils.groupIntervals(emptyList(), emptyList())
        assertTrue(groups.isEmpty())
        assertTrue(ungrouped.isEmpty())
    }

    @Test
    fun `groupIntervals - ungrouped intervals returned in second`() {
        val intervals = listOf(
            makeInterval(id = 1, prototypeRouteId = null),
            makeInterval(id = 2, prototypeRouteId = null)
        )
        val (groups, ungrouped) = MapOverlayUtils.groupIntervals(intervals, emptyList())
        assertTrue(groups.isEmpty())
        assertEquals(2, ungrouped.size)
    }

    @Test
    fun `groupIntervals - grouped intervals create IntervalGroup`() {
        val proto = makePrototype(id = 10, name = "Hill Climb")
        val intervals = listOf(
            makeInterval(id = 1, prototypeRouteId = 10, durationNormalizedSec = 300, avgPower = 250),
            makeInterval(id = 2, prototypeRouteId = 10, durationNormalizedSec = 320, avgPower = 260)
        )
        val (groups, ungrouped) = MapOverlayUtils.groupIntervals(intervals, listOf(proto))
        assertEquals(1, groups.size)
        assertEquals("Hill Climb", groups[0].prototypeRoute.name)
        assertEquals(2, groups[0].intervals.size)
        assertTrue(ungrouped.isEmpty())
    }

    @Test
    fun `groupIntervals - computes correct average duration and power`() {
        val proto = makePrototype(id = 10)
        val intervals = listOf(
            makeInterval(id = 1, prototypeRouteId = 10, durationNormalizedSec = 300, avgPower = 200),
            makeInterval(id = 2, prototypeRouteId = 10, durationNormalizedSec = 400, avgPower = 300)
        )
        val (groups, _) = MapOverlayUtils.groupIntervals(intervals, listOf(proto))
        assertEquals(350, groups[0].avgDurationNormalizedSec)
        assertEquals(250, groups[0].avgPower)
    }

    @Test
    fun `groupIntervals - mixed grouped and ungrouped partitioned correctly`() {
        val proto = makePrototype(id = 10)
        val intervals = listOf(
            makeInterval(id = 1, prototypeRouteId = 10),
            makeInterval(id = 2, prototypeRouteId = null),
            makeInterval(id = 3, prototypeRouteId = 10),
            makeInterval(id = 4, prototypeRouteId = null)
        )
        val (groups, ungrouped) = MapOverlayUtils.groupIntervals(intervals, listOf(proto))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].intervals.size)
        assertEquals(2, ungrouped.size)
    }

    // --- formatDurationMinSec ---

    @Test
    fun `formatDurationMinSec - 270 sec returns 4 colon 30`() {
        assertEquals("4:30", MapOverlayUtils.formatDurationMinSec(270))
    }

    @Test
    fun `formatDurationMinSec - 120 sec returns 2 colon 00`() {
        assertEquals("2:00", MapOverlayUtils.formatDurationMinSec(120))
    }

    @Test
    fun `formatDurationMinSec - 65 sec returns 1 colon 05`() {
        assertEquals("1:05", MapOverlayUtils.formatDurationMinSec(65))
    }

    // --- helpers ---

    private fun makeInterval(
        id: Long = 0,
        prototypeRouteId: Long? = null,
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
        gpsTrack = "[[50.78,6.07],[50.79,6.08]]",
        prototypeRouteId = prototypeRouteId
    )

    private fun makePrototype(
        id: Long = 0,
        name: String = "Test Route"
    ) = IntervalPrototypeRoute(
        id = id,
        name = name,
        startLat = 50.78,
        startLon = 6.07,
        endLat = 50.79,
        endLon = 6.08,
        distanceM = 1500.0,
        avgGpsTrack = "[[50.78,6.07],[50.785,6.075],[50.79,6.08]]"
    )
}
