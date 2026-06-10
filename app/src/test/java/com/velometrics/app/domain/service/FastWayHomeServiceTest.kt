package com.velometrics.app.domain.service

import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.domain.model.GraphMetadata
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.model.Poi
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.util.GeoUtils
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class FastWayHomeServiceTest {

    private fun edge(
        from: MapNode,
        to: MapNode,
        isTraversed: Boolean,
        speedMedian: Double? = null
    ): MapEdge {
        val lengthM = GeoUtils.haversineDistance(from.lat, from.lon, to.lat, to.lon)
        return MapEdge(
            fromNode = from.id, toNode = to.id,
            lengthM = lengthM, highway = "residential", name = null,
            isTraversed = isTraversed, geometryEncoded = "",
            speedMedian = speedMedian, speedMean = speedMedian,
            speedCount = if (speedMedian != null) 10 else null,
            speedP25 = speedMedian?.minus(5.0), speedP75 = speedMedian?.plus(5.0), speedP90 = speedMedian?.plus(8.0),
            powerMedian = if (speedMedian != null) 180.0 else null,
            powerMean = if (speedMedian != null) 180.0 else null,
            powerCount = if (speedMedian != null) 10 else null,
            powerP25 = if (speedMedian != null) 160.0 else null,
            powerP75 = if (speedMedian != null) 200.0 else null,
            powerP90 = if (speedMedian != null) 220.0 else null,
            slopePercent = 0.0, traversalCount = if (isTraversed) 5 else 0, lastTraversal = null, timeOfDayDist = null,
            stopCount = null, avgStopDurationS = null, stopProbability = null, estimatedStopTimeS = null
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
            override fun getMetadata(): GraphMetadata? = null
            override suspend fun loadGraph(nodes: List<MapNode>, edges: List<MapEdge>, metadata: GraphMetadata) {}
            override suspend fun loadPois(pois: List<Poi>) {}
            override fun isLoaded(): Boolean = true
        }

    private fun fakeSettings(homeLat: Double, homeLon: Double): UserSettingsRepository {
        val settings = mockk<UserSettingsRepository>()
        every { settings.homeLat } returns flowOf(homeLat)
        every { settings.homeLon } returns flowOf(homeLon)
        return settings
    }

    @Test
    fun `finds a concrete path home with plausible slow avg fast estimates`() = runTest {
        // A short north-bound chain: start -> N0 -> N1 -> N2 -> N3 (home)
        val n0 = MapNode(0, 50.7800, 6.0800)
        val n1 = MapNode(1, 50.7810, 6.0800)
        val n2 = MapNode(2, 50.7820, 6.0800)
        val n3 = MapNode(3, 50.7830, 6.0800)

        // Real traversed stats for the first two edges; the last is untraversed and must be
        // filled in by EdgeStatsEstimator (falling back to the flat 15 km/h estimate, since
        // no nearby traversed edge shares its bearing within range).
        val edge0 = edge(n0, n1, isTraversed = true, speedMedian = 20.0)
        val edge1 = edge(n1, n2, isTraversed = true, speedMedian = 25.0)
        val edge2 = edge(n2, n3, isTraversed = false)

        val repository = fakeRepository(listOf(edge0, edge1, edge2), listOf(n0, n1, n2, n3))
        val service = FastWayHomeService(
            repository = repository,
            edgeStatsEstimator = EdgeStatsEstimator(repository),
            userSettingsRepository = fakeSettings(homeLat = n3.lat, homeLon = n3.lon)
        )

        val result = service.findFastWayHome(LatLng(n0.lat, n0.lon))

        assertNotNull(result)
        requireNotNull(result)
        assertEquals(listOf(edge0, edge1, edge2), result.path)
        assertTrue(result.anySegmentsEstimated)

        // Slow <= Avg <= Fast: lower percentile speeds yield longer ride times
        assertTrue(result.slow.durationSec >= result.avg.durationSec)
        assertTrue(result.avg.durationSec >= result.fast.durationSec)
        assertTrue(result.avg.durationSec > 0.0)
        assertTrue(result.avg.avgPowerW > 0.0)
    }

    @Test
    fun `returns null when no route home exists in a disconnected graph`() = runTest {
        // Component A (near start) and component B (near home) share no nodes/edges
        val a0 = MapNode(0, 50.7800, 6.0800)
        val a1 = MapNode(1, 50.7810, 6.0800)
        val b0 = MapNode(10, 50.9000, 6.0800)
        val b1 = MapNode(11, 50.9010, 6.0800)

        val edgeA = edge(a0, a1, isTraversed = true, speedMedian = 20.0)
        val edgeB = edge(b0, b1, isTraversed = true, speedMedian = 20.0)

        val repository = fakeRepository(listOf(edgeA, edgeB), listOf(a0, a1, b0, b1))
        val service = FastWayHomeService(
            repository = repository,
            edgeStatsEstimator = EdgeStatsEstimator(repository),
            userSettingsRepository = fakeSettings(homeLat = b1.lat, homeLon = b1.lon)
        )

        val result = service.findFastWayHome(LatLng(a0.lat, a0.lon))

        assertNull(result)
    }
}
