package com.velometrics.app.util

import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.RepeatedInterval
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
    fun `groupIntervals - empty list returns empty list`() {
        val groups = MapOverlayUtils.groupIntervals(emptyList())
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `groupIntervals - archetype with assigned intervals is included`() {
        val matched = listOf(
            makeInterval(id = 1, durationNormalizedSec = 300, avgPower = 250),
            makeInterval(id = 2, durationNormalizedSec = 320, avgPower = 260)
        )
        val archetype = makeRepeatedInterval(id = 10, name = "Hill Climb", intervals = matched)
        val groups = MapOverlayUtils.groupIntervals(listOf(archetype))
        assertEquals(1, groups.size)
        assertEquals("Hill Climb", groups[0].name)
        assertEquals(2, groups[0].intervals.size)
    }

    @Test
    fun `groupIntervals - archetype with no assigned intervals is excluded`() {
        val emptyArchetype = makeRepeatedInterval(id = 10, name = "Unmatched", intervals = emptyList())
        val groups = MapOverlayUtils.groupIntervals(listOf(emptyArchetype))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `avgDurationNormalizedSec and avgPower compute correct averages`() {
        val matched = listOf(
            makeInterval(id = 1, durationNormalizedSec = 300, avgPower = 200),
            makeInterval(id = 2, durationNormalizedSec = 400, avgPower = 300)
        )
        val archetype = makeRepeatedInterval(id = 10, intervals = matched)
        assertEquals(350, MapOverlayUtils.avgDurationNormalizedSec(archetype))
        assertEquals(250, MapOverlayUtils.avgPower(archetype))
    }

    @Test
    fun `groupIntervals - mixed populated and empty archetypes filtered correctly`() {
        val populated = makeRepeatedInterval(id = 10, name = "Populated", intervals = listOf(makeInterval(id = 1)))
        val empty = makeRepeatedInterval(id = 11, name = "Empty", intervals = emptyList())
        val groups = MapOverlayUtils.groupIntervals(listOf(populated, empty))
        assertEquals(1, groups.size)
        assertEquals("Populated", groups[0].name)
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
