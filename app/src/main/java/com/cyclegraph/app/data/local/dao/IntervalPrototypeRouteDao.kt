package com.cyclegraph.app.data.local.dao

import androidx.room.*
import com.cyclegraph.app.data.local.entity.IntervalPrototypeRouteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IntervalPrototypeRouteDao {
    @Insert
    suspend fun insert(route: IntervalPrototypeRouteEntity): Long

    @Update
    suspend fun update(route: IntervalPrototypeRouteEntity)

    @Delete
    suspend fun delete(route: IntervalPrototypeRouteEntity)

    @Query("SELECT * FROM interval_prototype_routes")
    fun getAll(): Flow<List<IntervalPrototypeRouteEntity>>

    @Query("SELECT * FROM interval_prototype_routes WHERE id = :id")
    suspend fun getById(id: Long): IntervalPrototypeRouteEntity?
}
