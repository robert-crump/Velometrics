package com.velometrics.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.velometrics.app.data.local.entity.SessionBestEffortEntity
import com.velometrics.app.domain.model.BestEffortRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionBestEffortDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SessionBestEffortEntity): Long

    @Query(
        """
        SELECT sbe.sessionId AS sessionId, cs.sessionStart AS sessionStart,
               sbe.split25kSec AS split25kSec, sbe.split50kSec AS split50kSec, sbe.split100kSec AS split100kSec,
               sbe.power1s AS power1s, sbe.power3s AS power3s, sbe.power5s AS power5s,
               sbe.power20s AS power20s, sbe.power30s AS power30s,
               sbe.power1m AS power1m, sbe.power5m AS power5m, sbe.power20m AS power20m, sbe.power30m AS power30m
        FROM session_best_efforts sbe
        JOIN cycling_sessions cs ON cs.id = sbe.sessionId
        """
    )
    fun getAllWithSessionDate(): Flow<List<BestEffortRecord>>

    @Query("SELECT * FROM session_best_efforts WHERE sessionId = :sessionId")
    suspend fun getBySessionId(sessionId: Long): SessionBestEffortEntity?

    // Power-curve rank queries for Ride Reveal (see PowerCurveAchievementEvaluator): each counts
    // rides whose best effort for that duration beats the given power, optionally scoped to
    // on/after sinceEpochMs (null means all-time). Rank = count + 1. A NULL power value (no power
    // meter on that ride, or the ride was shorter than the duration) is excluded automatically
    // rather than counted as "greater".

    @Query(
        """SELECT COUNT(*) FROM session_best_efforts sbe
           JOIN cycling_sessions cs ON cs.id = sbe.sessionId
           WHERE sbe.power5s > :power AND (:sinceEpochMs IS NULL OR cs.sessionStart >= :sinceEpochMs)"""
    )
    suspend fun countBestEffortsWithGreaterPower5s(power: Int, sinceEpochMs: Long?): Int

    @Query(
        """SELECT COUNT(*) FROM session_best_efforts sbe
           JOIN cycling_sessions cs ON cs.id = sbe.sessionId
           WHERE sbe.power1m > :power AND (:sinceEpochMs IS NULL OR cs.sessionStart >= :sinceEpochMs)"""
    )
    suspend fun countBestEffortsWithGreaterPower1m(power: Int, sinceEpochMs: Long?): Int

    @Query(
        """SELECT COUNT(*) FROM session_best_efforts sbe
           JOIN cycling_sessions cs ON cs.id = sbe.sessionId
           WHERE sbe.power5m > :power AND (:sinceEpochMs IS NULL OR cs.sessionStart >= :sinceEpochMs)"""
    )
    suspend fun countBestEffortsWithGreaterPower5m(power: Int, sinceEpochMs: Long?): Int

    @Query(
        """SELECT COUNT(*) FROM session_best_efforts sbe
           JOIN cycling_sessions cs ON cs.id = sbe.sessionId
           WHERE sbe.power20m > :power AND (:sinceEpochMs IS NULL OR cs.sessionStart >= :sinceEpochMs)"""
    )
    suspend fun countBestEffortsWithGreaterPower20m(power: Int, sinceEpochMs: Long?): Int
}
