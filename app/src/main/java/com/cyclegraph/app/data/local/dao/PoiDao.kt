package com.cyclegraph.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.cyclegraph.app.data.local.entity.PoiEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PoiDao {
    @Query("SELECT * FROM pois")
    fun getAll(): Flow<List<PoiEntity>>
}
