package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.GraphMetadata
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.model.Poi
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.util.GeoUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MapMatcherTest {

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
            slopePercent = 0.0, traversalCount = 0, lastTraversal = null, timeOfDayDist = null,
            stopCount = null, avgStopDurationS = null, stopProbability = null, estimatedStopTimeS = null
        )
    }

    private fun fakeRepository(edges: List<MapEdge>, nodes: List<MapNode>): MapGraphRepository =
        object : MapGraphRepository {
            override fun getAllEdges(): Flow<List<MapEdge>> = flowOf(edges)
            override fun getAllNodes(): Flow<List<MapNode>> = flowOf(nodes)
            override suspend fun getEdgesNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) = edges
            override suspend fun getNodesNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) = nodes
            override fun getTraversedEdges(): Flow<List<MapEdge>> = flowOf(edges.filter { it.isTraversed })
            override fun getUntraversedEdges(): Flow<List<MapEdge>> = flowOf(edges.filterNot { it.isTraversed })
            override fun getAllPois(): Flow<List<Poi>> = flowOf(emptyList())
            override fun getMetadata(): GraphMetadata? = null
            override suspend fun loadGraph(nodes: List<MapNode>, edges: List<MapEdge>, metadata: GraphMetadata) {}
            override suspend fun loadPois(pois: List<Poi>) {}
            override fun isLoaded(): Boolean = true
        }

    /** A point at the given latitude, fixed longitude — used to build tracks along a north-bound chain. */
    private fun pt(lat: Double): List<Double> = listOf(lat, 6.0800)

    @Test
    fun `clean track snaps to a fully connected edge sequence`() = runTest {
        val n0 = MapNode(0, 50.7800, 6.0800)
        val n1 = MapNode(1, 50.7810, 6.0800)
        val n2 = MapNode(2, 50.7820, 6.0800)
        val n3 = MapNode(3, 50.7830, 6.0800)
        val edge0 = edge(n0, n1)
        val edge1 = edge(n1, n2)
        val edge2 = edge(n2, n3)

        val repository = fakeRepository(listOf(edge0, edge1, edge2), listOf(n0, n1, n2, n3))
        val matcher = MapMatcher(repository)

        // A handful of points sampled along each edge in turn
        val track = listOf(
            pt(50.7802), pt(50.7805), pt(50.7808),
            pt(50.7812), pt(50.7815), pt(50.7818),
            pt(50.7822), pt(50.7825), pt(50.7828)
        )

        val result = matcher.matchTrack(track)

        assertEquals(listOf(edge0, edge1, edge2), result)
    }

    @Test
    fun `noisy track with a missed middle edge is repaired via local adjacency search`() = runTest {
        val n0 = MapNode(0, 50.7800, 6.0800)
        val n1 = MapNode(1, 50.7810, 6.0800)
        val n2 = MapNode(2, 50.7820, 6.0800)
        val n3 = MapNode(3, 50.7830, 6.0800)
        val edge0 = edge(n0, n1)
        val edge1 = edge(n1, n2)
        val edge2 = edge(n2, n3)

        val repository = fakeRepository(listOf(edge0, edge1, edge2), listOf(n0, n1, n2, n3))
        val matcher = MapMatcher(repository)

        // GPS noise causes a gap: no points land near edge1's stretch, jumping straight from
        // edge0's territory to edge2's — the snapped sequence is [edge0, edge2], a one-hop gap
        // that local adjacency search should bridge by splicing edge1 back in.
        val track = listOf(
            pt(50.7802), pt(50.7805), pt(50.7808),
            pt(50.7822), pt(50.7825), pt(50.7828)
        )

        val result = matcher.matchTrack(track)

        assertEquals(listOf(edge0, edge1, edge2), result)
    }

    @Test
    fun `track jumping between disconnected components is rejected cleanly`() = runTest {
        // Component A (south) and component B (north) share no nodes/edges
        val a0 = MapNode(0, 50.7800, 6.0800)
        val a1 = MapNode(1, 50.7810, 6.0800)
        val a2 = MapNode(2, 50.7820, 6.0800)
        val b0 = MapNode(10, 50.9000, 6.0800)
        val b1 = MapNode(11, 50.9010, 6.0800)
        val b2 = MapNode(12, 50.9020, 6.0800)

        val edgeA0 = edge(a0, a1)
        val edgeA1 = edge(a1, a2)
        val edgeB0 = edge(b0, b1)
        val edgeB1 = edge(b1, b2)

        val repository = fakeRepository(
            listOf(edgeA0, edgeA1, edgeB0, edgeB1),
            listOf(a0, a1, a2, b0, b1, b2)
        )
        val matcher = MapMatcher(repository)

        // Snaps onto component A, then teleports onto component B — no connecting path exists
        val track = listOf(
            pt(50.7802), pt(50.7805), pt(50.7808),
            pt(50.9012), pt(50.9015), pt(50.9018)
        )

        val result = matcher.matchTrack(track)

        assertNull(result)
    }
}
