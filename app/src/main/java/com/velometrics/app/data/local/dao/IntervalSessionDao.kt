package com.velometrics.app.data.local.dao

import androidx.room.*
import com.velometrics.app.data.local.entity.IntervalSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IntervalSessionDao {
    @Insert
    suspend fun insert(interval: IntervalSessionEntity): Long

    @Insert
    suspend fun insertAll(intervals: List<IntervalSessionEntity>): List<Long>

    @Update
    suspend fun update(interval: IntervalSessionEntity)

    @Query("SELECT * FROM interval_sessions WHERE cyclingSessionId = :sessionId")
    fun getBySessionId(sessionId: Long): Flow<List<IntervalSessionEntity>>

    @Query("SELECT * FROM interval_sessions")
    fun getAll(): Flow<List<IntervalSessionEntity>>

    @Query("SELECT * FROM interval_sessions WHERE id = :id")
    suspend fun getById(id: Long): IntervalSessionEntity?
}
