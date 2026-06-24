package com.velometrics.app.util

import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.service.RTreeSpatialIndex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class FindSurrogatesTest {

    private fun encodePolyline(points: List<LatLng>): String {
        val sb = StringBuilder()
        var prevLat = 0
        var prevLng = 0
        for (point in points) {
            val lat = Math.round(point.latitude * 1e5).toInt()
            val lng = Math.round(point.longitude * 1e5).toInt()
            encodeValue(lat - prevLat, sb)
            encodeValue(lng - prevLng, sb)
            prevLat = lat
            prevLng = lng
        }
        return sb.toString()
    }

    private fun encodeValue(value: Int, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            sb.append((((v and 0x1f) or 0x20) + 63).toChar())
            v = v shr 5
        }
        sb.append((v + 63).toChar())
    }

    private fun ewLine(lat: Double, lonStart: Double = 6.0800, lonEnd: Double = 6.0810) =
        listOf(LatLng(lat, lonStart), LatLng(lat, lonEnd))

    private fun nsLine(lon: Double, latStart: Double = 50.7800, latEnd: Double = 50.7810) =
        listOf(LatLng(latStart, lon), LatLng(latEnd, lon))

    private fun makeEdge(
        fromNode: Long,
        toNode: Long,
        geometry: List<LatLng>,
        isTraversed: Boolean = false,
        speedMean: Double? = null,
        powerMean: Double? = null
    ): MapEdge {
        val lengthM = GeoUtils.haversineDistance(
            geometry.first().latitude, geometry.first().longitude,
            geometry.last().latitude, geometry.last().longitude
        )
        return MapEdge(
            fromNode = fromNode, toNode = toNode,
            lengthM = lengthM, highway = "residential", name = null,
            isTraversed = isTraversed, geometryEncoded = encodePolyline(geometry),
            speedMedian = null, speedMean = speedMean, speedCount = null,
            speedP25 = null, speedP75 = null, speedP90 = null,
            powerMedian = null, powerMean = powerMean, powerCount = null,
            powerP25 = null, powerP75 = null, powerP90 = null,
            slopePercent = null, traversalCount = null, lastTraversal = null, timeOfDayDist = null
        )
    }

    private fun nodesFrom(vararg pairs: Pair<Long, LatLng>): Map<Long, MapNode> =
        pairs.associate { (id, ll) -> id to MapNode(id, ll.latitude, ll.longitude) }

    @Test
    fun `returns empty map when no uncovered edges provided`() = runTest {
        val geom = ewLine(50.7800)
        val traversed = makeEdge(0, 1, geom, isTraversed = true, speedMean = 25.0, powerMean = 200.0)
        val allEdges = listOf(traversed)
        val index = RTreeSpatialIndex()
        index.rebuildIndex(allEdges, nodesFrom(0L to geom.first(), 1L to geom.last()))

        val result = GpxAnalysisUtils.findSurrogates(emptyMap(), allEdges, index)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty map when no candidates within 20m`() = runTest {
        val traversedGeom = ewLine(50.7800)
        val uncoveredGeom = ewLine(50.7810) // ~111m away
        val traversed = makeEdge(0, 1, traversedGeom, isTraversed = true, speedMean = 25.0, powerMean = 200.0)
        val uncovered = makeEdge(2, 3, uncoveredGeom)
        val allEdges = listOf(traversed, uncovered)
        val index = RTreeSpatialIndex()
        index.rebuildIndex(allEdges, nodesFrom(
            0L to traversedGeom.first(), 1L to traversedGeom.last(),
            2L to uncoveredGeom.first(), 3L to uncoveredGeom.last()
        ))

        val result = GpxAnalysisUtils.findSurrogates(mapOf(1L to uncovered), allEdges, index)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `candidates with bearing difference greater than 45 degrees are excluded`() = runTest {
        val nsGeom = nsLine(6.0805) // north-south, bearing ~0°
        val uncoveredGeom = ewLine(50.78005) // east-west, bearing ~90°; midpoint near the NS edge
        val perpendicular = makeEdge(0, 1, nsGeom, isTraversed = true, speedMean = 25.0, powerMean = 200.0)
        val uncovered = makeEdge(2, 3, uncoveredGeom)
        val allEdges = listOf(perpendicular, uncovered)
        val index = RTreeSpatialIndex()
        index.rebuildIndex(allEdges, nodesFrom(
            0L to nsGeom.first(), 1L to nsGeom.last(),
            2L to uncoveredGeom.first(), 3L to uncoveredGeom.last()
        ))

        val result = GpxAnalysisUtils.findSurrogates(mapOf(1L to uncovered), allEdges, index)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `anti-parallel edges are excluded`() = runTest {
        // Anti-parallel: west-to-east uncovered vs east-to-west candidate (~180° bearing diff)
        val weGeom = listOf(LatLng(50.7800, 6.0810), LatLng(50.7800, 6.0800)) // west, bearing ~270°
        val uncoveredGeom = ewLine(50.78001) // east, bearing ~90°
        val antiParallel = makeEdge(0, 1, weGeom, isTraversed = true, speedMean = 25.0, powerMean = 200.0)
        val uncovered = makeEdge(2, 3, uncoveredGeom)
        val allEdges = listOf(antiParallel, uncovered)
        val index = RTreeSpatialIndex()
        index.rebuildIndex(allEdges, nodesFrom(
            0L to weGeom.first(), 1L to weGeom.last(),
            2L to uncoveredGeom.first(), 3L to uncoveredGeom.last()
        ))

        val result = GpxAnalysisUtils.findSurrogates(mapOf(1L to uncovered), allEdges, index)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `when multiple candidates qualify the nearest by distance is selected`() = runTest {
        // Two parallel east-west traversed edges at different distances from the uncovered edge
        val nearGeom = ewLine(50.78001) // ~1.1m from uncovered
        val farGeom = ewLine(50.78010) // ~10m from uncovered
        val uncoveredGeom = ewLine(50.78002)
        val near = makeEdge(0, 1, nearGeom, isTraversed = true, speedMean = 20.0, powerMean = 180.0)
        val far = makeEdge(2, 3, farGeom, isTraversed = true, speedMean = 30.0, powerMean = 250.0)
        val uncovered = makeEdge(4, 5, uncoveredGeom)
        val allEdges = listOf(near, far, uncovered)
        val index = RTreeSpatialIndex()
        index.rebuildIndex(allEdges, nodesFrom(
            0L to nearGeom.first(), 1L to nearGeom.last(),
            2L to farGeom.first(), 3L to farGeom.last(),
            4L to uncoveredGeom.first(), 5L to uncoveredGeom.last()
        ))

        val result = GpxAnalysisUtils.findSurrogates(mapOf(2L to uncovered), allEdges, index)
        assertEquals(1, result.size)
        val surrogate = result[2L]!!
        assertEquals(near.speedMean, surrogate.speedMean)
        assertEquals(near.powerMean, surrogate.powerMean)
    }

    @Test
    fun `candidates without speedMean or powerMean are excluded`() = runTest {
        val candidateGeom = ewLine(50.7800)
        val uncoveredGeom = ewLine(50.78001)
        // Traversed but missing metadata
        val noMetadata = makeEdge(0, 1, candidateGeom, isTraversed = true, speedMean = null, powerMean = null)
        val uncovered = makeEdge(2, 3, uncoveredGeom)
        val allEdges = listOf(noMetadata, uncovered)
        val index = RTreeSpatialIndex()
        index.rebuildIndex(allEdges, nodesFrom(
            0L to candidateGeom.first(), 1L to candidateGeom.last(),
            2L to uncoveredGeom.first(), 3L to uncoveredGeom.last()
        ))

        val result = GpxAnalysisUtils.findSurrogates(mapOf(1L to uncovered), allEdges, index)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `candidates that are not traversed are excluded`() = runTest {
        val candidateGeom = ewLine(50.7800)
        val uncoveredGeom = ewLine(50.78001)
        val notTraversed = makeEdge(0, 1, candidateGeom, isTraversed = false)
        val uncovered = makeEdge(2, 3, uncoveredGeom)
        val allEdges = listOf(notTraversed, uncovered)
        val index = RTreeSpatialIndex()
        index.rebuildIndex(allEdges, nodesFrom(
            0L to candidateGeom.first(), 1L to candidateGeom.last(),
            2L to uncoveredGeom.first(), 3L to uncoveredGeom.last()
        ))

        val result = GpxAnalysisUtils.findSurrogates(mapOf(1L to uncovered), allEdges, index)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parallel traversed edge within range is selected as surrogate`() = runTest {
        val surrogateGeom = ewLine(50.7800)
        val uncoveredGeom = ewLine(50.78001) // ~1.1m north, same bearing
        val surrogate = makeEdge(0, 1, surrogateGeom, isTraversed = true, speedMean = 25.0, powerMean = 200.0)
        val uncovered = makeEdge(2, 3, uncoveredGeom)
        val allEdges = listOf(surrogate, uncovered)
        val index = RTreeSpatialIndex()
        index.rebuildIndex(allEdges, nodesFrom(
            0L to surrogateGeom.first(), 1L to surrogateGeom.last(),
            2L to uncoveredGeom.first(), 3L to uncoveredGeom.last()
        ))

        val result = GpxAnalysisUtils.findSurrogates(mapOf(1L to uncovered), allEdges, index)
        assertEquals(1, result.size)
        val found = result[1L]!!
        assertEquals(25.0, found.speedMean!!, 0.0)
        assertEquals(200.0, found.powerMean!!, 0.0)
    }
}
