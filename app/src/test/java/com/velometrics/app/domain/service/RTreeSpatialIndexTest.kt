package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.util.GeoUtils
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RTreeSpatialIndexTest {

    private fun edge(from: MapNode, to: MapNode): MapEdge {
        val lengthM = GeoUtils.haversineDistance(from.lat, from.lon, to.lat, to.lon)
        return MapEdge(
            fromNode = from.id, toNode = to.id,
            lengthM = lengthM, highway = "residential", name = null,
            isTraversed = false, geometryEncoded = "",
            speedMedian = null, speedMean = null, speedCount = null,
            speedP25 = null, speedP75 = null, speedP90 = null,
            powerMedian = null, powerMean = null, powerCount = null,
            powerP25 = null, powerP75 = null, powerP90 = null,
            slopePercent = 0.0, traversalCount = 0, lastTraversal = null, timeOfDayDist = null
        )
    }

    @Test
    fun `a point on a long edge ranks above a shorter edge with a closer bbox center`() = runTest {
        // Long edge: a horizontal segment the query point lies directly on.
        val a0 = MapNode(0, 50.7800, 6.0800)
        val a1 = MapNode(1, 50.7800, 6.1000)
        val longEdge = edge(a0, a1)

        // Short edge: its bbox center is much closer to the query point than the long edge's
        // bbox center (50.7800, 6.0900), but the point is not actually near its segment.
        val b0 = MapNode(2, 50.7807, 6.0812)
        val b1 = MapNode(3, 50.7809, 6.0812)
        val shortEdge = edge(b0, b1)

        val index = RTreeSpatialIndex()
        index.rebuildIndex(listOf(longEdge, shortEdge), mapOf(0L to a0, 1L to a1, 2L to b0, 3L to b1))

        // Point lies exactly on the long edge's line.
        val candidates = index.queryEdgesNear(50.7800, 6.0810, radiusM = 700.0)

        assertTrue("expected both edges within radius", candidates.size == 2)
        assertEquals(0L, candidates.first().edgeKey)
        assertEquals(0.0, candidates.first().distanceM, 0.5)
    }

    @Test
    fun `candidate bearing reflects the nearest segment's direction`() = runTest {
        val n0 = MapNode(0, 50.7800, 6.0800)
        val n1 = MapNode(1, 50.7810, 6.0800) // due north of n0
        val northEdge = edge(n0, n1)

        val index = RTreeSpatialIndex()
        index.rebuildIndex(listOf(northEdge), mapOf(0L to n0, 1L to n1))

        val candidates = index.queryEdgesNear(50.7805, 6.0800, radiusM = 50.0)

        assertEquals(1, candidates.size)
        assertEquals(0.0, candidates.first().bearingDeg, 1.0)
    }
}
