package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.domain.model.CorridorConnector
import com.velometrics.app.domain.model.GraphMetadata
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.model.Poi
import com.velometrics.app.domain.repository.MapGraphRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RouteRefinerTest {

    // --- Expansion ---

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

    // --- Bbox-only loading ---

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

        assertTrue("getEdgesNear should be called", repo.getEdgesNearCalled)
        assertTrue("getNodesNear should be called", repo.getNodesNearCalled)
        assertFalse("getAllEdges must not be called", repo.getAllEdgesCalled)
        assertFalse("getAllNodes must not be called", repo.getAllNodesCalled)
    }

    // --- Reward-weighted A-star beats pure shortest path ---

    @Test
    fun `reward-weighted A-star picks higher-reward path over shorter path`() = runTest {
        val (edges, nodes) = rewardVsShortestGraph()
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
            config = RefinerConfig(rewardWeight = 10.0),
        )

        assertNotNull(result)
        val edgeNodes = result!!.edges.map { it.fromNode to it.toNode }
        assertTrue(
            "High-reward path through node 4 should be chosen",
            edgeNodes.any { it.first == 4L || it.second == 4L },
        )
    }

    @Test
    fun `zero reward weight picks shorter path over higher-reward path`() = runTest {
        val (edges, nodes) = rewardVsShortestGraph()
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
            config = RefinerConfig(rewardWeight = 0.0),
        )

        assertNotNull(result)
        val forwardEdges = result!!.edges.filter { it.fromNode == 1L || it.toNode == 3L }
        val usesShortPath = result.edges.any { it.fromNode == 1L && it.toNode == 2L }
        assertTrue(
            "With zero reward weight, shortest path through node 2 should be chosen",
            usesShortPath,
        )
    }

    // --- Hazard filtering ---

    @Test
    fun `hazard-rejected edges never appear in refined route`() = runTest {
        val (edges, nodes) = hazardGraph()
        val corridorMap = mapOf(
            1L to corridor(1, entryNode = 1, exitNode = 3, lat = 50.0, lon = 6.0),
            2L to corridor(2, entryNode = 3, exitNode = 1, lat = 50.001, lon = 6.001),
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

        assertNotNull(result)
        for (edge in result!!.edges) {
            val hazard = edge.hazardScore
            if (hazard != null) {
                assertTrue(
                    "Hazardous edge with score $hazard should not appear",
                    hazard < 0.7,
                )
            }
            assertFalse(
                "Motorway edges should not appear",
                edge.highway == "motorway",
            )
        }
    }

    // --- Junction costs influence choices ---

    @Test
    fun `junction costs penalize sharp turns`() = runTest {
        val (edges, nodes) = junctionCostGraph()
        val corridorMap = mapOf(
            1L to corridor(1, entryNode = 1, exitNode = 4, lat = 50.0, lon = 6.0),
            2L to corridor(2, entryNode = 4, exitNode = 1, lat = 50.001, lon = 6.003),
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
            config = RefinerConfig(rewardWeight = 0.0, turnCostWeight = 200.0),
        )

        assertNotNull(result)
        val forwardEdges = result!!.edges.takeWhile { it.toNode != 4L } +
            result.edges.first { it.toNode == 4L }
        val usesStraightPath = forwardEdges.any { it.fromNode == 2L && it.toNode == 3L }
        assertTrue(
            "With high turn cost weight, the straighter path should be preferred",
            usesStraightPath,
        )
    }

    // --- Authoritative actual distance ---

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

    // --- Reward scores ---

    @Test
    fun `refined route carries flow and discovery sub-scores`() = runTest {
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
        assertTrue(result!!.flowScore >= 0.0)
        assertTrue(result.discoveryScore >= 0.0)
        assertTrue(result.totalReward != 0.0 || result.edges.all {
            (it.pedalFlowCount ?: 0) + (it.gravityFlowCount ?: 0) == 0
        })
    }

    // --- Edge cases ---

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

    // --- Helpers: computeCandidateBbox ---

    @Test
    fun `computeCandidateBbox covers all corridor centroids with margin`() {
        val corridors = listOf(
            corridor(1, lat = 50.0, lon = 6.0),
            corridor(2, lat = 50.05, lon = 6.05),
        )
        val bbox = RouteRefiner.computeCandidateBbox(corridors, 1000.0)

        assertTrue(bbox.minLat < 50.0)
        assertTrue(bbox.maxLat > 50.05)
        assertTrue(bbox.minLon < 6.0)
        assertTrue(bbox.maxLon > 6.05)
    }

    // --- Helpers: buildWaypoints ---

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
        pedalFlowCount: Int? = null,
        gravityFlowCount: Int? = null,
        hazardScore: Double? = null,
        highway: String = "residential",
        stopPenalty: Double? = null,
        isTraversed: Boolean = true,
        avgStopCount: Double? = null,
    ) = MapEdge(
        fromNode = fromNode,
        toNode = toNode,
        lengthM = lengthM,
        highway = highway,
        name = null,
        isTraversed = isTraversed,
        geometryEncoded = "",
        speedMedian = null, speedMean = null, speedCount = null,
        speedP25 = null, speedP75 = null, speedP90 = null,
        powerMedian = null, powerMean = null, powerCount = null,
        powerP25 = null, powerP75 = null, powerP90 = null,
        slopePercent = null, traversalCount = null, lastTraversal = null,
        timeOfDayDist = null,
        avgStopCount = avgStopCount,
        pedalFlowCount = pedalFlowCount,
        gravityFlowCount = gravityFlowCount,
        hazardScore = hazardScore,
        stopPenalty = stopPenalty,
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

    /**
     * Simple loop graph:
     *   1 → 2 → 3 → 4 → 1
     * Corridors: c1(entry=1, exit=2), c2(entry=3, exit=4)
     * With connecting edges 2→3 and 4→1.
     */
    private fun simpleLoopGraph(): Pair<List<MapEdge>, List<MapNode>> {
        val nodes = listOf(
            node(1, 50.0, 6.0),
            node(2, 50.001, 6.001),
            node(3, 50.002, 6.001),
            node(4, 50.001, 6.0),
        )
        val edges = listOf(
            edge(1, 2, 150.0, pedalFlowCount = 3, gravityFlowCount = 2),
            edge(2, 3, 150.0, pedalFlowCount = 2, gravityFlowCount = 1),
            edge(3, 4, 150.0, pedalFlowCount = 4, gravityFlowCount = 3),
            edge(4, 1, 150.0, pedalFlowCount = 2, gravityFlowCount = 2),
        )
        return edges to nodes
    }

    private fun simpleCorridorMap(): Map<Long, Corridor> = mapOf(
        1L to corridor(1, entryNode = 1, exitNode = 2, lat = 50.0, lon = 6.0),
        2L to corridor(2, entryNode = 3, exitNode = 4, lat = 50.002, lon = 6.001),
    )

    /**
     * Graph with two paths from node 1 to node 3:
     *   Short path:  1 → 2 → 3  (100 + 100 = 200m, zero flow)
     *   Reward path: 1 → 4 → 3  (120 + 120 = 240m, high flow)
     * And return paths 3 → 2 → 1 and 3 → 4 → 1.
     */
    private fun rewardVsShortestGraph(): Pair<List<MapEdge>, List<MapNode>> {
        val nodes = listOf(
            node(1, 50.0, 6.0),
            node(2, 50.001, 6.001),
            node(3, 50.002, 6.002),
            node(4, 50.001, 6.003),
        )
        val edges = listOf(
            edge(1, 2, 100.0, pedalFlowCount = 0, gravityFlowCount = 0),
            edge(2, 3, 100.0, pedalFlowCount = 0, gravityFlowCount = 0),
            edge(1, 4, 120.0, pedalFlowCount = 10, gravityFlowCount = 5),
            edge(4, 3, 120.0, pedalFlowCount = 10, gravityFlowCount = 5),
            edge(3, 2, 100.0, pedalFlowCount = 0, gravityFlowCount = 0),
            edge(2, 1, 100.0, pedalFlowCount = 0, gravityFlowCount = 0),
            edge(3, 4, 120.0, pedalFlowCount = 10, gravityFlowCount = 5),
            edge(4, 1, 120.0, pedalFlowCount = 10, gravityFlowCount = 5),
        )
        return edges to nodes
    }

    /**
     * Graph where the only short direct edge 1→3 is hazardous:
     *   Hazardous: 1 → 3  (50m, hazardScore=0.8)
     *   Safe:      1 → 2 → 3  (100 + 100 = 200m)
     * Return: 3 → 2 → 1.
     */
    private fun hazardGraph(): Pair<List<MapEdge>, List<MapNode>> {
        val nodes = listOf(
            node(1, 50.0, 6.0),
            node(2, 50.001, 6.001),
            node(3, 50.002, 6.0),
        )
        val edges = listOf(
            edge(1, 3, 50.0, hazardScore = 0.8, highway = "residential"),
            edge(1, 2, 100.0, pedalFlowCount = 2),
            edge(2, 3, 100.0, pedalFlowCount = 2),
            edge(3, 2, 100.0, pedalFlowCount = 2),
            edge(2, 1, 100.0, pedalFlowCount = 2),
        )
        return edges to nodes
    }

    /**
     * Graph where path from 1→4 has two options via node 2 or node 5:
     *   Via 2→3: requires straight continuation (low turn cost)
     *   Via 5→3: requires sharp turn at node 5 (high turn cost)
     * Both paths have similar length; turn cost should break the tie.
     *
     * Layout:
     *   1 is at (50.0, 6.0), 2 is east at (50.0, 6.002),
     *   3 is east at (50.0, 6.004), 4 is east at (50.0, 6.006)
     *   5 is north at (50.002, 6.002) — path through 5 requires a sharp N then S turn
     */
    private fun junctionCostGraph(): Pair<List<MapEdge>, List<MapNode>> {
        val nodes = listOf(
            node(1, 50.0, 6.0),
            node(2, 50.0, 6.002),
            node(3, 50.0, 6.004),
            node(4, 50.0, 6.006),
            node(5, 50.002, 6.002),
        )
        val edges = listOf(
            edge(1, 2, 150.0),
            edge(2, 3, 150.0),
            edge(3, 4, 150.0),
            edge(1, 5, 160.0),
            edge(5, 3, 160.0),
            edge(4, 3, 150.0),
            edge(3, 2, 150.0),
            edge(2, 1, 150.0),
            edge(4, 1, 450.0),
        )
        return edges to nodes
    }

    // --- Fake repositories ---

    private open class FakeRepository(
        private val edges: List<MapEdge>,
        private val nodes: List<MapNode>,
    ) : MapGraphRepository {
        override fun getAllEdges(): Flow<List<MapEdge>> = flowOf(emptyList())
        override fun getAllNodes(): Flow<List<MapNode>> = flowOf(emptyList())
        override suspend fun getEdgesByNodePairs(pairs: List<Pair<Long, Long>>) = emptyList<MapEdge>()
        override suspend fun getEdgesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = edges
        override suspend fun getNodesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = nodes
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
    }

    private class TrackingFakeRepository(
        edges: List<MapEdge>,
        nodes: List<MapNode>,
    ) : FakeRepository(edges, nodes) {
        var getEdgesNearCalled = false
        var getNodesNearCalled = false
        var getAllEdgesCalled = false
        var getAllNodesCalled = false

        override suspend fun getEdgesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ): List<MapEdge> {
            getEdgesNearCalled = true
            return super.getEdgesNear(minLat, minLon, maxLat, maxLon)
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
