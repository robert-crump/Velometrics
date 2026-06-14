package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.GraphMetadata
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.model.Poi
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.util.GeoUtils
import org.maplibre.android.geometry.LatLng
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
            slopePercent = 0.0, traversalCount = 0, lastTraversal = null, timeOfDayDist = null
        )
    }

    private fun fakeRepository(edges: List<MapEdge>, nodes: List<MapNode>): MapGraphRepository =
        object : MapGraphRepository {
            override fun getAllEdges(): Flow<List<MapEdge>> = flowOf(edges)
            override fun getAllNodes(): Flow<List<MapNode>> = flowOf(nodes)
            override suspend fun getEdgesByNodePairs(pairs: List<Pair<Long, Long>>) =
                edges.filter { (it.fromNode to it.toNode) in pairs.toSet() }
            override suspend fun getEdgesNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) = edges
            override suspend fun getNodesNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) = nodes
            override fun getTraversedEdges(): Flow<List<MapEdge>> = flowOf(edges.filter { it.isTraversed })
            override fun getUntraversedEdges(): Flow<List<MapEdge>> = flowOf(edges.filterNot { it.isTraversed })
            override fun getAllPois(): Flow<List<Poi>> = flowOf(emptyList())
            override suspend fun getPoisInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) = emptyList<Poi>()
            override suspend fun getMetadata(): GraphMetadata? = null
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
    fun `snap rejects a perpendicular spur near a junction and keeps the along-travel edge`() = runTest {
        val n0 = MapNode(0, 50.7800, 6.0800)
        val n1 = MapNode(1, 50.7810, 6.0800)
        val n2 = MapNode(2, 50.7820, 6.0800)
        val n3 = MapNode(3, 50.7830, 6.0800)
        // Dead-end spur running east from n1, very close to the track but perpendicular to travel.
        val nSpur = MapNode(4, 50.7810, 6.0801)

        val edge0 = edge(n0, n1)
        val edge1 = edge(n1, n2)
        val edge2 = edge(n2, n3)
        val edgeSpur = edge(n1, nSpur)

        val repository = fakeRepository(
            listOf(edge0, edge1, edge2, edgeSpur),
            listOf(n0, n1, n2, n3, nSpur)
        )
        val matcher = MapMatcher(repository)

        // Travels north along edge0/edge1/edge2. The two points near n1 sit closer to the
        // perpendicular spur than to edge1, but the GPS heading is northbound throughout.
        val track = listOf(
            pt(50.7802), pt(50.7805), pt(50.7808),
            listOf(50.781005, 6.080020), listOf(50.781010, 6.080020),
            pt(50.7815), pt(50.7818),
            pt(50.7822), pt(50.7825), pt(50.7828)
        )

        val result = matcher.matchTrack(track)

        assertEquals(listOf(edge0, edge1, edge2), result)
    }

    @Test
    fun `selectSnapCandidate falls back to nearest when no candidate matches heading`() = runTest {
        val repository = fakeRepository(emptyList(), emptyList())
        val matcher = MapMatcher(repository)

        val nearest = RTreeSpatialIndex.EdgeCandidate(edgeKey = 1L, distanceM = 5.0, bearingDeg = 90.0)
        val farther = RTreeSpatialIndex.EdgeCandidate(edgeKey = 2L, distanceM = 15.0, bearingDeg = 95.0)
        val candidates = listOf(nearest, farther)

        // Heading is due north (0deg); neither candidate is within 45deg of it.
        val chosen = matcher.selectSnapCandidate(candidates, heading = 0.0)

        assertEquals(nearest, chosen)
    }

    @Test
    fun `selectSnapCandidate picks the nearest candidate matching heading over a closer mismatch`() = runTest {
        val repository = fakeRepository(emptyList(), emptyList())
        val matcher = MapMatcher(repository)

        val perpendicular = RTreeSpatialIndex.EdgeCandidate(edgeKey = 1L, distanceM = 1.0, bearingDeg = 90.0)
        val alongTravel = RTreeSpatialIndex.EdgeCandidate(edgeKey = 2L, distanceM = 5.0, bearingDeg = 0.0)
        val candidates = listOf(perpendicular, alongTravel)

        val chosen = matcher.selectSnapCandidate(candidates, heading = 0.0)

        assertEquals(alongTravel, chosen)
    }

    @Test
    fun `computeHeadings uses a multi-point window rather than adjacent points`() = runTest {
        val repository = fakeRepository(emptyList(), emptyList())
        val matcher = MapMatcher(repository)

        // Straight northbound track, evenly spaced.
        val points = listOf(
            LatLng(50.7800, 6.0800),
            LatLng(50.7802, 6.0800),
            LatLng(50.7804, 6.0800),
            LatLng(50.7806, 6.0800),
            LatLng(50.7808, 6.0800)
        )

        val headings = matcher.computeHeadings(points)

        // The middle point's heading spans points[0]..points[4] (window radius 2), not just
        // its immediate neighbors - still due north either way here.
        assertEquals(0.0, headings[2]!!, 0.5)
    }

    @Test
    fun `single-point lateral spur is dropped from the matched sequence`() = runTest {
        val n0 = MapNode(0, 50.7800, 6.0800)
        val n1 = MapNode(1, 50.7810, 6.0800)
        val n2 = MapNode(2, 50.7820, 6.0800)
        val n3 = MapNode(3, 50.7830, 6.0800)
        // Dead-end node far enough east that only the spur edge is within snap radius there.
        val nSpur = MapNode(4, 50.7810, 6.0810)

        val edge0 = edge(n0, n1)
        val edge1 = edge(n1, n2)
        val edge2 = edge(n2, n3)
        val edgeSpur = edge(n1, nSpur)

        val repository = fakeRepository(
            listOf(edge0, edge1, edge2, edgeSpur),
            listOf(n0, n1, n2, n3, nSpur)
        )
        val matcher = MapMatcher(repository)

        // A single point sits right on the spur, isolated from the rest of the track.
        val track = listOf(
            pt(50.7802), pt(50.7805), pt(50.7808),
            listOf(50.7810, 6.0810),
            pt(50.7812), pt(50.7815), pt(50.7818),
            pt(50.7822), pt(50.7825), pt(50.7828)
        )

        val result = matcher.matchTrack(track)

        assertEquals(listOf(edge0, edge1, edge2), result)
    }

    @Test
    fun `a single-point connector dropped by the anchor floor is restored as a bridge`() = runTest {
        val n0 = MapNode(0, 50.7800, 6.0800)
        val n1 = MapNode(1, 50.7810, 6.0800)
        val n2 = MapNode(2, 50.7820, 6.0800)
        val n3 = MapNode(3, 50.7830, 6.0800)
        val edge0 = edge(n0, n1)
        val edge1 = edge(n1, n2)
        val edge2 = edge(n2, n3)

        val repository = fakeRepository(listOf(edge0, edge1, edge2), listOf(n0, n1, n2, n3))
        val matcher = MapMatcher(repository)

        // edge1 gets exactly one snapped point (below INTERVAL_MATCH_MIN_ANCHOR_POINTS), so it's
        // dropped as an anchor — but repairGaps must re-insert it to bridge edge0 -> edge2.
        val track = listOf(
            pt(50.7802), pt(50.7805), pt(50.7808),
            pt(50.7815),
            pt(50.7822), pt(50.7825), pt(50.7828)
        )

        val result = matcher.matchTrack(track)

        assertEquals(listOf(edge0, edge1, edge2), result)
    }

    @Test
    fun `at a fork the high-count branch is kept and the low-count branch is dropped`() = runTest {
        val n0 = MapNode(0, 50.7800, 6.0800)
        val n1 = MapNode(1, 50.7810, 6.0800)
        val n2 = MapNode(2, 50.7820, 6.0800)
        val n3 = MapNode(3, 50.7830, 6.0800)
        // A low-count branch off n1, nearly parallel to edge1 but geometrically closer to one
        // of the GPS points near the fork.
        val nLow = MapNode(4, 50.7820, 6.0803)

        val edge0 = edge(n0, n1)
        val edge1 = edge(n1, n2)
        val edge2 = edge(n2, n3)
        val edgeLow = edge(n1, nLow)

        val repository = fakeRepository(
            listOf(edge0, edge1, edge2, edgeLow),
            listOf(n0, n1, n2, n3, nLow)
        )
        val matcher = MapMatcher(repository)

        // One point near the fork sits closer to edgeLow's line than to edge1's, but edge1
        // collects far more points overall, so it's kept as the anchor and edgeLow is dropped.
        val track = listOf(
            pt(50.7802), pt(50.7805), pt(50.7808),
            listOf(50.7813, 6.08015),
            pt(50.7815), pt(50.7817), pt(50.7818),
            pt(50.7822), pt(50.7825), pt(50.7828)
        )

        val result = matcher.matchTrack(track)

        assertEquals(listOf(edge0, edge1, edge2), result)
    }

    @Test
    fun `an out-and-back spur collapses to a degree-1 leaf and is peeled`() = runTest {
        val n0 = MapNode(0, 50.7800, 6.0800)
        val n1 = MapNode(1, 50.7810, 6.0800)
        val n2 = MapNode(2, 50.7820, 6.0800)
        // Dead-end node reached and left along the same physical edge (out-and-back).
        val nDead = MapNode(3, 50.7805, 6.0820)

        val edge0 = edge(n0, n1)
        val edge1 = edge(n1, n2)
        val edgeOut = edge(n1, nDead)
        val edgeBack = edge(nDead, n1)

        val repository = fakeRepository(
            listOf(edge0, edge1, edgeOut, edgeBack),
            listOf(n0, n1, n2, nDead)
        )
        val matcher = MapMatcher(repository)

        // North along edge0, out to nDead and back (anti-parallel pair, each with >=2 points),
        // then continue north along edge1. nDead has no other connections, so the out-and-back
        // pair should collapse to a degree-1 leaf and be peeled, leaving edge0 -> edge1.
        val track = listOf(
            pt(50.7802), pt(50.7805), pt(50.7808),
            listOf(50.7809, 6.0805), listOf(50.7808, 6.0810), listOf(50.7807, 6.0815),
            listOf(50.7806, 6.0816), listOf(50.7808, 6.0808), listOf(50.7809, 6.0802),
            pt(50.7812), pt(50.7815), pt(50.7818)
        )

        val result = matcher.matchTrack(track)

        assertEquals(listOf(edge0, edge1), result)
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
