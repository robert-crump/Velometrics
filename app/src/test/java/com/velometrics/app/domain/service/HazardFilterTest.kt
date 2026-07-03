package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapEdge
import org.junit.Assert.*
import org.junit.Test

class HazardFilterTest {

    // --- Highway-tag fast-road prior ---

    @Test
    fun `motorway is rejected by tag prior`() {
        assertFalse(HazardFilter.shouldKeep(edge(highway = "motorway")))
    }

    @Test
    fun `motorway_link is rejected by tag prior`() {
        assertFalse(HazardFilter.shouldKeep(edge(highway = "motorway_link")))
    }

    @Test
    fun `trunk is rejected by tag prior`() {
        assertFalse(HazardFilter.shouldKeep(edge(highway = "trunk")))
    }

    @Test
    fun `trunk_link is rejected by tag prior`() {
        assertFalse(HazardFilter.shouldKeep(edge(highway = "trunk_link")))
    }

    @Test
    fun `residential is kept`() {
        assertTrue(HazardFilter.shouldKeep(edge(highway = "residential")))
    }

    // --- Helpers ---

    private fun edge(
        highway: String = "residential",
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
    )
}
