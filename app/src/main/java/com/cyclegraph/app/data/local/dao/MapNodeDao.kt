package com.cyclegraph.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.cyclegraph.app.data.local.entity.MapNodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MapNodeDao {
    @Query("SELECT * FROM map_nodes")
    fun getAll(): Flow<List<MapNodeEntity>>
}
