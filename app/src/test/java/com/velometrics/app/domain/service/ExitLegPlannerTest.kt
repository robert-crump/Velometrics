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

class ExitLegPlannerTest {

    // --- Happy path ---

    @Test
    fun `computes exit leg from home to corridor entry on high-traversal subgraph`() = runTest {
        val (edges, nodes) = homeToCorridorGraph()
        val corridors = listOf(corridorAt5km())
        val config = configWithSmallBbox()

        val result = ExitLegPlanner.computeExitLeg(
            homeLat = HOME_LAT, homeLon = HOME_LON,
            direction = null,
            corridors = corridors,
            remainingBudgetM = 50_000.0,
            repository = FakeRepo(edges, nodes),
            config = config,
        )

        assertNotNull(result)
        assertTrue(result!!.edges.isNotEmpty())
        assertEquals(corridors[0].entryNode, result.targetNode)
        assertEquals(result.edges.sumOf { it.lengthM }, result.distanceM, 1e-9)
    }

    @Test
    fun `picks corridor with highest traversal-weighted score`() = runTest {
        val (edges, nodes) = twoCandidateGraph()
        val corridorHigh = corridor(10, entryNode = 100, exitNode = 101,
            lat = HOME_LAT + 0.04, lon = HOME_LON + 0.005)
        val corridorLow = corridor(20, entryNode = 200, exitNode = 201,
            lat = HOME_LAT + 0.04, lon = HOME_LON - 0.005)
        val config = configWithSmallBbox().copy(fallbackThresholds = listOf(0.0))

        val result = ExitLegPlanner.computeExitLeg(
            homeLat = HOME_LAT, homeLon = HOME_LON,
            direction = null,
            corridors = listOf(corridorHigh, corridorLow),
            remainingBudgetM = 50_000.0,
            repository = FakeRepo(edges, nodes),
            config = config,
        )

        assertNotNull(result)
        assertEquals("Should pick corridor with higher traversal score",
            100L, result!!.targetNode)
    }

    // --- Threshold fallback ---

    @Test
    fun `falls back to lower threshold when no path at 25 percent`() = runTest {
        val nodes = listOf(
            node(1, HOME_LAT, HOME_LON),
            node(2, HOME_LAT + 0.01, HOME_LON + 0.01),
            node(100, HOME_LAT + 0.04, HOME_LON),
        )
        val edges = listOf(
            edge(1, 2, 200.0, traversalCount = 2),
            edge(2, 100, 200.0, traversalCount = 2),
        )
        val corridors = listOf(corridorAt5km())
        val config = configWithSmallBbox().copy(
            fallbackThresholds = listOf(0.25, 0.0),
        )

        val result = ExitLegPlanner.computeExitLeg(
            homeLat = HOME_LAT, homeLon = HOME_LON,
            direction = null,
            corridors = corridors,
            remainingBudgetM = 50_000.0,
            repository = FakeRepo(edges, nodes),
            config = config,
        )

        assertNotNull("Should find path at 0% threshold fallback", result)
        assertEquals(100L, result!!.targetNode)
    }

    // --- No corridors fallback ---

    @Test
    fun `uses edge-based fallback when no corridors in range`() = runTest {
        val nodes = listOf(
            node(1, HOME_LAT, HOME_LON),
            node(2, HOME_LAT + 0.001, HOME_LON),
            node(3, HOME_LAT + 0.002, HOME_LON),
        )
        val edges = listOf(
            edge(1, 2, 100.0, traversalCount = 5),
            edge(2, 3, 100.0, traversalCount = 3),
        )
        val farCorridor = corridor(
            id = 99, entryNode = 999, exitNode = 998,
            lat = HOME_LAT + 0.5, lon = HOME_LON,
        )
        val config = configWithSmallBbox()

        val result = ExitLegPlanner.computeExitLeg(
            homeLat = HOME_LAT, homeLon = HOME_LON,
            direction = null,
            corridors = listOf(farCorridor),
            remainingBudgetM = 50_000.0,
            repository = FakeRepo(edges, nodes),
            config = config,
        )

        assertNotNull("Should use edge-based fallback", result)
        assertTrue(result!!.edges.isNotEmpty())
        assertEquals(farCorridor.entryNode, result.targetNode)
    }

    @Test
    fun `edge fallback returns null when no traversed edges exist`() = runTest {
        val nodes = listOf(node(1, HOME_LAT, HOME_LON))
        val edges = listOf(edge(1, 2, 100.0, traversalCount = 0))
        val config = configWithSmallBbox()

        val result = ExitLegPlanner.computeExitLeg(
            homeLat = HOME_LAT, homeLon = HOME_LON,
            direction = null,
            corridors = emptyList(),
            remainingBudgetM = 50_000.0,
            repository = FakeRepo(edges, nodes),
            config = config,
        )

        assertNull(result)
    }

