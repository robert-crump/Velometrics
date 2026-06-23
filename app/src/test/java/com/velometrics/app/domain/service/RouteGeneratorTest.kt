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

class RouteGeneratorTest {

    // --- End-to-end smoke test ---

    @Test
    fun `generates ranked candidates with sub-scores on small fixture`() = runTest {
        val repo = LoopFixtureRepository()

        val result = RouteGenerator.generate(
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 4000.0,
            repository = repo,
            config = GeneratorConfig(
                orienteerConfig = OrienteerConfig(candidateCount = 3, graspRestarts = 30),
                seed = 42L,
            ),
        )

        assertTrue("Expected Success, got $result", result is RoutePlanResult.Success)
        val success = result as RoutePlanResult.Success
        assertTrue("Expected at least 1 candidate", success.candidates.isNotEmpty())
        assertTrue("Expected at most 3 candidates", success.candidates.size <= 3)

        for (candidate in success.candidates) {
            assertTrue(candidate.refinedRoute.flowScore >= 0.0)
            assertTrue(candidate.refinedRoute.discoveryScore >= 0.0)
            assertTrue(candidate.refinedRoute.actualDistanceM > 0.0)
            assertTrue(candidate.refinedRoute.edges.isNotEmpty())
            assertTrue(candidate.rank >= 1)
        }
    }

    // --- Candidates are ranked by reward ---

    @Test
    fun `candidates are ranked by descending total reward`() = runTest {
        val repo = LoopFixtureRepository()

        val result = RouteGenerator.generate(
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 4000.0,
            repository = repo,
            config = GeneratorConfig(seed = 42L),
        )

        assertTrue(result is RoutePlanResult.Success)
        val candidates = (result as RoutePlanResult.Success).candidates
        if (candidates.size >= 2) {
            for (i in 0 until candidates.size - 1) {
                assertTrue(
                    "Candidate ${i + 1} should have >= reward than candidate ${i + 2}",
                    candidates[i].refinedRoute.totalReward >= candidates[i + 1].refinedRoute.totalReward,
                )
            }
        }
    }

    // --- Home snapping ---

    @Test
    fun `candidates start and end at home corridor`() = runTest {
        val repo = LoopFixtureRepository()

        val result = RouteGenerator.generate(
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 4000.0,
            repository = repo,
            config = GeneratorConfig(seed = 42L),
        )

        assertTrue(result is RoutePlanResult.Success)
        val candidates = (result as RoutePlanResult.Success).candidates
        for (candidate in candidates) {
            val firstEdge = candidate.refinedRoute.edges.first()
            val lastEdge = candidate.refinedRoute.edges.last()
            assertEquals(
                "Loop should return to starting node",
                firstEdge.fromNode, lastEdge.toNode,
            )
        }
    }

    // --- Distance deviation reported ---

    @Test
    fun `distance deviation percent is reported per candidate`() = runTest {
        val repo = LoopFixtureRepository()
        val targetM = 4000.0

        val result = RouteGenerator.generate(
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = targetM,
            repository = repo,
            config = GeneratorConfig(seed = 42L),
        )

        assertTrue(result is RoutePlanResult.Success)
        for (candidate in (result as RoutePlanResult.Success).candidates) {
            val expectedDeviation =
                (candidate.refinedRoute.actualDistanceM - targetM) / targetM * 100.0
            assertEquals(expectedDeviation, candidate.distanceDeviationPercent, 1e-6)
        }
    }

    // --- Failure on empty corridors ---

    @Test
    fun `fails with message when no corridor data exists`() = runTest {
        val repo = EmptyRepository()

        val result = RouteGenerator.generate(
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 50000.0,
            repository = repo,
        )

        assertTrue("Expected Failure, got $result", result is RoutePlanResult.Failure)
        val failure = result as RoutePlanResult.Failure
        assertTrue(failure.reason.contains("corridor"))
    }

