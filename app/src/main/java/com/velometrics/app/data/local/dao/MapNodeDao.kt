package com.velometrics.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.velometrics.app.data.local.entity.MapNodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MapNodeDao {
    @Query("SELECT * FROM map_nodes")
    fun getAll(): Flow<List<MapNodeEntity>>

    @Query("SELECT * FROM map_nodes WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    suspend fun getNear(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<MapNodeEntity>

    @Query("SELECT * FROM map_nodes WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<MapNodeEntity>
}
