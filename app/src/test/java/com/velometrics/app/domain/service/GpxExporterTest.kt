package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.util.PolylineDecoder
import org.junit.Assert.*
import org.junit.Test

class GpxExporterTest {

    private val exporter = GpxExporter()

    /**
     * Extracts `<trkpt lat="..." lon="..."/>` coordinates directly via regex, rather than via
     * `GpxParser` (removed with the .gpx-analysis flow, #152) — keeps this test self-contained
     * to [GpxExporter]'s own output instead of depending on a parser outside the system under
     * test.
     */
    private fun extractTrkPts(gpx: String): List<Pair<Double, Double>> =
        Regex("""<trkpt lat="([^"]+)" lon="([^"]+)"/>""").findAll(gpx)
            .map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
            .toList()

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

    @Test
    fun `round-trip export then parse preserves track points`() {
        val edges = listOf(makeEdge())
        val routeName = "42k_Velometrics_260627"
        val gpx = exporter.toGpxString(edges, routeName)

        val expectedPoints = PolylineDecoder.decode(edges[0].geometryEncoded)
        val trkPts = extractTrkPts(gpx)

        assertEquals("Point count should match decoded geometry", expectedPoints.size, trkPts.size)
        for (i in expectedPoints.indices) {
            assertEquals(expectedPoints[i].latitude, trkPts[i].first, 1e-5)
            assertEquals(expectedPoints[i].longitude, trkPts[i].second, 1e-5)
        }
    }

    @Test
    fun `raw track produces trkpt elements for each coordinate pair`() {
        val coords = listOf(listOf(51.5, -0.1), listOf(51.51, -0.11))
        val gpx = exporter.toGpxStringFromTrack(coords, "Repeated Route")

        val trkPts = extractTrkPts(gpx)
        assertEquals(2, trkPts.size)
        assertEquals(51.5, trkPts[0].first, 1e-9)
        assertEquals(-0.1, trkPts[0].second, 1e-9)
        assertEquals(51.51, trkPts[1].first, 1e-9)
        assertEquals(-0.11, trkPts[1].second, 1e-9)
    }

    @Test
    fun `raw track ignores malformed coordinate pairs`() {
        val coords = listOf(listOf(51.5, -0.1), listOf(51.51))
        val gpx = exporter.toGpxStringFromTrack(coords, "Repeated Route")

        val trkPts = extractTrkPts(gpx)
        assertEquals(1, trkPts.size)
    }

    @Test
    fun `empty raw track produces valid but empty GPX`() {
        val gpx = exporter.toGpxStringFromTrack(emptyList(), "Empty Route")

        assertTrue(gpx.contains("<gpx version=\"1.1\""))
        assertFalse(gpx.contains("<trkpt"))
    }
}
