package com.velometrics.app.data.repository

import com.velometrics.app.data.local.dao.MapEdgeDao
import com.velometrics.app.data.local.dao.MapMetadataDao
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
    private val poiDao: PoiDao,
    private val metadataDao: MapMetadataDao
) : MapGraphRepository {

    override fun getAllEdges(): Flow<List<MapEdge>> =
        edgeDao.getAll().map { it.map { e -> e.toDomain() } }

    override fun getAllNodes(): Flow<List<MapNode>> =
        nodeDao.getAll().map { it.map { n -> n.toDomain() } }

    override suspend fun getEdgesByNodePairs(pairs: List<Pair<Long, Long>>): List<MapEdge> {
        if (pairs.isEmpty()) return emptyList()
        val fromNodes = pairs.map { it.first }.distinct()
        val pairSet = pairs.toHashSet()
        return edgeDao.getEdgesByFromNodes(fromNodes)
            .filter { (it.fromNode to it.toNode) in pairSet }
            .map { it.toDomain() }
    }

    override suspend fun getEdgesNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<MapEdge> =
        edgeDao.getNear(minLat, maxLat, minLon, maxLon).map { it.toDomain() }

    override suspend fun getNodesNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<MapNode> =
        nodeDao.getNear(minLat, maxLat, minLon, maxLon).map { it.toDomain() }

    override fun getTraversedEdges(): Flow<List<MapEdge>> =
        edgeDao.getTraversed().map { it.map { e -> e.toDomain() } }

    override fun getUntraversedEdges(): Flow<List<MapEdge>> =
        edgeDao.getUntraversed().map { it.map { e -> e.toDomain() } }

    override fun getAllPois(): Flow<List<Poi>> =
        poiDao.getAll().map { list -> list.mapNotNull { it.toDomain() } }

    override suspend fun getPoisInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Poi> =
        poiDao.getInBoundingBox(minLat, maxLat, minLon, maxLon).mapNotNull { it.toDomain() }

    override suspend fun getMetadata(): GraphMetadata? = metadataDao.getMetadata()?.toDomain()
}
