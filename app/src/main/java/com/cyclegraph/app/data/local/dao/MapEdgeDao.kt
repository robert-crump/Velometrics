package com.cyclegraph.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.cyclegraph.app.data.local.entity.MapEdgeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MapEdgeDao {
    @Query("SELECT * FROM map_edges")
    fun getAll(): Flow<List<MapEdgeEntity>>

    @Query("SELECT * FROM map_edges WHERE is_traversed = 1")
    fun getTraversed(): Flow<List<MapEdgeEntity>>

    @Query("SELECT * FROM map_edges WHERE is_traversed = 0")
    fun getUntraversed(): Flow<List<MapEdgeEntity>>
}
