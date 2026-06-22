package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapEdge
import org.junit.Assert.*
import org.junit.Test

class HazardFilterTest {

    // --- Type 2: measured hazard score threshold ---

    @Test
    fun `edge with hazardScore above threshold is rejected`() {
        val edge = edge(hazardScore = 0.9)
        assertFalse(HazardFilter.shouldKeep(edge))
    }

    @Test
    fun `edge with hazardScore below threshold is kept`() {
        val edge = edge(hazardScore = 0.3)
        assertTrue(HazardFilter.shouldKeep(edge))
    }

    @Test
    fun `edge with hazardScore exactly at threshold is rejected`() {
        val edge = edge(hazardScore = 0.7)
        assertFalse(HazardFilter.shouldKeep(edge))
    }

    @Test
    fun `custom threshold is respected`() {
        val edge = edge(hazardScore = 0.5)
        val strict = HazardConfig(hazardScoreThreshold = 0.4)
        val lenient = HazardConfig(hazardScoreThreshold = 0.6)

        assertFalse(HazardFilter.shouldKeep(edge, strict))
        assertTrue(HazardFilter.shouldKeep(edge, lenient))
    }

    // --- Type 2: highway-tag prior for un-measured edges ---

    @Test
    fun `motorway with no measured hazard is rejected by tag prior`() {
        assertFalse(HazardFilter.shouldKeep(edge(highway = "motorway", hazardScore = null)))
    }

    @Test
    fun `motorway_link with no measured hazard is rejected by tag prior`() {
        assertFalse(HazardFilter.shouldKeep(edge(highway = "motorway_link", hazardScore = null)))
    }

    @Test
    fun `trunk with no measured hazard is rejected by tag prior`() {
        assertFalse(HazardFilter.shouldKeep(edge(highway = "trunk", hazardScore = null)))
    }

    @Test
    fun `trunk_link with no measured hazard is rejected by tag prior`() {
        assertFalse(HazardFilter.shouldKeep(edge(highway = "trunk_link", hazardScore = null)))
    }

    @Test
    fun `residential with no measured hazard is kept`() {
        assertTrue(HazardFilter.shouldKeep(edge(highway = "residential", hazardScore = null)))
    }

    @Test
    fun `measured low hazardScore on motorway overrides tag prior`() {
        val edge = edge(highway = "motorway", hazardScore = 0.1)
        assertTrue(HazardFilter.shouldKeep(edge))
    }

    // --- Type 1: race-to-a-stop (per-edge stop proxy) ---

    @Test
    fun `high stopPenalty is rejected as race-to-a-stop hazard`() {
        val edge = edge(stopPenalty = 4.0)
        assertFalse(HazardFilter.shouldKeep(edge))
    }

    @Test
    fun `stopPenalty below threshold is kept`() {
        val edge = edge(stopPenalty = 1.5)
        assertTrue(HazardFilter.shouldKeep(edge))
    }

    @Test
    fun `stopPenalty exactly at threshold is rejected`() {
        val edge = edge(stopPenalty = 3.0)
        assertFalse(HazardFilter.shouldKeep(edge))
    }

    @Test
    fun `null stopPenalty is kept`() {
        val edge = edge(stopPenalty = null)
        assertTrue(HazardFilter.shouldKeep(edge))
    }

    @Test
    fun `custom raceToStop threshold is respected`() {
        val edge = edge(stopPenalty = 2.0)
        val strict = HazardConfig(raceToStopThreshold = 1.5)
        val lenient = HazardConfig(raceToStopThreshold = 2.5)

        assertFalse(HazardFilter.shouldKeep(edge, strict))
        assertTrue(HazardFilter.shouldKeep(edge, lenient))
    }

    // --- Combined / edge cases ---

    @Test
    fun `safe edge with null scores and residential highway is kept`() {
        val edge = edge(highway = "residential", hazardScore = null, stopPenalty = null)
        assertTrue(HazardFilter.shouldKeep(edge))
    }

    @Test
    fun `edge rejected by hazardScore even if stopPenalty is safe`() {
        val edge = edge(hazardScore = 0.9, stopPenalty = 0.5)
        assertFalse(HazardFilter.shouldKeep(edge))
    }

    @Test
    fun `edge rejected by stopPenalty even if hazardScore is safe`() {
        val edge = edge(hazardScore = 0.2, stopPenalty = 5.0)
        assertFalse(HazardFilter.shouldKeep(edge))
    }

    // --- Helpers ---

    private fun edge(
        highway: String = "residential",
        hazardScore: Double? = null,
        stopPenalty: Double? = null,
    ) = MapEdge(
        fromNode = 1L,
        toNode = 2L,
        lengthM = 100.0,
        highway = highway,
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
        hazardScore = hazardScore,
        stopPenalty = stopPenalty,
    )
}
