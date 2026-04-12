package com.cyclegraph.app.domain.repository

import com.cyclegraph.app.domain.model.GraphMetadata
import com.cyclegraph.app.domain.model.MapEdge
import com.cyclegraph.app.domain.model.MapNode
import com.cyclegraph.app.domain.model.Poi
import kotlinx.coroutines.flow.Flow

interface MapGraphRepository {
    fun getAllEdges(): Flow<List<MapEdge>>
    fun getAllNodes(): Flow<List<MapNode>>
    fun getTraversedEdges(): Flow<List<MapEdge>>
    fun getUntraversedEdges(): Flow<List<MapEdge>>
    fun getAllPois(): Flow<List<Poi>>
    fun getMetadata(): GraphMetadata?
    suspend fun loadGraph(nodes: List<MapNode>, edges: List<MapEdge>, metadata: GraphMetadata)
    suspend fun loadPois(pois: List<Poi>)
    fun isLoaded(): Boolean
}
