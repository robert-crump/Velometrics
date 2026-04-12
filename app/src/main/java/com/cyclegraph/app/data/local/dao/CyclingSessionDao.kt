package com.cyclegraph.app.data.local.dao

import androidx.room.*
import com.cyclegraph.app.data.local.entity.CyclingSessionEntity
import kotlinx.coroutines.flow.Flow

data class SessionIdAndTrack(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "gpsTrack") val gpsTrack: String?
)

@Dao
interface CyclingSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: CyclingSessionEntity): Long

    @Update
    suspend fun update(session: CyclingSessionEntity)

    @Delete
    suspend fun delete(session: CyclingSessionEntity)

    @Query("SELECT * FROM cycling_sessions ORDER BY sessionStart DESC")
    fun getAllSessions(): Flow<List<CyclingSessionEntity>>

    @Query("SELECT * FROM cycling_sessions WHERE id = :id")
    suspend fun getById(id: Long): CyclingSessionEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM cycling_sessions WHERE fileSha1 = :sha1)")
    suspend fun existsBySha1(sha1: String): Boolean

    @Query("SELECT * FROM cycling_sessions WHERE fileSha1 = :sha1")
    suspend fun getBySha1(sha1: String): CyclingSessionEntity?

    @Query("SELECT * FROM cycling_sessions ORDER BY sessionStart DESC LIMIT :limit")
    fun getRecentSessions(limit: Int): Flow<List<CyclingSessionEntity>>

    @Query("SELECT COUNT(*) FROM cycling_sessions")
    suspend fun getSessionCount(): Int

    @Query("UPDATE cycling_sessions SET intervalCount = :count, intervalTotalTimeSec = :totalSec WHERE id = :sessionId")
    suspend fun updateIntervalStats(sessionId: Long, count: Int, totalSec: Int)

    @Query("SELECT * FROM cycling_sessions ORDER BY sessionStart DESC LIMIT :limit")
    suspend fun getRecentSessionsList(limit: Int): List<CyclingSessionEntity>

    @Query("SELECT * FROM cycling_sessions WHERE sessionStart < :beforeEpochMs ORDER BY sessionStart DESC LIMIT :limit")
    suspend fun getSessionsBeforeDate(beforeEpochMs: Long, limit: Int): List<CyclingSessionEntity>

    @Query("SELECT id, gpsTrack FROM cycling_sessions")
    suspend fun getAllIdsAndTracks(): List<SessionIdAndTrack>

    @Query("SELECT * FROM cycling_sessions WHERE id IN (:ids) ORDER BY sessionStart DESC")
    fun getSessionsByIds(ids: List<Long>): Flow<List<CyclingSessionEntity>>
}