    // --- Soft cap ---

    @Test
    fun `skips exit leg when nearest corridor exceeds soft cap`() = runTest {
        val (edges, nodes) = homeToCorridorGraph()
        val corridors = listOf(corridorAt5km())
        val tightBudget = 1000.0
        val config = configWithSmallBbox().copy(softCapFraction = 0.15)

        val result = ExitLegPlanner.computeExitLeg(
            homeLat = HOME_LAT, homeLon = HOME_LON,
            direction = null,
            corridors = corridors,
            remainingBudgetM = tightBudget,
            repository = FakeRepo(edges, nodes),
            config = config,
        )

        assertNull("Soft cap should skip exit leg", result)
    }

    // --- Return leg ---

    @Test
    fun `computes return leg from corridor exit to home`() = runTest {
        val (edges, nodes) = returnLegGraph()
        val config = configWithSmallBbox().copy(fallbackThresholds = listOf(0.0))

        val result = ExitLegPlanner.computeReturnLeg(
            fromNode = 100L,
            fromLat = HOME_LAT + 0.04,
            fromLon = HOME_LON,
            homeLat = HOME_LAT,
            homeLon = HOME_LON,
            repository = FakeRepo(edges, nodes),
            config = config,
        )

        assertNotNull(result)
        assertEquals(1L, result!!.targetNode)
        assertTrue(result.edges.isNotEmpty())
        assertEquals(result.edges.sumOf { it.lengthM }, result.distanceM, 1e-9)
    }

    @Test
    fun `return leg returns null when no path exists`() = runTest {
        val nodes = listOf(
            node(1, HOME_LAT, HOME_LON),
            node(100, HOME_LAT + 0.04, HOME_LON),
        )
        val edges = emptyList<MapEdge>()

        val result = ExitLegPlanner.computeReturnLeg(
            fromNode = 100L,
            fromLat = HOME_LAT + 0.04,
            fromLon = HOME_LON,
            homeLat = HOME_LAT,
            homeLon = HOME_LON,
            repository = FakeRepo(edges, nodes),
        )

        assertNull(result)
    }

    // --- Direction filtering ---

    @Test
    fun `filters candidate corridors by direction`() {
        val north = corridor(1, lat = HOME_LAT + 0.05, lon = HOME_LON)
        val south = corridor(2, lat = HOME_LAT - 0.05, lon = HOME_LON)
        val config = configWithSmallBbox().copy(
            minCorridorDistM = 1000.0, maxCorridorDistM = 20_000.0,
        )

        val result = ExitLegPlanner.findCandidateCorridors(
            listOf(north, south), HOME_LAT, HOME_LON, RideDirection.NORTH, config,
        )

        assertEquals(1, result.size)
        assertEquals(1L, result[0].id)
    }

    @Test
    fun `returns all directions when direction is null`() {
        val north = corridor(1, lat = HOME_LAT + 0.05, lon = HOME_LON)
        val south = corridor(2, lat = HOME_LAT - 0.05, lon = HOME_LON)
        val config = configWithSmallBbox().copy(
            minCorridorDistM = 1000.0, maxCorridorDistM = 20_000.0,
        )

        val result = ExitLegPlanner.findCandidateCorridors(
            listOf(north, south), HOME_LAT, HOME_LON, null, config,
        )

        assertEquals(2, result.size)
    }

    // --- Helpers ---

    @Test
    fun `scoreExitLeg returns traversal density`() {
        val path = listOf(
            edge(1, 2, 100.0, traversalCount = 10),
            edge(2, 3, 200.0, traversalCount = 20),
        )
        val score = ExitLegPlanner.scoreExitLeg(path)
        assertEquals(30.0 / 300.0, score, 1e-9)
    }

    @Test
    fun `scoreExitLeg returns zero for empty path`() {
        assertEquals(0.0, ExitLegPlanner.scoreExitLeg(emptyList()), 1e-9)
    }

    @Test
    fun `buildBbox creates symmetric box around point`() {
        val bbox = ExitLegPlanner.buildBbox(50.0, 6.0, 2500.0)
        assertTrue(bbox.minLat < 50.0)
        assertTrue(bbox.maxLat > 50.0)
        assertTrue(bbox.minLon < 6.0)
        assertTrue(bbox.maxLon > 6.0)
        assertEquals(50.0, (bbox.minLat + bbox.maxLat) / 2.0, 1e-6)
    }

