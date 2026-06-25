package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.domain.model.CorridorConnector
import com.velometrics.app.domain.model.FlowSegment
import com.velometrics.app.domain.model.GraphMetadata
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.model.Poi
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.domain.repository.RoutingEdge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RouteRefinerTest {

    @Test
    fun `expands corridor candidate into edge-level route`() = runTest {
        val (edges, nodes) = simpleLoopGraph()
        val corridorMap = simpleCorridorMap()
        val candidate = CandidateLoop(
            corridors = listOf(1L, 2L),
            totalDistanceM = 1000.0,
            totalReward = 10.0,
            flowScore = 8.0,
            discoveryScore = 2.0,
        )

        val result = RouteRefiner.refine(
            candidate, corridorMap, FakeRepository(edges, nodes),
        )

        assertNotNull(result)
        assertTrue("Expected at least one edge", result!!.edges.isNotEmpty())
        assertEquals(result.edges.first().fromNode, corridorMap[1L]!!.entryNode)
        assertEquals(result.edges.last().toNode, corridorMap[1L]!!.entryNode)
    }

    @Test
    fun `loads only bbox slice, never full edge graph`() = runTest {
        val (edges, nodes) = simpleLoopGraph()
        val repo = TrackingFakeRepository(edges, nodes)
        val corridorMap = simpleCorridorMap()
        val candidate = CandidateLoop(
            corridors = listOf(1L, 2L),
            totalDistanceM = 1000.0,
            totalReward = 10.0,
            flowScore = 8.0,
            discoveryScore = 2.0,
        )

        RouteRefiner.refine(candidate, corridorMap, repo)

        assertTrue("getRoutingEdgesNear should be called", repo.getRoutingEdgesNearCalled)
        assertTrue("getNodesNear should be called", repo.getNodesNearCalled)
        assertFalse("getAllEdges must not be called", repo.getAllEdgesCalled)
        assertFalse("getAllNodes must not be called", repo.getAllNodesCalled)
    }

    @Test
    fun `picks shortest path between waypoints`() = runTest {
        val (edges, nodes) = shortVsLongGraph()
        val corridorMap = mapOf(
            1L to corridor(1, entryNode = 1, exitNode = 3, lat = 50.0, lon = 6.0),
            2L to corridor(2, entryNode = 3, exitNode = 1, lat = 50.002, lon = 6.002),
        )
        val candidate = CandidateLoop(
            corridors = listOf(1L, 2L),
            totalDistanceM = 500.0,
            totalReward = 10.0,
            flowScore = 8.0,
            discoveryScore = 0.0,
        )

        val result = RouteRefiner.refine(
            candidate, corridorMap, FakeRepository(edges, nodes),
        )

        assertNotNull(result)
        val usesShortPath = result!!.edges.any { it.fromNode == 1L && it.toNode == 2L }
        assertTrue("Shortest path through node 2 should be chosen", usesShortPath)
    }

    @Test
    fun `reports authoritative actual distance from edges`() = runTest {
        val (edges, nodes) = simpleLoopGraph()
        val corridorMap = simpleCorridorMap()
        val candidate = CandidateLoop(
            corridors = listOf(1L, 2L),
            totalDistanceM = 1000.0,
            totalReward = 10.0,
            flowScore = 8.0,
            discoveryScore = 2.0,
        )

        val result = RouteRefiner.refine(
            candidate, corridorMap, FakeRepository(edges, nodes),
        )

        assertNotNull(result)
        val expectedDistance = result!!.edges.sumOf { it.lengthM }
        assertEquals(expectedDistance, result.actualDistanceM, 1e-9)
    }

    @Test
    fun `actual distance can differ from coarse estimate`() = runTest {
        val (edges, nodes) = simpleLoopGraph()
        val corridorMap = simpleCorridorMap()
        val coarseEstimate = 9999.0
        val candidate = CandidateLoop(
            corridors = listOf(1L, 2L),
            totalDistanceM = coarseEstimate,
            totalReward = 10.0,
            flowScore = 8.0,
            discoveryScore = 2.0,
        )

        val result = RouteRefiner.refine(
            candidate, corridorMap, FakeRepository(edges, nodes),
        )

        assertNotNull(result)
        assertNotEquals(
            "Actual distance should differ from coarse estimate",
            coarseEstimate, result!!.actualDistanceM, 1e-9,
        )
    }

    @Test
    fun `returns null when no path exists`() = runTest {
        val nodes = listOf(
            node(1, 50.0, 6.0),
            node(2, 50.001, 6.001),
            node(3, 50.002, 6.002),
        )
        val edges = listOf(
            edge(1, 2, 100.0),
        )
        val corridorMap = mapOf(
            1L to corridor(1, entryNode = 1, exitNode = 3, lat = 50.0, lon = 6.0),
            2L to corridor(2, entryNode = 3, exitNode = 1, lat = 50.002, lon = 6.002),
        )
        val candidate = CandidateLoop(
            corridors = listOf(1L, 2L),
            totalDistanceM = 500.0,
            totalReward = 5.0,
            flowScore = 5.0,
            discoveryScore = 0.0,
        )

        val result = RouteRefiner.refine(
            candidate, corridorMap, FakeRepository(edges, nodes),
        )

        assertNull(result)
    }

    @Test
    fun `empty candidate corridors returns null`() = runTest {
        val corridorMap = emptyMap<Long, Corridor>()
        val candidate = CandidateLoop(
            corridors = listOf(99L),
            totalDistanceM = 500.0,
            totalReward = 5.0,
            flowScore = 5.0,
            discoveryScore = 0.0,
        )

        val result = RouteRefiner.refine(
            candidate, corridorMap, FakeRepository(emptyList(), emptyList()),
        )

        assertNull(result)
    }

    @Test
    fun `computeSegmentBbox covers both nodes with margin`() {
        val from = node(1, 50.0, 6.0)
        val to = node(2, 50.01, 6.01)
        val bbox = RouteRefiner.computeSegmentBbox(from, to, 500.0)

        assertTrue(bbox.minLat < 50.0)
        assertTrue(bbox.maxLat > 50.01)
        assertTrue(bbox.minLon < 6.0)
        assertTrue(bbox.maxLon > 6.01)
    }

    @Test
    fun `computeChainBbox covers all nodes with margin`() {
        val nodes = listOf(
            node(1, 50.0, 6.0),
            node(2, 50.01, 6.01),
            node(3, 49.99, 5.99),
        )
        val bbox = RouteRefiner.computeChainBbox(nodes, 500.0)

        assertTrue(bbox.minLat < 49.99)
        assertTrue(bbox.maxLat > 50.01)
        assertTrue(bbox.minLon < 5.99)
        assertTrue(bbox.maxLon > 6.01)
    }

    @Test
    fun `edge reuse penalty discourages backtracking`() = runTest {
        val nodes = listOf(
            node(1, 50.0, 6.0),
            node(2, 50.001, 6.001),
            node(3, 50.002, 6.002),
            node(4, 50.001, 6.003),
        )
        val edges = listOf(
            edge(1, 2, 100.0),
            edge(2, 3, 100.0),
            edge(3, 4, 150.0),
            edge(4, 1, 150.0),
            edge(3, 2, 100.0),
            edge(2, 1, 100.0),
        )
        val corridorMap = mapOf(
            1L to corridor(1, entryNode = 1, exitNode = 3, lat = 50.0, lon = 6.0),
            2L to corridor(2, entryNode = 3, exitNode = 1, lat = 50.002, lon = 6.002),
        )
        val candidate = CandidateLoop(
            corridors = listOf(1L, 2L),
            totalDistanceM = 500.0,
            totalReward = 10.0,
            flowScore = 8.0,
            discoveryScore = 2.0,
        )

        val result = RouteRefiner.refine(
            candidate, corridorMap, FakeRepository(edges, nodes),
            config = RefinerConfig(edgeReusePenalty = 10.0),
        )

        assertNotNull(result)
        val usesAlternate = result!!.edges.any { it.fromNode == 3L && it.toNode == 4L }
        assertTrue("Should route via node 4 to avoid backtracking on 3->2->1", usesAlternate)
    }

    @Test
    fun `no reuse penalty when edgeReusePenalty is 1`() = runTest {
        val nodes = listOf(
            node(1, 50.0, 6.0),
            node(2, 50.001, 6.001),
            node(3, 50.002, 6.002),
            node(4, 50.001, 6.003),
        )
        val edges = listOf(
            edge(1, 2, 100.0),
            edge(2, 3, 100.0),
            edge(3, 4, 150.0),
            edge(4, 1, 150.0),
            edge(3, 2, 100.0),
            edge(2, 1, 100.0),
        )
        val corridorMap = mapOf(
            1L to corridor(1, entryNode = 1, exitNode = 3, lat = 50.0, lon = 6.0),
            2L to corridor(2, entryNode = 3, exitNode = 1, lat = 50.002, lon = 6.002),
        )
        val candidate = CandidateLoop(
            corridors = listOf(1L, 2L),
            totalDistanceM = 500.0,
            totalReward = 10.0,
            flowScore = 8.0,
            discoveryScore = 2.0,
        )

        val result = RouteRefiner.refine(
            candidate, corridorMap, FakeRepository(edges, nodes),
            config = RefinerConfig(edgeReusePenalty = 1.0),
        )

        assertNotNull(result)
        val usesDirectReturn = result!!.edges.any { it.fromNode == 3L && it.toNode == 2L }
        assertTrue("Without penalty, shortest path 3->2->1 should be used", usesDirectReturn)
    }

    @Test
    fun `buildWaypoints produces entry-exit pairs closed with home return`() {
        val corridors = listOf(
            corridor(1, entryNode = 10, exitNode = 11),
            corridor(2, entryNode = 20, exitNode = 21),
            corridor(3, entryNode = 30, exitNode = 31),
        )

        val waypoints = RouteRefiner.buildWaypoints(corridors)

        assertEquals(listOf(10L, 11L, 20L, 21L, 30L, 31L, 10L), waypoints)
    }

    // --- Test fixtures ---

    private fun edge(
        fromNode: Long,
        toNode: Long,
        lengthM: Double,
        highway: String = "residential",
    ) = MapEdge(
        fromNode = fromNode,
        toNode = toNode,
        lengthM = lengthM,
        highway = highway,
        name = null,
        isTraversed = true,
        geometryEncoded = "",
        speedMedian = null, speedMean = null, speedCount = null,
        speedP25 = null, speedP75 = null, speedP90 = null,
        powerMedian = null, powerMean = null, powerCount = null,
        powerP25 = null, powerP75 = null, powerP90 = null,
        slopePercent = null, traversalCount = null, lastTraversal = null,
        timeOfDayDist = null,
    )

    private fun node(id: Long, lat: Double, lon: Double) = MapNode(id, lat, lon)

    private fun corridor(
        id: Long,
        entryNode: Long = id * 10,
        exitNode: Long = id * 10 + 1,
        lat: Double = 50.0,
        lon: Double = 6.0,
    ) = Corridor(
        id = id,
        entryNode = entryNode,
        exitNode = exitNode,
        lengthM = 1000.0,
        pedalReward = 3.0,
        gravityReward = 2.0,
        predictedReward = 0.0,
        exitHazardScore = 0.0,
        type = "measured",
        centroidLat = lat,
        centroidLon = lon,
    )

    private fun simpleLoopGraph(): Pair<List<MapEdge>, List<MapNode>> {
        val nodes = listOf(
            node(1, 50.0, 6.0),
            node(2, 50.001, 6.001),
            node(3, 50.002, 6.001),
            node(4, 50.001, 6.0),
        )
        val edges = listOf(
            edge(1, 2, 150.0),
            edge(2, 3, 150.0),
            edge(3, 4, 150.0),
            edge(4, 1, 150.0),
        )
        return edges to nodes
    }

    private fun simpleCorridorMap(): Map<Long, Corridor> = mapOf(
        1L to corridor(1, entryNode = 1, exitNode = 2, lat = 50.0, lon = 6.0),
        2L to corridor(2, entryNode = 3, exitNode = 4, lat = 50.002, lon = 6.001),
    )

    /**
     * Graph with two paths from node 1 to node 3:
     *   Short path:  1 → 2 → 3  (100 + 100 = 200m)
     *   Long path:   1 → 4 → 3  (200 + 200 = 400m)
     * And return paths 3 → 2 → 1 and 3 → 4 → 1.
     */
    private fun shortVsLongGraph(): Pair<List<MapEdge>, List<MapNode>> {
        val nodes = listOf(
            node(1, 50.0, 6.0),
            node(2, 50.001, 6.001),
            node(3, 50.002, 6.002),
            node(4, 50.001, 6.003),
        )
        val edges = listOf(
            edge(1, 2, 100.0),
            edge(2, 3, 100.0),
            edge(1, 4, 200.0),
            edge(4, 3, 200.0),
            edge(3, 2, 100.0),
            edge(2, 1, 100.0),
            edge(3, 4, 200.0),
            edge(4, 1, 200.0),
        )
        return edges to nodes
    }

    // --- Fake repositories ---

    private open class FakeRepository(
        private val edges: List<MapEdge> = emptyList(),
        private val nodes: List<MapNode> = emptyList(),
    ) : MapGraphRepository {
        override fun getAllEdges(): Flow<List<MapEdge>> = flowOf(emptyList())
        override fun getAllNodes(): Flow<List<MapNode>> = flowOf(emptyList())
        override suspend fun getEdgesByNodePairs(pairs: List<Pair<Long, Long>>): List<MapEdge> {
            val pairSet = pairs.toSet()
            return edges.filter { (it.fromNode to it.toNode) in pairSet }
        }
        override suspend fun getEdgesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = edges
        override suspend fun getNodesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = nodes
        override suspend fun getNodesByIds(vararg ids: Long) = nodes.filter { it.id in ids }
        override suspend fun getRoutingEdgesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = edges.map { RoutingEdge(it.fromNode, it.toNode, it.lengthM) }
        override fun getTraversedEdges(): Flow<List<MapEdge>> = flowOf(emptyList())
        override fun getUntraversedEdges(): Flow<List<MapEdge>> = flowOf(emptyList())
        override fun getAllPois(): Flow<List<Poi>> = flowOf(emptyList())
        override suspend fun getPoisInBoundingBox(
            minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        ) = emptyList<Poi>()
        override suspend fun getMetadata(): GraphMetadata? = null
        override suspend fun getAllCorridors() = emptyList<Corridor>()
        override suspend fun getAllCorridorConnectors() = emptyList<CorridorConnector>()
        override suspend fun getConnectorsForCorridor(corridorId: Long) =
            emptyList<CorridorConnector>()
        override suspend fun getFlowSegmentsNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) =
            emptyList<FlowSegment>()
    }

    private class TrackingFakeRepository(
        edges: List<MapEdge>,
        nodes: List<MapNode>,
    ) : FakeRepository(edges, nodes) {
        var getRoutingEdgesNearCalled = false
        var getNodesNearCalled = false
        var getAllEdgesCalled = false
        var getAllNodesCalled = false

        override suspend fun getRoutingEdgesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ): List<RoutingEdge> {
            getRoutingEdgesNearCalled = true
            return super.getRoutingEdgesNear(minLat, minLon, maxLat, maxLon)
        }

        override suspend fun getNodesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ): List<MapNode> {
            getNodesNearCalled = true
            return super.getNodesNear(minLat, minLon, maxLat, maxLon)
        }

        override fun getAllEdges(): Flow<List<MapEdge>> {
            getAllEdgesCalled = true
            return super.getAllEdges()
        }

        override fun getAllNodes(): Flow<List<MapNode>> {
            getAllNodesCalled = true
            return super.getAllNodes()
        }
    }
}
