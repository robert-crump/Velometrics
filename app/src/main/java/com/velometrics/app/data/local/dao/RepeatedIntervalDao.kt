package com.velometrics.app.data.local.dao

import androidx.room.*
import com.velometrics.app.data.local.entity.RepeatedIntervalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RepeatedIntervalDao {

    @Query("SELECT * FROM repeated_intervals ORDER BY createdAt ASC")
    fun getAll(): Flow<List<RepeatedIntervalEntity>>

    @Query("SELECT * FROM repeated_intervals ORDER BY createdAt ASC")
    suspend fun getAllList(): List<RepeatedIntervalEntity>

    @Query("SELECT * FROM repeated_intervals WHERE id = :id")
    suspend fun getById(id: Long): RepeatedIntervalEntity?

    @Query("SELECT * FROM repeated_intervals WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<RepeatedIntervalEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(interval: RepeatedIntervalEntity): Long

    @Update
    suspend fun update(interval: RepeatedIntervalEntity)

    @Query("DELETE FROM repeated_intervals WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM repeated_intervals")
    suspend fun deleteAll()
}