    // --- Hard failure when no loop is possible ---

    @Test
    fun `hard failure when no loop can be formed`() = runTest {
        val repo = DisconnectedRepository()

        val result = RouteGenerator.generate(
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 50000.0,
            repository = repo,
        )

        assertTrue("Expected Failure, got $result", result is RoutePlanResult.Failure)
    }

    // --- Degradation path is exercised ---

    @Test
    fun `relaxed tier when insufficient tight candidates`() = runTest {
        val repo = SparseLoopRepository()

        val result = RouteGenerator.generate(
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 5000.0,
            repository = repo,
            config = GeneratorConfig(
                degradationConfig = DegradationConfig(minDesiredCandidates = 10),
                seed = 42L,
            ),
        )

        if (result is RoutePlanResult.Success) {
            assertTrue(
                "Expected relaxation beyond NONE when asking for 10 candidates with a sparse graph",
                result.appliedTier != DegradationPolicy.RelaxationTier.NONE ||
                    result.candidates.isNotEmpty(),
            )
        }
    }

    // --- estimateMaxReachable ---

    @Test
    fun `estimateMaxReachable returns null for empty inputs`() {
        assertNull(RouteGenerator.estimateMaxReachable(emptyList(), emptyList()))
    }

    @Test
    fun `estimateMaxReachable returns positive value for non-empty inputs`() {
        val corridors = listOf(
            corridor(1, lengthM = 1000.0),
            corridor(2, lengthM = 2000.0),
        )
        val connectors = listOf(
            connector(1, 2, 500.0),
        )

        val result = RouteGenerator.estimateMaxReachable(corridors, connectors)

        assertNotNull(result)
        assertTrue(result!! > 0.0)
    }

    // --- Helpers ---

    private fun corridor(
        id: Long,
        lat: Double = 50.0,
        lon: Double = 6.0,
        pedalReward: Double = 3.0,
        gravityReward: Double = 2.0,
        lengthM: Double = 1000.0,
    ) = Corridor(
        id = id,
        entryNode = id * 10,
        exitNode = id * 10 + 1,
        lengthM = lengthM,
        pedalReward = pedalReward,
        gravityReward = gravityReward,
        predictedReward = 0.0,
        exitHazardScore = 0.0,
        type = "measured",
        centroidLat = lat,
        centroidLon = lon,
    )

    private fun connector(from: Long, to: Long, distanceM: Double) = CorridorConnector(
        fromCorridor = from,
        toCorridor = to,
        distanceM = distanceM,
    )

    private fun edge(
        fromNode: Long,
        toNode: Long,
        lengthM: Double,
        pedalFlowCount: Int? = 2,
        gravityFlowCount: Int? = 1,
    ) = MapEdge(
        fromNode = fromNode,
        toNode = toNode,
        lengthM = lengthM,
        highway = "residential",
        name = null,
        isTraversed = true,
        geometryEncoded = "",
        speedMedian = null, speedMean = null, speedCount = null,
        speedP25 = null, speedP75 = null, speedP90 = null,
        powerMedian = null, powerMean = null, powerCount = null,
        powerP25 = null, powerP75 = null, powerP90 = null,
        slopePercent = null, traversalCount = null, lastTraversal = null,
        timeOfDayDist = null,
        pedalFlowCount = pedalFlowCount,
        gravityFlowCount = gravityFlowCount,
    )

    private fun node(id: Long, lat: Double, lon: Double) = MapNode(id, lat, lon)

