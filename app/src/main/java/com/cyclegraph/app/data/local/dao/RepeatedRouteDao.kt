package com.cyclegraph.app.data.local.dao

import androidx.room.*
import com.cyclegraph.app.data.local.entity.RepeatedRouteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RepeatedRouteDao {

    @Query("SELECT * FROM repeated_routes ORDER BY createdAt ASC")
    fun getAllRoutes(): Flow<List<RepeatedRouteEntity>>

    @Query("SELECT * FROM repeated_routes ORDER BY createdAt ASC")
    suspend fun getAllRoutesList(): List<RepeatedRouteEntity>

    @Query("SELECT * FROM repeated_routes WHERE id = :id")
    suspend fun getById(id: Long): RepeatedRouteEntity?

    @Query("SELECT * FROM repeated_routes WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<RepeatedRouteEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(route: RepeatedRouteEntity): Long

    @Update
    suspend fun update(route: RepeatedRouteEntity)

    @Delete
    suspend fun delete(route: RepeatedRouteEntity)

    @Query("DELETE FROM repeated_routes WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM repeated_routes")
    suspend fun deleteAll()
}
