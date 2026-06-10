package com.velometrics.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.velometrics.app.data.local.entity.PoiEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PoiDao {
    @Query("SELECT * FROM pois")
    fun getAll(): Flow<List<PoiEntity>>

    @Query("SELECT * FROM pois WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    suspend fun getInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<PoiEntity>
}
