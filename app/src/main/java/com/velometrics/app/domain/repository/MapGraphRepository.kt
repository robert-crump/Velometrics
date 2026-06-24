package com.velometrics.app.domain.repository

import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.domain.model.CorridorConnector
import com.velometrics.app.domain.model.FlowSegment
import com.velometrics.app.domain.model.GraphMetadata
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.model.Poi
import kotlinx.coroutines.flow.Flow

data class RoutingEdge(val fromNode: Long, val toNode: Long, val lengthM: Double)

interface MapGraphRepository {
    fun getAllEdges(): Flow<List<MapEdge>>
    fun getAllNodes(): Flow<List<MapNode>>
    suspend fun getEdgesByNodePairs(pairs: List<Pair<Long, Long>>): List<MapEdge>
    suspend fun getEdgesNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<MapEdge>
    suspend fun getNodesNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<MapNode>
    suspend fun getNodesByIds(vararg ids: Long): List<MapNode>
    suspend fun getRoutingEdgesNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<RoutingEdge>
    fun getTraversedEdges(): Flow<List<MapEdge>>
    fun getUntraversedEdges(): Flow<List<MapEdge>>
    fun getAllPois(): Flow<List<Poi>>
    suspend fun getPoisInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Poi>
    suspend fun getMetadata(): GraphMetadata?
    suspend fun getAllCorridors(): List<Corridor>
    suspend fun getAllCorridorConnectors(): List<CorridorConnector>
    suspend fun getConnectorsForCorridor(corridorId: Long): List<CorridorConnector>
    suspend fun getFlowSegmentsNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<FlowSegment>
}
