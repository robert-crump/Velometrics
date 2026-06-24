package com.velometrics.app.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import com.velometrics.app.data.local.entity.MapEdgeEntity
import kotlinx.coroutines.flow.Flow

data class RoutingEdgeRow(
    @ColumnInfo(name = "from_node") val fromNode: Long,
    @ColumnInfo(name = "to_node") val toNode: Long,
    @ColumnInfo(name = "length_m") val lengthM: Double,
)

@Dao
interface MapEdgeDao {
    @Query("SELECT * FROM map_edges")
    fun getAll(): Flow<List<MapEdgeEntity>>

    @Query("SELECT * FROM map_edges WHERE is_traversed = 1")
    fun getTraversed(): Flow<List<MapEdgeEntity>>

    @Query("SELECT * FROM map_edges WHERE is_traversed = 0")
    fun getUntraversed(): Flow<List<MapEdgeEntity>>

    @Query("SELECT * FROM map_edges WHERE from_node IN (:fromNodes)")
    suspend fun getEdgesByFromNodes(fromNodes: List<Long>): List<MapEdgeEntity>

    @Query(
        """
        SELECT DISTINCT e.* FROM map_edges e
        INNER JOIN map_nodes nf ON e.from_node = nf.id
        INNER JOIN map_nodes nt ON e.to_node = nt.id
        WHERE (nf.lat BETWEEN :minLat AND :maxLat AND nf.lon BETWEEN :minLon AND :maxLon)
           OR (nt.lat BETWEEN :minLat AND :maxLat AND nt.lon BETWEEN :minLon AND :maxLon)
        """
    )
    suspend fun getNear(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<MapEdgeEntity>

    @Query(
        """
        SELECT DISTINCT e.from_node, e.to_node, e.length_m FROM map_edges e
        INNER JOIN map_nodes nf ON e.from_node = nf.id
        INNER JOIN map_nodes nt ON e.to_node = nt.id
        WHERE (nf.lat BETWEEN :minLat AND :maxLat AND nf.lon BETWEEN :minLon AND :maxLon)
           OR (nt.lat BETWEEN :minLat AND :maxLat AND nt.lon BETWEEN :minLon AND :maxLon)
        """
    )
    suspend fun getRoutingEdgesNear(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<RoutingEdgeRow>
}
