package com.velometrics.app.fakes

import com.velometrics.app.domain.model.FlowSegment
import com.velometrics.app.domain.model.GraphMetadata
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.model.Poi
import com.velometrics.app.domain.repository.MapGraphRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeMapGraphRepository : MapGraphRepository {
    override fun getAllEdges(): Flow<List<MapEdge>> = flowOf(emptyList())
    override fun getAllNodes(): Flow<List<MapNode>> = flowOf(emptyList())
    override suspend fun getEdgesByNodePairs(pairs: List<Pair<Long, Long>>) = emptyList<MapEdge>()
    override suspend fun getEdgesNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) = emptyList<MapEdge>()
    override suspend fun getNodesNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) = emptyList<MapNode>()
    override suspend fun getNodesByIds(vararg ids: Long) = emptyList<MapNode>()
    override fun getTraversedEdges(): Flow<List<MapEdge>> = flowOf(emptyList())
    override fun getUntraversedEdges(): Flow<List<MapEdge>> = flowOf(emptyList())
    override fun getAllPois(): Flow<List<Poi>> = flowOf(emptyList())
    override suspend fun getMetadata(): GraphMetadata? = null
    override suspend fun getFlowSegmentsNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) = emptyList<FlowSegment>()
}
