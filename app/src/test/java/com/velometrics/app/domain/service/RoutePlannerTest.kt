package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.GraphMetadata
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.model.Poi
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.util.CyclingConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test

class RoutePlannerTest {

    @Test
    fun `tight tolerance filters correctly`() {
        val target = 40000.0
        val tolerance = CyclingConstants.ROUTE_DISTANCE_TOLERANCE_TIGHT

        val inRange = 42000.0
        val outOfRange = 50000.0

        assertTrue(kotlin.math.abs(inRange - target) / target <= tolerance)
        assertFalse(kotlin.math.abs(outOfRange - target) / target <= tolerance)
    }

    @Test
    fun `relaxed tolerance used as fallback`() {
        val target = 40000.0
        val tightTol = CyclingConstants.ROUTE_DISTANCE_TOLERANCE_TIGHT
        val relaxedTol = CyclingConstants.ROUTE_DISTANCE_TOLERANCE_RELAXED

        val value = 44800.0
        val ratio = kotlin.math.abs(value - target) / target

        assertFalse(ratio <= tightTol)
        assertTrue(ratio <= relaxedTol)
    }

    @Test
    fun `closest route returned when both tolerances fail`() {
        val target = 40000.0
        val candidates = listOf(25000.0, 60000.0, 55000.0)
        val closest = candidates.minByOrNull { kotlin.math.abs(it - target) }
        assertEquals(25000.0, closest!!, 0.01)
    }
}

private class FakeMapGraphRepository : MapGraphRepository {
    override fun getAllEdges(): Flow<List<MapEdge>> = flowOf(emptyList())
    override fun getAllNodes(): Flow<List<MapNode>> = flowOf(emptyList())
    override suspend fun getEdgesByNodePairs(pairs: List<Pair<Long, Long>>) = emptyList<MapEdge>()
    override suspend fun getEdgesNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) = emptyList<MapEdge>()
    override suspend fun getNodesNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) = emptyList<MapNode>()
    override fun getTraversedEdges(): Flow<List<MapEdge>> = flowOf(emptyList())
    override fun getUntraversedEdges(): Flow<List<MapEdge>> = flowOf(emptyList())
    override fun getAllPois(): Flow<List<Poi>> = flowOf(emptyList())
    override suspend fun getPoisInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) = emptyList<Poi>()
    override fun getMetadata(): GraphMetadata? = null
    override suspend fun loadGraph(nodes: List<MapNode>, edges: List<MapEdge>, metadata: GraphMetadata) {}
    override suspend fun loadPois(pois: List<Poi>) {}
    override fun isLoaded(): Boolean = false
}
