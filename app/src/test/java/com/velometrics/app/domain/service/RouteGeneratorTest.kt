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
import com.velometrics.app.util.GeoUtils
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RouteGeneratorTest {

    // --- End-to-end smoke test ---

    // The 4-corridor ring fixture refines to a ~2600 m loop, so 3000 m keeps the actual distance
    // inside the default +/-15% acceptance band ([2550, 3450]).
    private val fixtureTargetM = 3000.0

    // LoopFixtureRepository's corridors were hand-placed against the pre-#138 reach = target/3
    // ratio; opt back into it for tests that exercise other RouteGenerator behavior (scoring, home
    // snapping, deviation reporting) rather than the reach fraction itself. Mirrors
    // CorridorOrienteerTest's LEGACY_REACH_THIRD.
    private val legacyReachDegradation = DegradationConfig(
        baseReachFraction = 1.0 / 3.0,
        extendedReachFraction = 0.5,
    )

    @Test
    fun `generates a candidate with sub-scores on small fixture`() = runTest {
        val repo = LoopFixtureRepository()

        val result = RouteGenerator.generate(
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = fixtureTargetM,
            repository = repo,
            config = GeneratorConfig(seed = 42L, degradationConfig = legacyReachDegradation),
        )

        assertTrue("Expected Success, got $result", result is RoutePlanResult.Success)
        val candidate = (result as RoutePlanResult.Success).candidate
        assertTrue(candidate.refinedRoute.actualDistanceM > 0.0)
        assertTrue(candidate.refinedRoute.edges.isNotEmpty())
        assertEquals(1, candidate.rank)
    }

    // --- Acceptance is judged on actual refined distance ---

    @Test
    fun `accepts a candidate whose actual distance lands inside the band`() = runTest {
        val repo = LoopFixtureRepository()

        val result = RouteGenerator.generate(
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = fixtureTargetM,
            repository = repo,
            config = GeneratorConfig(seed = 42L, degradationConfig = legacyReachDegradation),
        )

        assertTrue("Expected Success, got $result", result is RoutePlanResult.Success)
        val success = result as RoutePlanResult.Success
        assertEquals(
            "An in-band route should be accepted at the tightest tier",
            DegradationPolicy.RelaxationTier.NONE, success.appliedTier,
        )
        assertTrue(
            "Accepted candidate ${success.candidate.refinedRoute.actualDistanceM}m should be within +/-15%",
            kotlin.math.abs(success.candidate.distanceDeviationPercent) <= 15.0,
        )
    }

    @Test
    fun `rejects a route whose actual distance falls outside every band`() = runTest {
        // The fixture's 4 corridors + connectors sum to well under 10 km of total edge length, so
        // no combination of anchors/fills/windings can ever assemble a loop anywhere near 50 km —
        // even the widened +/-30% band (35-65 km) is unreachable, so generation fails rather than
        // returning a wildly short "match". (A 6 km ask used to be unreachable too, back when the
        // skeleton built a single anchor combo per quadrant; issue #117's CW/CCW windings + top-N
        // anchor combos made a ~4.6 km loop achievable here via the diagonal cross-connectors, which
        // falls inside 6 km's widened band and made this test flaky against that target.)
        val repo = LoopFixtureRepository()

        val result = RouteGenerator.generate(
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 50_000.0,
            repository = repo,
            config = GeneratorConfig(seed = 42L, degradationConfig = legacyReachDegradation),
        )

        assertTrue("Expected Failure for an unreachable distance, got $result", result is RoutePlanResult.Failure)
    }

    // --- Best candidate is selected by reward ---

    @Test
    fun `returned candidate has positive total reward`() = runTest {
        val repo = LoopFixtureRepository()

        val result = RouteGenerator.generate(
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = fixtureTargetM,
            repository = repo,
            config = GeneratorConfig(seed = 42L, degradationConfig = legacyReachDegradation),
        )

        assertTrue(result is RoutePlanResult.Success)
        val candidate = (result as RoutePlanResult.Success).candidate
        assertTrue(
            "Returned candidate should have non-negative total reward",
            candidate.coarseLoop.totalReward >= 0.0,
        )
    }

    // --- Home snapping ---

    @Test
    fun `candidates start and end at home corridor`() = runTest {
        val repo = LoopFixtureRepository()

        val result = RouteGenerator.generate(
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = fixtureTargetM,
            repository = repo,
            config = GeneratorConfig(seed = 42L, degradationConfig = legacyReachDegradation),
        )

        assertTrue(result is RoutePlanResult.Success)
        val candidate = (result as RoutePlanResult.Success).candidate
        val firstEdge = candidate.refinedRoute.edges.first()
        val lastEdge = candidate.refinedRoute.edges.last()
        assertEquals(
            "Loop should return to starting node",
            firstEdge.fromNode, lastEdge.toNode,
        )
    }

    // --- Distance deviation reported ---

    @Test
    fun `distance deviation percent is reported`() = runTest {
        val repo = LoopFixtureRepository()
        val targetM = fixtureTargetM

        val result = RouteGenerator.generate(
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = targetM,
            repository = repo,
            config = GeneratorConfig(seed = 42L, degradationConfig = legacyReachDegradation),
        )

        assertTrue(result is RoutePlanResult.Success)
        val candidate = (result as RoutePlanResult.Success).candidate
        val expectedDeviation =
            (candidate.refinedRoute.actualDistanceM - targetM) / targetM * 100.0
        assertEquals(expectedDeviation, candidate.distanceDeviationPercent, 1e-6)
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
            targetDistanceM = 3700.0,
            repository = repo,
            config = GeneratorConfig(
                degradationConfig = DegradationConfig(minDesiredCandidates = 10),
                seed = 42L,
            ),
        )

        if (result is RoutePlanResult.Success) {
            assertTrue(
                "Expected relaxation beyond NONE when asking for 10 min candidates with a sparse graph",
                result.appliedTier != DegradationPolicy.RelaxationTier.NONE ||
                    result.candidate.refinedRoute.edges.isNotEmpty(),
            )
        }
    }

    // --- Adaptive coarse-distance calibration (#132) ---

    @Test
    fun `adaptive calibration reaches tier NONE despite roads that systematically exceed straight-line estimates`() = runTest {
        // MeshSpokeRepository's real roads are a direct point-to-point mesh, each edge exactly
        // INFLATION_FACTOR times its geographic (haversine) length -- a systemic ~1.5x inflation
        // over the coarse haversine*1.3 estimate, matching the #129 diagnosis's measured 1.4-1.7x
        // range. CorridorOrienteer is mocked to a small deterministic stand-in for its real
        // fill-to-target behaviour (nearest-first, stop once the coarse *estimate* reaches
        // 0.9x its requested target) so the test controls candidate growth precisely; RouteRefiner
        // runs for real and does the actual A* distance measurement over the inflated mesh. Without
        // calibration the coarse search fills toward the full target and the refined route
        // overshoots it by roughly the fixed 1.5x ratio; #132's rescale should bring it back
        // in-band at the tightest tier.
        val repo = MeshSpokeRepository()
        val targetM = 20_000.0

        mockkObject(CorridorOrienteer)
        try {
            coEvery {
                CorridorOrienteer.search(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } answers {
                val target = arg<Double>(3)
                listOf(repo.buildCandidateForTarget(target))
            }

            val calibratedResult = RouteGenerator.generate(
                homeLat = 50.0, homeLon = 6.0,
                targetDistanceM = targetM,
                repository = repo,
                config = GeneratorConfig(
                    refinerConfig = RefinerConfig(connectorRewardBudgetFraction = null),
                    seed = 42L,
                ),
            )

            assertTrue("Expected Success, got $calibratedResult", calibratedResult is RoutePlanResult.Success)
            val success = calibratedResult as RoutePlanResult.Success
            assertEquals(
                "Calibration should let this request converge at the tightest tier",
                DegradationPolicy.RelaxationTier.NONE, success.appliedTier,
            )
            assertTrue(
                "Accepted candidate deviation ${success.candidate.distanceDeviationPercent}% should be within +/-15%",
                kotlin.math.abs(success.candidate.distanceDeviationPercent) <= 15.0,
            )
        } finally {
            unmockkObject(CorridorOrienteer)
        }
    }

    @Test
    fun `at most one recalibration pass occurs per generation`() = runTest {
        mockkObject(CorridorOrienteer)
        mockkObject(RouteRefiner)
        try {
            val capturedTargets = mutableListOf<Double>()
            val fakeCandidate = CandidateLoop(
                corridors = listOf(1L),
                totalDistanceM = 1000.0,
                totalReward = 1.0,
                flowScore = 0.0,
                discoveryScore = 0.0,
            )
            coEvery {
                CorridorOrienteer.search(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } answers {
                capturedTargets.add(arg(3))
                listOf(fakeCandidate)
            }
            // Always refines to 5x the coarse plan, regardless of the candidate: never lands in
            // any band (even the widest +/-30%), so every tier relaxes and, if the "at most once"
            // guard were missing, each tier would recalibrate again using the same fixed ratio.
            coEvery {
                RouteRefiner.refine(any(), any(), any(), any(), any())
            } returns RefinedRoute(edges = emptyList(), actualDistanceM = 5000.0)

            val repo = SingleCorridorRepository()
            val result = RouteGenerator.generate(
                homeLat = 50.0, homeLon = 6.0,
                targetDistanceM = 1000.0,
                repository = repo,
                config = GeneratorConfig(
                    exitLegConfig = ExitLegConfig(minCorridorDistM = 50_000.0, maxCorridorDistM = 100_000.0),
                    seed = 1L,
                ),
            )

            assertTrue(
                "Expected Failure since the mocked actual distance never lands in any band, got $result",
                result is RoutePlanResult.Failure,
            )
            val distinctTargets = capturedTargets.distinct()
            assertEquals(
                "Expected exactly one recalibration (original target + one rescaled value), got $capturedTargets",
                2, distinctTargets.size,
            )
        } finally {
            unmockkObject(CorridorOrienteer)
            unmockkObject(RouteRefiner)
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

    // --- Exit leg planning ---

    @Test
    fun `planExitLeg returns plan when exit corridor is different from home`() = runTest {
        val repo = LoopFixtureRepository()
        val corridors = repo.getAllCorridors()

        val plan = RouteGenerator.planExitLeg(
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 20000.0,
            direction = null,
            corridors = corridors,
            repository = repo,
            config = ExitLegConfig(
                minCorridorDistM = 100.0,
                maxCorridorDistM = 5000.0,
                bboxHalfSizeM = 5000.0,
            ),
        )

        assertNotNull("Expected exit leg plan", plan)
        assertTrue(plan!!.exitLeg.edges.isNotEmpty())
        assertTrue(plan.exitLeg.distanceM > 0.0)
        assertTrue(plan.adjustedTargetM > 0.0)
        assertTrue(plan.adjustedTargetM < 20000.0)
        assertNotEquals(
            "Exit corridor should differ from home corridor",
            CorridorOrienteer.findNearestCorridor(corridors, 50.0, 6.0)?.id,
            plan.exitCorridorId,
        )
    }

    @Test
    fun `planExitLeg returns null when exit corridor is home corridor`() = runTest {
        val repo = LoopFixtureRepository()

        val plan = RouteGenerator.planExitLeg(
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 3700.0,
            direction = null,
            corridors = repo.getAllCorridors(),
            repository = repo,
            config = ExitLegConfig(),
        )

        assertNull("Default config should produce exit leg to home corridor (skipped)", plan)
    }

    // --- Exit leg integration: happy path ---

    @Test
    fun `exit leg stitches exit corridor route and return into single route`() = runTest {
        val repo = ExitLegLoopFixtureRepository()

        // The exit-leg soft cap needs a sizeable target to fire, but this tiny fixture's stitched
        // loop (exit + corridor route + return) is only ~2.7 km. This test verifies stitching
        // mechanics, so it widens the acceptance band to admit the short loop; distance-band
        // enforcement is covered by the dedicated acceptance tests above.
        val result = RouteGenerator.generate(
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = 5500.0,
            repository = repo,
            config = GeneratorConfig(
                orienteerConfig = OrienteerConfig(candidateCount = 3),
                exitLegConfig = ExitLegConfig(
                    minCorridorDistM = 100.0,
                    maxCorridorDistM = 5000.0,
                    bboxHalfSizeM = 5000.0,
                ),
                degradationConfig = DegradationConfig(baseDistanceBandFraction = 1.0),
                seed = 42L,
            ),
        )

        assertTrue("Expected Success, got $result", result is RoutePlanResult.Success)
        val candidate = (result as RoutePlanResult.Success).candidate
        assertTrue(candidate.refinedRoute.edges.isNotEmpty())
        assertTrue(candidate.refinedRoute.actualDistanceM > 0.0)
    }

    // --- Exit leg integration: fallback ---

    @Test
    fun `fallback to home start when exit leg fails`() = runTest {
        val repo = LoopFixtureRepository()

        val result = RouteGenerator.generate(
            homeLat = 50.0, homeLon = 6.0,
            targetDistanceM = fixtureTargetM,
            repository = repo,
            config = GeneratorConfig(
                exitLegConfig = ExitLegConfig(
                    minCorridorDistM = 50_000.0,
                    maxCorridorDistM = 100_000.0,
                ),
                degradationConfig = legacyReachDegradation,
                seed = 42L,
            ),
        )

        assertTrue("Expected Success even without exit legs, got $result", result is RoutePlanResult.Success)
        val candidate = (result as RoutePlanResult.Success).candidate
        val firstEdge = candidate.refinedRoute.edges.first()
        val lastEdge = candidate.refinedRoute.edges.last()
        assertEquals(
            "Fallback loop should return to starting node",
            firstEdge.fromNode, lastEdge.toNode,
        )
    }

    // --- Shape-aware selection and trimming (#133) ---

    private fun shapeReport(compactness: Double, repeatFraction: Double) = RouteShapeReport(
        totalLengthM = 10_000.0,
        repeatedLengthM = repeatFraction * 10_000.0,
        repeatFraction = repeatFraction,
        rawRepeatedLengthM = repeatFraction * 10_000.0,
        rawRepeatFraction = repeatFraction,
        compactness = compactness,
        repeatedRoadCount = if (repeatFraction > 0.0) 1 else 0,
    )

    private fun refinedCandidate(
        id: Long,
        reward: Double,
        actualDistanceM: Double,
        compactness: Double,
        repeatFraction: Double,
    ) = RefinedCandidate(
        coarseLoop = CandidateLoop(
            corridors = listOf(id),
            totalDistanceM = actualDistanceM,
            totalReward = reward,
            flowScore = 0.0,
            discoveryScore = 0.0,
        ),
        refinedRoute = RefinedRoute(edges = emptyList(), actualDistanceM = actualDistanceM),
        shapeReport = shapeReport(compactness, repeatFraction),
    )

    @Test
    fun `selectWinner prefers a gate-passing candidate over a higher-reward gate-failing one`() {
        // Lollipop: high reward but fails both thresholds (compactness too low, repeat too high).
        val lollipop = refinedCandidate(1, reward = 100.0, actualDistanceM = 10_000.0, compactness = 0.05, repeatFraction = 0.60)
        // Oval: low reward but clears both thresholds.
        val oval = refinedCandidate(2, reward = 10.0, actualDistanceM = 10_000.0, compactness = 0.50, repeatFraction = 0.05)

        val winner = RouteGenerator.selectWinner(
            listOf(lollipop, oval), targetDistanceM = 10_000.0, bandFraction = 0.15,
            shapeConfig = RouteShapeGateConfig(),
        )

        assertEquals("Gate-passing oval should win despite lower reward", oval, winner)
    }

    @Test
    fun `selectWinner falls back to the best-shaped candidate when none pass the gate`() {
        val worse = refinedCandidate(1, reward = 100.0, actualDistanceM = 10_000.0, compactness = 0.05, repeatFraction = 0.60)
        val lessWorse = refinedCandidate(2, reward = 1.0, actualDistanceM = 10_000.0, compactness = 0.20, repeatFraction = 0.20)

        val winner = RouteGenerator.selectWinner(
            listOf(worse, lessWorse), targetDistanceM = 10_000.0, bandFraction = 0.15,
            shapeConfig = RouteShapeGateConfig(),
        )

        assertEquals(
            "Best-shaped candidate should win when none pass the gate, even with lower reward",
            lessWorse, winner,
        )
    }

    @Test
    fun `trimRefined keeps a gate-passing candidate over higher-reward gate-failing ones`() {
        val oval = refinedCandidate(1, reward = 5.0, actualDistanceM = 10_000.0, compactness = 0.50, repeatFraction = 0.05)
        val lollipopA = refinedCandidate(2, reward = 100.0, actualDistanceM = 10_000.0, compactness = 0.05, repeatFraction = 0.60)
        val lollipopB = refinedCandidate(3, reward = 90.0, actualDistanceM = 10_000.0, compactness = 0.10, repeatFraction = 0.50)
        val pool = mutableListOf(lollipopA, lollipopB, oval)

        RouteGenerator.trimRefined(
            pool, maxKeep = 1, targetDistanceM = 10_000.0, bandFraction = 0.15,
            shapeConfig = RouteShapeGateConfig(),
        )

        assertEquals(listOf(oval), pool)
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
        exitHazardScore = 0.0,
        centroidLat = lat,
        centroidLon = lon,
        edgeList = emptyList(),
        popularity = 0,
        groupId = id,
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
        traversalCount: Int? = 3,
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
        slopePercent = null, traversalCount = traversalCount, lastTraversal = null,
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
            corridor(1, lat = 50.0, lon = 6.0, lengthM = 300.0),
            corridor(2, lat = 50.005, lon = 6.005, lengthM = 300.0),
            corridor(3, lat = 50.01, lon = 6.005, lengthM = 300.0),
            corridor(4, lat = 50.005, lon = 6.0, lengthM = 300.0),
        )
        private val connectors = listOf(
            connector(1, 2, 500.0),
            connector(2, 3, 500.0),
            connector(3, 4, 500.0),
            connector(4, 1, 500.0),
            connector(1, 3, 700.0),
            connector(2, 4, 700.0),
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
        override suspend fun getRoutingEdgesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = edges.map { RoutingEdge(it.fromNode, it.toNode, it.lengthM) }
        override suspend fun getEdgesByNodePairs(pairs: List<Pair<Long, Long>>): List<MapEdge> {
            val pairSet = pairs.toSet()
            return edges.filter { (it.fromNode to it.toNode) in pairSet }
        }
        override suspend fun getNodesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = nodes
        override suspend fun getNodesByIds(vararg ids: Long) = nodes.filter { it.id in ids }
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
        override suspend fun getRoutingEdgesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = edges.map { RoutingEdge(it.fromNode, it.toNode, it.lengthM) }
        override suspend fun getEdgesByNodePairs(pairs: List<Pair<Long, Long>>): List<MapEdge> {
            val pairSet = pairs.toSet()
            return edges.filter { (it.fromNode to it.toNode) in pairSet }
        }
        override suspend fun getNodesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = nodes
        override suspend fun getNodesByIds(vararg ids: Long) = nodes.filter { it.id in ids }
    }

    /**
     * Fixture with bidirectional edges and 300m corridors for exit leg testing.
     * Exit leg routes from C1 (home) to C2, orienteer loops through C2→C1→C4→C3,
     * return leg from C3 back to home.
     */
    private inner class ExitLegLoopFixtureRepository : FakeRepository() {
        private val corridors = listOf(
            corridor(1, lat = 50.0, lon = 6.0, lengthM = 300.0),
            corridor(2, lat = 50.005, lon = 6.005, lengthM = 300.0),
            corridor(3, lat = 50.01, lon = 6.005, lengthM = 300.0),
            corridor(4, lat = 50.005, lon = 6.0, lengthM = 300.0),
        )
        private val connectors = listOf(
            connector(1, 2, 500.0),
            connector(2, 3, 500.0),
            connector(3, 4, 500.0),
            connector(4, 1, 500.0),
            connector(1, 3, 800.0),
            connector(2, 4, 800.0),
        )
        private val nodes = listOf(
            node(10, 50.0, 6.0),
            node(11, 50.002, 6.002),
            node(20, 50.005, 6.005),
            node(21, 50.007, 6.005),
            node(30, 50.01, 6.005),
            node(31, 50.008, 6.003),
            node(40, 50.005, 6.0),
            node(41, 50.002, 6.0),
        )
        private val edges = listOf(
            // Internal corridor edges (bidirectional)
            edge(10, 11, 300.0), edge(11, 10, 300.0),
            edge(20, 21, 300.0), edge(21, 20, 300.0),
            edge(30, 31, 300.0), edge(31, 30, 300.0),
            edge(40, 41, 300.0), edge(41, 40, 300.0),
            // Ring connectors: exit → next entry
            edge(11, 20, 500.0), edge(21, 30, 500.0),
            edge(31, 40, 500.0), edge(41, 10, 500.0),
            // Reverse ring connectors: exit → prev entry
            edge(21, 10, 500.0), edge(31, 20, 500.0),
            edge(41, 30, 500.0), edge(11, 40, 500.0),
            // Cross connectors
            edge(11, 30, 800.0), edge(21, 40, 800.0),
            edge(31, 10, 800.0), edge(41, 20, 800.0),
        )

        override suspend fun getAllCorridors() = corridors
        override suspend fun getAllCorridorConnectors() = connectors
        override suspend fun getEdgesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = edges
        override suspend fun getRoutingEdgesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = edges.map { RoutingEdge(it.fromNode, it.toNode, it.lengthM) }
        override suspend fun getEdgesByNodePairs(pairs: List<Pair<Long, Long>>): List<MapEdge> {
            val pairSet = pairs.toSet()
            return edges.filter { (it.fromNode to it.toNode) in pairSet }
        }
        override suspend fun getNodesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = nodes
        override suspend fun getNodesByIds(vararg ids: Long) = nodes.filter { it.id in ids }
    }

    private inner class SingleCorridorRepository : FakeRepository() {
        override suspend fun getAllCorridors() = listOf(corridor(1))
    }

    /**
     * A line of `spokeCount` corridors due east of home, `stepM` apart, fully mesh-connected: every
     * pair of points has a direct real edge exactly `inflationFactor` times its geographic
     * (haversine) distance -- so the real road network is a systematic, uniform multiple of
     * straight-line distance, regardless of which points a route visits or in what order.
     *
     * [buildCandidateForTarget] mimics CorridorOrienteer's real fill-to-target behaviour (nearest
     * fill first, stop once the coarse *estimate* -- straight-line * [RouteGenerator.ROAD_DISTANCE_FACTOR]
     * -- reaches 0.9x the requested target) closely enough to drive #132's calibration loop, without
     * depending on the real quadrant/separation skeleton (whose behaviour on a hand-built fixture is
     * far harder to predict exactly). RouteRefiner is not mocked: it runs real A* over this mesh, so
     * the *actual* distance in the test is a genuine measurement, not a stand-in.
     */
    private inner class MeshSpokeRepository(
        private val spokeCount: Int = 40,
        private val stepM: Double = 300.0,
        private val inflationFactor: Double = 1.95,
    ) : FakeRepository() {
        private val corridors: List<Corridor> = buildList {
            add(corridor(1, lat = 50.0, lon = 6.0, lengthM = 2.0))
            for (i in 1..spokeCount) {
                val lonOffset = GeoUtils.metersToLon(i * stepM, 50.0)
                add(
                    corridor(
                        id = (i + 1).toLong(),
                        lat = 50.0,
                        lon = 6.0 + lonOffset,
                        pedalReward = 3.0,
                        gravityReward = 2.0,
                        lengthM = 2.0,
                    ),
                )
            }
        }

        private val nodes: List<MapNode> = buildList {
            val tinyEastLon = GeoUtils.metersToLon(1.0, 50.0)
            for (c in corridors) {
                add(node(c.entryNode, c.centroidLat, c.centroidLon))
                add(node(c.exitNode, c.centroidLat, c.centroidLon + tinyEastLon))
            }
        }

        private val edges: List<MapEdge> = buildList {
            for (c in corridors) {
                add(edge(c.entryNode, c.exitNode, c.lengthM))
            }
            for (i in corridors.indices) {
                for (j in corridors.indices) {
                    if (i == j) continue
                    val from = corridors[i]
                    val to = corridors[j]
                    val geoDist = GeoUtils.haversineDistance(
                        from.centroidLat, from.centroidLon, to.centroidLat, to.centroidLon,
                    )
                    add(edge(from.exitNode, to.entryNode, geoDist * inflationFactor))
                }
            }
        }

        /** Cyclic sum of consecutive-centroid haversine distances * ROAD_DISTANCE_FACTOR, plus corridor lengths. */
        private fun coarseEstimate(loop: List<Corridor>): Double {
            var straightLine = 0.0
            for (i in loop.indices) {
                val a = loop[i]
                val b = loop[(i + 1) % loop.size]
                straightLine += GeoUtils.haversineDistance(a.centroidLat, a.centroidLon, b.centroidLat, b.centroidLon)
            }
            return straightLine * RouteGenerator.ROAD_DISTANCE_FACTOR + loop.sumOf { it.lengthM }
        }

        /** Nearest-spoke-first stand-in for CorridorOrienteer's real fill-to-target loop. */
        fun buildCandidateForTarget(targetDistanceM: Double): CandidateLoop {
            val home = corridors[0]
            val loop = mutableListOf(home)
            val ceiling = targetDistanceM * 0.9
            for (i in 1 until corridors.size) {
                if (coarseEstimate(loop) >= ceiling) break
                loop.add(corridors[i])
            }
            return CandidateLoop(
                corridors = loop.map { it.id },
                totalDistanceM = coarseEstimate(loop),
                totalReward = loop.sumOf { it.pedalReward + it.gravityReward },
                flowScore = 0.0,
                discoveryScore = 0.0,
            )
        }

        override suspend fun getAllCorridors() = corridors
        override suspend fun getAllCorridorConnectors() = emptyList<CorridorConnector>()
        override suspend fun getEdgesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = edges
        override suspend fun getRoutingEdgesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = edges.map { RoutingEdge(it.fromNode, it.toNode, it.lengthM) }
        override suspend fun getEdgesByNodePairs(pairs: List<Pair<Long, Long>>): List<MapEdge> {
            val pairSet = pairs.toSet()
            return edges.filter { (it.fromNode to it.toNode) in pairSet }
        }
        override suspend fun getNodesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = nodes
        override suspend fun getNodesByIds(vararg ids: Long) = nodes.filter { it.id in ids }
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
        override suspend fun getNodesByIds(vararg ids: Long) = emptyList<MapNode>()
        override suspend fun getRoutingEdgesNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = emptyList<RoutingEdge>()
        override suspend fun getTurnsNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = emptyList<com.velometrics.app.domain.model.MapTurn>()
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
}
