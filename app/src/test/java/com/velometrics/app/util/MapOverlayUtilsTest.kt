package com.velometrics.app.util

import com.velometrics.app.domain.model.MapEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapOverlayUtilsTest {

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

    @Test
    fun `speedToColor - 0 kmh returns black`() {
        assertEquals("#000000", MapOverlayUtils.speedToColor(0.0))
    }

    @Test
    fun `speedToColor - 15 kmh returns yellow`() {
        assertEquals("#FFEDA0", MapOverlayUtils.speedToColor(15.0))
    }

    @Test
    fun `speedToColor - 22 kmh returns gold`() {
        assertEquals("#FEB24C", MapOverlayUtils.speedToColor(22.0))
    }

    @Test
    fun `speedToColor - 27 kmh returns dark orange`() {
        assertEquals("#FD8D3C", MapOverlayUtils.speedToColor(27.0))
    }

    @Test
    fun `speedToColor - 35 kmh returns red`() {
        assertEquals("#F03B20", MapOverlayUtils.speedToColor(35.0))
    }

    @Test
    fun `speedToColor - 45 kmh returns dark red`() {
        assertEquals("#BD0026", MapOverlayUtils.speedToColor(45.0))
    }

    @Test
    fun `speedToColor - 55 kmh returns light blue`() {
        assertEquals("#6BAED6", MapOverlayUtils.speedToColor(55.0))
    }

    // --- isFlowSegment ---

    @Test
    fun `isFlowSegment - sum below threshold is false`() {
        assertFalse(MapOverlayUtils.isFlowSegment(edgeOf(pedalFlowCount = 1, gravityFlowCount = 1)))
    }

    @Test
    fun `isFlowSegment - sum at threshold is true`() {
        assertTrue(MapOverlayUtils.isFlowSegment(edgeOf(pedalFlowCount = 2, gravityFlowCount = 1)))
    }

    @Test
    fun `isFlowSegment - sum above threshold is true`() {
        assertTrue(MapOverlayUtils.isFlowSegment(edgeOf(pedalFlowCount = 3, gravityFlowCount = 2)))
    }

    @Test
    fun `isFlowSegment - null counts are false`() {
        assertFalse(MapOverlayUtils.isFlowSegment(edgeOf(pedalFlowCount = null, gravityFlowCount = null)))
    }

    @Test
    fun `isFlowSegment - one null count uses the other`() {
        assertTrue(MapOverlayUtils.isFlowSegment(edgeOf(pedalFlowCount = 3, gravityFlowCount = null)))
    }
}
