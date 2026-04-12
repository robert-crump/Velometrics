package com.cyclegraph.app.domain.service

import com.cyclegraph.app.domain.model.MapEdge
import org.junit.Assert.*
import org.junit.Test

class GpxExporterTest {

    private val exporter = GpxExporter()

    private fun makeEdge(geometryEncoded: String = "_p~iF~ps|U_ulLnnqC_mqNvxq`@") = MapEdge(
        fromNode = 1L,
        toNode = 2L,
        lengthM = 100.0,
        highway = "cycleway",
        name = null,
        isTraversed = true,
        geometryEncoded = geometryEncoded,
        speedMedian = 25.0,
        speedMean = 25.0,
        speedCount = 10,
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
        traversalCount = 5,
        lastTraversal = "2025-01-01",
        timeOfDayDist = null,
        avgStopCount = null
    )

    @Test
    fun `valid GPX 1-1 XML output with correct structure`() {
        val edges = listOf(makeEdge())
        val gpx = exporter.toGpxString(edges, "Test Route")

        assertTrue(gpx.contains("<?xml version=\"1.0\""))
        assertTrue(gpx.contains("<gpx version=\"1.1\""))
        assertTrue(gpx.contains("<trk>"))
        assertTrue(gpx.contains("<trkseg>"))
        assertTrue(gpx.contains("</trkseg>"))
        assertTrue(gpx.contains("</trk>"))
        assertTrue(gpx.contains("</gpx>"))
    }

    @Test
    fun `empty edge list produces valid but empty GPX`() {
        val gpx = exporter.toGpxString(emptyList(), "Empty Route")

        assertTrue(gpx.contains("<gpx version=\"1.1\""))
        assertTrue(gpx.contains("<trkseg>"))
        assertTrue(gpx.contains("</trkseg>"))
        assertFalse(gpx.contains("<trkpt"))
    }

    @Test
    fun `metadata contains time element`() {
        val gpx = exporter.toGpxString(emptyList(), "Test")
        assertTrue(gpx.contains("<metadata>"))
        assertTrue(gpx.contains("<time>"))
        assertTrue(gpx.contains("</metadata>"))
    }

    @Test
    fun `route name is included and XML-escaped`() {
        val gpx = exporter.toGpxString(emptyList(), "Route <test> & 40km")
        assertTrue(gpx.contains("Route &lt;test&gt; &amp; 40km"))
    }

    @Test
    fun `trkpt elements included for non-empty edges`() {
        val edges = listOf(makeEdge())
        val gpx = exporter.toGpxString(edges, "Test")
        assertTrue("GPX should contain at least one trkpt", gpx.contains("<trkpt"))
    }
}
