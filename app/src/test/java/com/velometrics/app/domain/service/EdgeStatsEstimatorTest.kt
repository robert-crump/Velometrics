package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.GraphMetadata
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.model.Poi
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.util.CyclingConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class EdgeStatsEstimatorTest {

    private fun edge(
        id: Long,
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        speedMedian: Double,
        powerMedian: Double? = null,
        isTraversed: Boolean = true
    ): Pair<MapEdge, List<MapNode>> {
        val from = MapNode(id * 2, fromLat, fromLon)
        val to = MapNode(id * 2 + 1, toLat, toLon)
        val mapEdge = MapEdge(
            fromNode = from.id,
            toNode = to.id,
            lengthM = 50.0,
            highway = "residential",
            name = null,
            isTraversed = isTraversed,
            geometryEncoded = "",
            speedMedian = speedMedian, speedMean = speedMedian, speedCount = 10,
            speedP25 = speedMedian - 5.0, speedP75 = speedMedian + 5.0, speedP90 = speedMedian + 8.0,
            powerMedian = powerMedian, powerMean = powerMedian, powerCount = if (powerMedian != null) 10 else null,
            powerP25 = powerMedian?.minus(20.0), powerP75 = powerMedian?.plus(20.0), powerP90 = powerMedian?.plus(40.0),
            slopePercent = 0.0, traversalCount = 5, lastTraversal = null, timeOfDayDist = null,
            stopCount = null, avgStopDurationS = null, stopProbability = null, estimatedStopTimeS = null
        )
        return mapEdge to listOf(from, to)
    }

    private fun fakeRepository(edgesAndNodes: List<Pair<MapEdge, List<MapNode>>>): MapGraphRepository {
        val edges = edgesAndNodes.map { it.first }
        val nodes = edgesAndNodes.flatMap { it.second }
        return object : MapGraphRepository {
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
    }

    @Test
    fun `distance-weighted average favors the closer traversed edge`() = runTest {
        val queryPoint = LatLng(50.7800, 6.0800)

        // Both run roughly north (bearing ~0), matching the query bearing
        val near = edge(
            id = 1,
            fromLat = 50.78002, fromLon = 6.08000,
            toLat = 50.78010, toLon = 6.08000,
            speedMedian = 30.0
        )
        val far = edge(
            id = 2,
            fromLat = 50.78035, fromLon = 6.08000,
            toLat = 50.78043, toLon = 6.08000,
            speedMedian = 10.0
        )

        val estimator = EdgeStatsEstimator(fakeRepository(listOf(near, far)))
        val stats = estimator.estimateStatsNear(queryPoint, bearing = 0.0)

        assertTrue(stats.isEstimated)
        // Simple average would be 20; distance weighting should pull the result toward
        // the nearer edge's value (30)
        assertTrue("expected speedP50 ($stats.speedP50) closer to nearer edge's 30.0 than the midpoint 20.0",
            stats.speedP50 > 20.0)
    }

    @Test
    fun `falls back to conservative estimate when no traversed edges are nearby`() = runTest {
        val queryPoint = LatLng(50.7800, 6.0800)

        // Far outside the 50m search radius
        val farAway = edge(
            id = 1,
            fromLat = 50.79000, fromLon = 6.09000,
            toLat = 50.79010, toLon = 6.09000,
            speedMedian = 30.0
        )

        val estimator = EdgeStatsEstimator(fakeRepository(listOf(farAway)))
        val stats = estimator.estimateStatsNear(queryPoint, bearing = 0.0)

        assertEquals(CyclingConstants.EDGE_STATS_FALLBACK_SPEED_KMH, stats.speedP50, 0.0)
        assertEquals(CyclingConstants.EDGE_STATS_FALLBACK_SPEED_KMH, stats.speedP25, 0.0)
        assertEquals(CyclingConstants.EDGE_STATS_FALLBACK_SPEED_KMH, stats.speedP75, 0.0)
        assertNull(stats.powerP50)
        assertTrue(stats.isEstimated)
    }

    @Test
    fun `excludes nearby edges running in roughly the opposite direction`() = runTest {
        val queryPoint = LatLng(50.7800, 6.0800)

        // Runs north (bearing ~0) — matches the query bearing
        val sameDirection = edge(
            id = 1,
            fromLat = 50.78030, fromLon = 6.08000,
            toLat = 50.78038, toLon = 6.08000,
            speedMedian = 25.0
        )
        // Runs south (bearing ~180) and is closer to the query point — should be filtered out
        val oppositeDirection = edge(
            id = 2,
            fromLat = 50.78010, fromLon = 6.08000,
            toLat = 50.78002, toLon = 6.08000,
            speedMedian = 5.0
        )

        val estimator = EdgeStatsEstimator(fakeRepository(listOf(sameDirection, oppositeDirection)))
        val stats = estimator.estimateStatsNear(queryPoint, bearing = 0.0)

        // If the opposite-direction edge contributed, the weighted speed would be pulled
        // toward 5.0 (and it's the closer candidate, so would dominate an unfiltered average)
        assertEquals(25.0, stats.speedP50, 0.01)
    }
}