    @Test
    fun `buildCoveringBbox covers both points with margin`() {
        val bbox = ExitLegPlanner.buildCoveringBbox(50.0, 6.0, 50.05, 6.05, 500.0)
        assertTrue(bbox.minLat < 50.0)
        assertTrue(bbox.maxLat > 50.05)
        assertTrue(bbox.minLon < 6.0)
        assertTrue(bbox.maxLon > 6.05)
    }

    @Test
    fun `findNearestNode picks closest node`() {
        val nodes = listOf(
            node(1, 50.0, 6.0),
            node(2, 50.005, 6.005),
            node(3, 50.01, 6.01),
        )
        val nearest = ExitLegPlanner.findNearestNode(nodes, 50.0001, 6.0001)
        assertNotNull(nearest)
        assertEquals(1L, nearest!!.id)
    }

    // --- Test graph factories ---

    private fun homeToCorridorGraph(): Pair<List<MapEdge>, List<MapNode>> {
        val nodes = listOf(
            node(1, HOME_LAT, HOME_LON),
            node(2, HOME_LAT + 0.01, HOME_LON),
            node(3, HOME_LAT + 0.02, HOME_LON),
            node(100, HOME_LAT + 0.04, HOME_LON),
        )
        val edges = listOf(
            edge(1, 2, 500.0, traversalCount = 10),
            edge(2, 3, 500.0, traversalCount = 8),
            edge(3, 100, 500.0, traversalCount = 6),
        )
        return edges to nodes
    }

    private fun twoCandidateGraph(): Pair<List<MapEdge>, List<MapNode>> {
        val nodes = listOf(
            node(1, HOME_LAT, HOME_LON),
            node(2, HOME_LAT + 0.01, HOME_LON + 0.005),
            node(3, HOME_LAT + 0.01, HOME_LON - 0.005),
            node(100, HOME_LAT + 0.04, HOME_LON + 0.005),
            node(200, HOME_LAT + 0.04, HOME_LON - 0.005),
        )
        val edges = listOf(
            edge(1, 2, 500.0, traversalCount = 20),
            edge(2, 100, 500.0, traversalCount = 15),
            edge(1, 3, 500.0, traversalCount = 2),
            edge(3, 200, 500.0, traversalCount = 1),
        )
        return edges to nodes
    }

    private fun returnLegGraph(): Pair<List<MapEdge>, List<MapNode>> {
        val nodes = listOf(
            node(1, HOME_LAT, HOME_LON),
            node(50, HOME_LAT + 0.02, HOME_LON),
            node(100, HOME_LAT + 0.04, HOME_LON),
        )
        val edges = listOf(
            edge(100, 50, 500.0, traversalCount = 8),
            edge(50, 1, 500.0, traversalCount = 10),
        )
        return edges to nodes
    }

    private fun corridorAt5km() = corridor(
        id = 10, entryNode = 100, exitNode = 101,
        lat = HOME_LAT + 0.04, lon = HOME_LON,
    )

    private fun edge(
        fromNode: Long,
        toNode: Long,
        lengthM: Double,
        traversalCount: Int = 0,
    ) = MapEdge(
        fromNode = fromNode, toNode = toNode, lengthM = lengthM,
        highway = "residential", name = null,
        isTraversed = traversalCount > 0, geometryEncoded = "",
        speedMedian = null, speedMean = null, speedCount = null,
        speedP25 = null, speedP75 = null, speedP90 = null,
        powerMedian = null, powerMean = null, powerCount = null,
        powerP25 = null, powerP75 = null, powerP90 = null,
        slopePercent = null, traversalCount = traversalCount,
        lastTraversal = null, timeOfDayDist = null,
    )

    private fun node(id: Long, lat: Double, lon: Double) = MapNode(id, lat, lon)

    private fun corridor(
        id: Long,
        entryNode: Long = id * 10,
        exitNode: Long = id * 10 + 1,
        lat: Double = HOME_LAT,
        lon: Double = HOME_LON,
    ) = Corridor(
        id = id, entryNode = entryNode, exitNode = exitNode,
        lengthM = 1000.0, pedalReward = 3.0, gravityReward = 2.0,
        exitHazardScore = 0.0, centroidLat = lat, centroidLon = lon,
        edgeList = emptyList(), popularity = 0, groupId = id,
    )

    private fun configWithSmallBbox() = ExitLegConfig(
        bboxHalfSizeM = 10_000.0,
        minCorridorDistM = 2000.0,
        maxCorridorDistM = 10_000.0,
    )

    private open class FakeRepo(
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
        override suspend fun getFlowSegmentsNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = emptyList<FlowSegment>()
    }

    companion object {
        private const val HOME_LAT = 50.0
        private const val HOME_LON = 6.0
    }
}