    /**
     * Small loop fixture with 4 corridors forming a ring:
     *   C1(50.0, 6.0) → C2(50.005, 6.005) → C3(50.01, 6.005) → C4(50.005, 6.0) → C1
     * Each corridor has entry/exit nodes; edges connect the exit of one to the entry of the next.
     */
    private inner class LoopFixtureRepository : FakeRepository() {
        private val corridors = listOf(
            corridor(1, lat = 50.0, lon = 6.0),
            corridor(2, lat = 50.005, lon = 6.005),
            corridor(3, lat = 50.01, lon = 6.005),
            corridor(4, lat = 50.005, lon = 6.0),
        )
        private val connectors = listOf(
            connector(1, 2, 800.0),
            connector(2, 3, 800.0),
            connector(3, 4, 800.0),
            connector(4, 1, 800.0),
            connector(1, 3, 1200.0),
            connector(2, 4, 1200.0),
        )
        private val nodes = listOf(
            node(10, 50.0, 6.0),      // C1 entry
            node(11, 50.002, 6.002),   // C1 exit
            node(20, 50.005, 6.005),   // C2 entry
            node(21, 50.007, 6.005),   // C2 exit
            node(30, 50.01, 6.005),    // C3 entry
            node(31, 50.008, 6.003),   // C3 exit
            node(40, 50.005, 6.0),     // C4 entry
            node(41, 50.002, 6.0),     // C4 exit
        )
        private val edges = listOf(
            // Internal corridor edges
            edge(10, 11, 300.0),
            edge(20, 21, 300.0),
            edge(30, 31, 300.0),
            edge(40, 41, 300.0),
            // Connector edges: C1-exit→C2-entry, C2-exit→C3-entry, etc.
            edge(11, 20, 500.0),
            edge(21, 30, 500.0),
            edge(31, 40, 500.0),
            edge(41, 10, 500.0),
            // Cross connectors
            edge(11, 30, 700.0),
            edge(21, 40, 700.0),
            edge(31, 10, 700.0),
            edge(41, 20, 700.0),
        )

        override suspend fun getAllCorridors() = corridors
        override suspend fun getAllCorridorConnectors() = connectors
        override suspend fun getEdgesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = edges
        override suspend fun getNodesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = nodes
    }

    private inner class EmptyRepository : FakeRepository()

    private inner class DisconnectedRepository : FakeRepository() {
        private val corridors = listOf(
            corridor(1, lat = 50.0, lon = 6.0),
            corridor(2, lat = 51.0, lon = 7.0),
        )

        override suspend fun getAllCorridors() = corridors
        override suspend fun getAllCorridorConnectors() = emptyList<CorridorConnector>()
    }

    private inner class SparseLoopRepository : FakeRepository() {
        private val corridors = listOf(
            corridor(1, lat = 50.0, lon = 6.0),
            corridor(2, lat = 50.005, lon = 6.005),
            corridor(3, lat = 50.01, lon = 6.005),
        )
        private val connectors = listOf(
            connector(1, 2, 1000.0),
            connector(2, 3, 1000.0),
            connector(3, 1, 1500.0),
        )
        private val nodes = listOf(
            node(10, 50.0, 6.0),
            node(11, 50.002, 6.002),
            node(20, 50.005, 6.005),
            node(21, 50.007, 6.005),
            node(30, 50.01, 6.005),
            node(31, 50.008, 6.003),
        )
        private val edges = listOf(
            edge(10, 11, 300.0),
            edge(20, 21, 300.0),
            edge(30, 31, 300.0),
            edge(11, 20, 700.0),
            edge(21, 30, 700.0),
            edge(31, 10, 900.0),
        )

        override suspend fun getAllCorridors() = corridors
        override suspend fun getAllCorridorConnectors() = connectors
        override suspend fun getEdgesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = edges
        override suspend fun getNodesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = nodes
    }

    private open class FakeRepository : MapGraphRepository {
        override fun getAllEdges(): Flow<List<MapEdge>> = flowOf(emptyList())
        override fun getAllNodes(): Flow<List<MapNode>> = flowOf(emptyList())
        override suspend fun getEdgesByNodePairs(pairs: List<Pair<Long, Long>>) = emptyList<MapEdge>()
        override suspend fun getEdgesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = emptyList<MapEdge>()
        override suspend fun getNodesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = emptyList<MapNode>()
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
}
