package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.domain.model.CorridorConnector
import com.velometrics.app.domain.model.FlowSegment
import com.velometrics.app.domain.model.GraphMetadata
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.model.MapTurn
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

    // --- Reward detour tests ---

    @Test
    fun `reward detour chosen when within budget cap`() = runTest {
        val (edges, nodes) = rewardDetourGraph(detourLegLength = 190.0)
        val corridorMap = mapOf(
            1L to corridor(1, entryNode = 10, exitNode = 12, lat = 50.0, lon = 6.0),
            2L to corridor(2, entryNode = 12, exitNode = 10, lat = 50.002, lon = 6.002),
        )
        val candidate = CandidateLoop(
            corridors = listOf(1L, 2L), totalDistanceM = 500.0,
            totalReward = 5.0, flowScore = 5.0, discoveryScore = 0.0,
        )

        val result = RouteRefiner.refine(
            candidate, corridorMap, FakeRepository(edges, nodes),
            config = RefinerConfig(connectorRewardBudgetFraction = 1.5),
        )

        assertNotNull(result)
        // detour 10→13→12 (290m) has reward=5; shortest 10→11→12 (200m) has reward=0
        assertTrue(
            "Reward detour via node 13 should be chosen",
            result!!.edges.any { it.fromNode == 10L && it.toNode == 13L },
        )
    }

    @Test
    fun `reward detour rejected when it exceeds budget cap`() = runTest {
        val (edges, nodes) = rewardDetourGraph(detourLegLength = 210.0)
        val corridorMap = mapOf(
            1L to corridor(1, entryNode = 10, exitNode = 12, lat = 50.0, lon = 6.0),
            2L to corridor(2, entryNode = 12, exitNode = 10, lat = 50.002, lon = 6.002),
        )
        val candidate = CandidateLoop(
            corridors = listOf(1L, 2L), totalDistanceM = 500.0,
            totalReward = 5.0, flowScore = 5.0, discoveryScore = 0.0,
        )

        val result = RouteRefiner.refine(
            candidate, corridorMap, FakeRepository(edges, nodes),
            config = RefinerConfig(connectorRewardBudgetFraction = 1.5),
        )

        assertNotNull(result)
        // detour 10→13→12 (310m) exceeds 1.5×200=300m cap; must fall back to shortest
        assertTrue(
            "Shortest path via node 11 should be used when detour exceeds cap",
            result!!.edges.any { it.fromNode == 10L && it.toNode == 11L },
        )
    }

    @Test
    fun `flag off reproduces shortest path output`() = runTest {
        val (edges, nodes) = rewardDetourGraph(detourLegLength = 190.0)
        val corridorMap = mapOf(
            1L to corridor(1, entryNode = 10, exitNode = 12, lat = 50.0, lon = 6.0),
            2L to corridor(2, entryNode = 12, exitNode = 10, lat = 50.002, lon = 6.002),
        )
        val candidate = CandidateLoop(
            corridors = listOf(1L, 2L), totalDistanceM = 500.0,
            totalReward = 5.0, flowScore = 5.0, discoveryScore = 0.0,
        )

        val result = RouteRefiner.refine(
            candidate, corridorMap, FakeRepository(edges, nodes),
            config = RefinerConfig(connectorRewardBudgetFraction = null),
        )

        assertNotNull(result)
        // with flag off, detour (290m, reward=5) is ignored; shortest (200m) used
        assertTrue(
            "Shortest path via node 11 should be used when flag is off",
            result!!.edges.any { it.fromNode == 10L && it.toNode == 11L },
        )
    }

    @Test
    fun `connector path length never exceeds budget fraction of shortest`() = runTest {
        val (edges, nodes) = rewardDetourGraph(detourLegLength = 190.0)
        val corridorMap = mapOf(
            1L to corridor(1, entryNode = 10, exitNode = 12, lat = 50.0, lon = 6.0),
            2L to corridor(2, entryNode = 12, exitNode = 10, lat = 50.002, lon = 6.002),
        )
        val candidate = CandidateLoop(
            corridors = listOf(1L, 2L), totalDistanceM = 500.0,
            totalReward = 5.0, flowScore = 5.0, discoveryScore = 0.0,
        )
        val fraction = 1.5
        val shortestForwardLength = 200.0  // 10→11 (100m) + 11→12 (100m)

        val result = RouteRefiner.refine(
            candidate, corridorMap, FakeRepository(edges, nodes),
            config = RefinerConfig(connectorRewardBudgetFraction = fraction),
        )

        assertNotNull(result)
        // Collect only the forward segment (10→12); stop once we include the edge arriving at node 12.
        val fwdEdges = buildList {
            for (e in result!!.edges) {
                add(e)
                if (e.toNode == 12L) break
            }
        }
        val forwardLength = fwdEdges.sumOf { it.lengthM }
        assertTrue(
            "Forward connector length $forwardLength must not exceed ${shortestForwardLength * fraction}",
            forwardLength <= shortestForwardLength * fraction,
        )
    }

    // --- Hull-band cost tests (issue #131) ---

    @Test
    fun `hull bias prefers a perimeter path over an interior chord of comparable length`() = runTest {
        val (edges, nodes) = hullGridGraph()
        val corridorMap = mapOf(
            1L to corridor(1, entryNode = 1, exitNode = 2, lat = 50.0000, lon = 6.0000),
            2L to corridor(2, entryNode = 3, exitNode = 4, lat = 50.0000, lon = 6.0300),
            3L to corridor(3, entryNode = 5, exitNode = 6, lat = 50.0200, lon = 6.0300),
            4L to corridor(4, entryNode = 7, exitNode = 8, lat = 50.0200, lon = 6.0000),
        )
        val candidate = CandidateLoop(
            corridors = listOf(1L, 2L, 3L, 4L),
            totalDistanceM = 9000.0, totalReward = 0.0, flowScore = 0.0, discoveryScore = 0.0,
        )

        val result = RouteRefiner.refine(
            candidate, corridorMap, FakeRepository(edges, nodes),
            config = RefinerConfig(connectorRewardBudgetFraction = null),
        )

        assertNotNull(result)
        // Node 100 sits on the hull's south edge (ring); node 101 sits at the hull's interior
        // centroid. The interior chord via 101 is 8m shorter in raw length, but the hull-band
        // penalty on cutting deep into the interior should outweigh that small advantage.
        assertTrue(
            "Should trace the perimeter via node 100, not cut through the hull interior",
            result!!.edges.any { it.fromNode == 2L && it.toNode == 100L },
        )
        assertFalse(
            "Should not take the interior chord via node 101 despite its shorter raw length",
            result.edges.any { it.fromNode == 2L && it.toNode == 101L },
        )
    }

    @Test
    fun `reward detour pass never reuses a node pair already used earlier in the route`() = runTest {
        val (edges, nodes) = reusableRewardDetourGraph()
        val corridorMap = mapOf(
            1L to corridor(1, entryNode = 10, exitNode = 12, lat = 50.0, lon = 6.0),
            2L to corridor(2, entryNode = 12, exitNode = 10, lat = 50.002, lon = 6.002),
        )
        val candidate = CandidateLoop(
            corridors = listOf(1L, 2L), totalDistanceM = 500.0,
            totalReward = 5.0, flowScore = 5.0, discoveryScore = 0.0,
        )

        val result = RouteRefiner.refine(
            candidate, corridorMap, FakeRepository(edges, nodes),
            config = RefinerConfig(connectorRewardBudgetFraction = 1.5),
        )

        assertNotNull(result)
        // Segment 1 (10->12) takes the high-reward detour via node 13 (10->13->12).
        assertTrue(
            "First segment should still take its own reward detour via node 13",
            result!!.edges.any { it.fromNode == 10L && it.toNode == 13L },
        )
        // Segment 2 (12->10) could reuse edge 13->10 (also high-reward) for "free" extra reward,
        // but that edge pair was already used by segment 1 (10->13, reversed) - it must be
        // hard-excluded rather than allowed as an out-and-back.
        assertFalse(
            "Second segment must not reuse the already-used edge 13->10",
            result.edges.any { it.fromNode == 13L && it.toNode == 10L },
        )
        assertTrue(
            "Second segment should fall back to its shortest path via node 11",
            result.edges.any { it.fromNode == 12L && it.toNode == 11L },
        )
    }

    // --- Turn cost tests ---

    @Test
    fun `connector segment charges turn cost and prefers a longer detour over a hazardous turn`() = runTest {
        val (edges, nodes) = turnForkGraph()
        val corridorMap = mapOf(
            1L to corridor(1, entryNode = 1, exitNode = 2, lat = 50.0, lon = 6.0),
            2L to corridor(2, entryNode = 5, exitNode = 6, lat = 50.0, lon = 6.002),
        )
        val candidate = CandidateLoop(
            corridors = listOf(1L, 2L), totalDistanceM = 500.0,
            totalReward = 5.0, flowScore = 5.0, discoveryScore = 0.0,
        )

        val result = RouteRefiner.refine(
            candidate, corridorMap, FakeRepository(edges, nodes, turns = listOf(hazardousTurn())),
        )

        assertNotNull(result)
        // Direct 2->3->5 (200m) is shorter than detour 2->4->5 (300m), but the hazardous turn
        // charged at the connector segment (2,3,5) pushes it above the detour's total cost.
        assertTrue(
            "Should detour via node 4 to avoid the hazardous turn at node 3",
            result!!.edges.any { it.fromNode == 2L && it.toNode == 4L },
        )
        assertFalse(
            "Should not take the hazardous direct path through node 3",
            result.edges.any { it.fromNode == 2L && it.toNode == 3L },
        )
    }

    @Test
    fun `intra-corridor segment ignores turn cost even with a hazardous turn record present`() = runTest {
        val (edges, nodes) = turnForkGraph()
        val corridorMap = mapOf(
            1L to corridor(1, entryNode = 2, exitNode = 5, lat = 50.0, lon = 6.0),
        )
        val candidate = CandidateLoop(
            corridors = listOf(1L), totalDistanceM = 500.0,
            totalReward = 5.0, flowScore = 5.0, discoveryScore = 0.0,
        )

        val result = RouteRefiner.refine(
            candidate, corridorMap, FakeRepository(edges, nodes, turns = listOf(hazardousTurn())),
            config = RefinerConfig(connectorRewardBudgetFraction = null),
            closeLoop = false,
        )

        assertNotNull(result)
        // The 2->5 leg here is intra-corridor (waypoints entry->exit), so the same hazardous
        // turn at node 3 must NOT be charged; the shortest path via node 3 should still win.
        assertTrue(
            "Shortest path through node 3 should be used inside a corridor",
            result!!.edges.any { it.fromNode == 2L && it.toNode == 3L },
        )
    }

    // --- Test fixtures ---

    private fun edge(
        fromNode: Long,
        toNode: Long,
        lengthM: Double,
        highway: String = "residential",
        pedalFlowCount: Int? = null,
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
        pedalFlowCount = pedalFlowCount,
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
        exitHazardScore = 0.0,
        centroidLat = lat,
        centroidLon = lon,
        edgeList = emptyList(),
        popularity = 0,
        groupId = id,
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

    /**
     * Graph with two paths from node 10 to node 12:
     *   Short path:  10 → 11 → 12  (100 + 100 = 200m, reward = 0)
     *   Detour:      10 → 13 → 12  (100 + detourLegLength, reward = 5 on edge 10→13)
     * And symmetric return paths.
     */
    private fun rewardDetourGraph(detourLegLength: Double): Pair<List<MapEdge>, List<MapNode>> {
        val nodes = listOf(
            node(10, 50.0, 6.0),
            node(11, 50.001, 6.001),
            node(12, 50.002, 6.002),
            node(13, 50.001, 6.003),
        )
        val edges = listOf(
            edge(10, 11, 100.0),
            edge(11, 12, 100.0),
            edge(10, 13, 100.0, pedalFlowCount = 5),
            edge(13, 12, detourLegLength),
            edge(12, 11, 100.0),
            edge(11, 10, 100.0),
            edge(12, 13, detourLegLength),
            edge(13, 10, 100.0),
        )
        return edges to nodes
    }

    /**
     * Graph with two paths from node 2 to node 5:
     *   Direct: 2 → 3 → 5 (100 + 100 = 200m), with a sharp turn at node 3
     *           (north then east-ish) matching `hazardousTurn()`'s (2, 3, 5) key.
     *   Detour: 2 → 4 → 5 (150 + 150 = 300m), dead straight (no bearing change, no turn record).
     * Plus corridor approach/return edges (1→2, 5→6, 6→1, 5→2) so the fork can be placed on
     * either a connector or an intra-corridor segment depending on the corridor map used.
     */
    private fun turnForkGraph(): Pair<List<MapEdge>, List<MapNode>> {
        val nodes = listOf(
            node(1, 50.0, 5.999),
            node(2, 50.0, 6.0),
            node(3, 50.001, 6.0),
            node(4, 50.0, 6.001),
            node(5, 50.0, 6.002),
            node(6, 50.0, 6.003),
        )
        val edges = listOf(
            edge(1, 2, 100.0),
            edge(2, 3, 100.0),
            edge(3, 5, 100.0),
            edge(2, 4, 150.0),
            edge(4, 5, 150.0),
            edge(5, 6, 100.0),
            edge(6, 1, 100.0),
            edge(5, 2, 50.0),
        )
        return edges to nodes
    }

    /**
     * A ~2.1km x 2.2km rectangular loop (corridors at the 4 corners, entry=exit=corner so the
     * corridor waypoints + centroids resolve to exactly the 4 corners, giving a clean rectangular
     * hull well above the 1 km^2 degenerate-area floor). The south-edge connector (node 2 -> node 3)
     * has two paths of comparable length: via node 100 (exactly on the south-edge ring) and via
     * node 101 (the hull's interior centroid, 8m shorter in raw length).
     */
    private fun hullGridGraph(): Pair<List<MapEdge>, List<MapNode>> {
        val nodes = listOf(
            node(1, 50.0000, 6.0000), node(2, 50.0000, 6.0000),
            node(3, 50.0000, 6.0300), node(4, 50.0000, 6.0300),
            node(5, 50.0200, 6.0300), node(6, 50.0200, 6.0300),
            node(7, 50.0200, 6.0000), node(8, 50.0200, 6.0000),
            node(100, 50.0000, 6.0150),
            node(101, 50.0100, 6.0150),
        )
        val edges = listOf(
            edge(1, 2, 10.0),
            edge(2, 100, 1074.0),
            edge(100, 3, 1074.0),
            edge(2, 101, 1070.0),
            edge(101, 3, 1070.0),
            edge(3, 4, 10.0),
            edge(4, 5, 2226.0),
            edge(5, 6, 10.0),
            edge(6, 7, 2148.0),
            edge(7, 8, 10.0),
            edge(8, 1, 2226.0),
        )
        return edges to nodes
    }

    /**
     * Graph with two segments, each with its own shortest vs. reward-detour choice:
     *   Segment 1 (10 -> 12): shortest 10->11->12 (200m, reward 0); detour 10->13->12 (290m,
     *     reward 5 on 10->13) - within the 1.5x budget cap, so the detour is chosen.
     *   Segment 2 (12 -> 10): shortest 12->11->10 (200m, reward 0); "detour" 12->13->10 (290m,
     *     reward 5 on 13->10) - within budget, but 13->10 is the reverse of the 10->13 edge segment
     *     1 already used, so it must be hard-excluded rather than reused.
     */
    private fun reusableRewardDetourGraph(): Pair<List<MapEdge>, List<MapNode>> {
        val nodes = listOf(
            node(10, 50.0, 6.0),
            node(11, 50.001, 6.001),
            node(12, 50.002, 6.002),
            node(13, 50.001, 6.003),
        )
        val edges = listOf(
            edge(10, 11, 100.0),
            edge(11, 12, 100.0),
            edge(10, 13, 100.0, pedalFlowCount = 5),
            edge(13, 12, 190.0),
            edge(12, 11, 100.0),
            edge(11, 10, 100.0),
            edge(12, 13, 190.0),
            edge(13, 10, 100.0, pedalFlowCount = 5),
        )
        return edges to nodes
    }

    private fun hazardousTurn() = MapTurn(
        fromNode = 2L,
        junctionNode = 3L,
        toNode = 5L,
        hazardScore = 1.0,
        hazardSource = "measured",
        stopPenalty = 2.0,
        stopPenaltySource = "measured",
        brakingProbability = 1.0,
        medianKeDelta = 20.0,
        stopPenaltyConfidence = 1.0,
    )

    // --- Fake repositories ---

    private open class FakeRepository(
        private val edges: List<MapEdge> = emptyList(),
        private val nodes: List<MapNode> = emptyList(),
        private val turns: List<MapTurn> = emptyList(),
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
        ) = edges.map { RoutingEdge(it.fromNode, it.toNode, it.lengthM, RewardComposer.composeEdgeReward(it).total) }
        override suspend fun getTurnsNear(
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) = turns
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
