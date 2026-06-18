package com.velometrics.app.domain.repository

import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.domain.model.CorridorConnector
import com.velometrics.app.domain.model.GraphMetadata
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.model.Poi
import kotlinx.coroutines.flow.Flow

interface MapGraphRepository {
    fun getAllEdges(): Flow<List<MapEdge>>
    fun getAllNodes(): Flow<List<MapNode>>
    suspend fun getEdgesByNodePairs(pairs: List<Pair<Long, Long>>): List<MapEdge>
    suspend fun getEdgesNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<MapEdge>
    suspend fun getNodesNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<MapNode>
    fun getTraversedEdges(): Flow<List<MapEdge>>
    fun getUntraversedEdges(): Flow<List<MapEdge>>
    fun getAllPois(): Flow<List<Poi>>
    suspend fun getPoisInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Poi>
    suspend fun getMetadata(): GraphMetadata?
    suspend fun getAllCorridors(): List<Corridor>
    suspend fun getAllCorridorConnectors(): List<CorridorConnector>
    suspend fun getConnectorsForCorridor(corridorId: Long): List<CorridorConnector>
}
