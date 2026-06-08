package com.velometrics.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.velometrics.app.data.local.entity.MapEdgeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MapEdgeDao {
    @Query("SELECT * FROM map_edges")
    fun getAll(): Flow<List<MapEdgeEntity>>

    @Query("SELECT * FROM map_edges WHERE is_traversed = 1")
    fun getTraversed(): Flow<List<MapEdgeEntity>>

    @Query("SELECT * FROM map_edges WHERE is_traversed = 0")
    fun getUntraversed(): Flow<List<MapEdgeEntity>>

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
}
