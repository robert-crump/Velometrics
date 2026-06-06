package com.velometrics.app.data.repository

import com.velometrics.app.data.local.dao.MapEdgeDao
import com.velometrics.app.data.local.dao.MapNodeDao
import com.velometrics.app.data.local.dao.PoiDao
import com.velometrics.app.data.local.entity.*
import com.velometrics.app.domain.model.GraphMetadata
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.model.Poi
import com.velometrics.app.domain.repository.MapGraphRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MapGraphRepositoryImpl @Inject constructor(
    private val nodeDao: MapNodeDao,
    private val edgeDao: MapEdgeDao,
    private val poiDao: PoiDao
) : MapGraphRepository {

    override fun getAllEdges(): Flow<List<MapEdge>> =
        edgeDao.getAll().map { it.map { e -> e.toDomain() } }

    override fun getAllNodes(): Flow<List<MapNode>> =
        nodeDao.getAll().map { it.map { n -> n.toDomain() } }

    override fun getTraversedEdges(): Flow<List<MapEdge>> =
        edgeDao.getTraversed().map { it.map { e -> e.toDomain() } }

    override fun getUntraversedEdges(): Flow<List<MapEdge>> =
        edgeDao.getUntraversed().map { it.map { e -> e.toDomain() } }

    override fun getAllPois(): Flow<List<Poi>> =
        poiDao.getAll().map { list -> list.mapNotNull { it.toDomain() } }

    override fun getMetadata(): GraphMetadata? = null

    override suspend fun loadGraph(nodes: List<MapNode>, edges: List<MapEdge>, metadata: GraphMetadata) {
        // no-op: data is always available from Room
    }

    override suspend fun loadPois(pois: List<Poi>) {
        // no-op: data is always available from Room
    }

    override fun isLoaded(): Boolean = true
}
