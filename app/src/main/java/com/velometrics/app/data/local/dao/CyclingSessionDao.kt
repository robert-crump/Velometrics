package com.velometrics.app.data.local.dao

import androidx.room.*
import com.velometrics.app.data.local.entity.CyclingSessionEntity
import com.velometrics.app.domain.model.SessionClusterData
import kotlinx.coroutines.flow.Flow

data class SessionIdAndTrack(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "gpsTrack") val gpsTrack: String?
)

data class CyclingSessionSummaryEntity(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "sessionStart") val sessionStart: Long,
    @ColumnInfo(name = "distanceKm") val distanceKm: Double,
    @ColumnInfo(name = "netDurationSec") val netDurationSec: Int,
    @ColumnInfo(name = "averagePower") val averagePower: Int?,
    @ColumnInfo(name = "hasPower") val hasPower: Boolean,
    @ColumnInfo(name = "tag") val tag: String?
)

data class SessionMetricSampleEntity(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "netDurationSec") val netDurationSec: Int,
    @ColumnInfo(name = "distanceKm") val distanceKm: Double,
    @ColumnInfo(name = "averagePower") val averagePower: Int?,
    @ColumnInfo(name = "normalizedPower") val normalizedPower: Int?,
    @ColumnInfo(name = "fatEfficiencyScore") val fatEfficiencyScore: Int?,
    @ColumnInfo(name = "avgHeartRate") val avgHeartRate: Int?,
    @ColumnInfo(name = "elevationGainM") val elevationGainM: Double?,
    @ColumnInfo(name = "fatBurnedGrams") val fatBurnedGrams: Double?,
    @ColumnInfo(name = "carbsBurnedGrams") val carbsBurnedGrams: Double?,
    @ColumnInfo(name = "cardiacDriftPercent") val cardiacDriftPercent: Double?,
    @ColumnInfo(name = "hasPower") val hasPower: Boolean,
    @ColumnInfo(name = "intervalCount") val intervalCount: Int,
    @ColumnInfo(name = "intervalTotalTimeSec") val intervalTotalTimeSec: Int,
    @ColumnInfo(name = "timeBelowSixtyPercentFtpSec") val timeBelowSixtyPercentFtpSec: Int?
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

    @Query("SELECT id, sessionStart, distanceKm, netDurationSec, averagePower, hasPower, tag FROM cycling_sessions ORDER BY sessionStart DESC")
    fun getAllSessionSummaries(): Flow<List<CyclingSessionSummaryEntity>>

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

    @Query("SELECT MAX(sessionStart) FROM cycling_sessions")
    suspend fun getMaxSessionStart(): Long?

    // Ride-level milestone rank queries for Ride Reveal (see RideMilestoneEvaluator): each counts
    // rides that beat the given value on that metric, optionally scoped to on/after sinceEpochMs
    // (null means all-time). Rank = count + 1. A NULL elevationGainM, or a session with
    // netDurationSec <= 0, is excluded automatically rather than counted as "greater".

    @Query(
        """SELECT COUNT(*) FROM cycling_sessions
           WHERE distanceKm > :distanceKm AND (:sinceEpochMs IS NULL OR sessionStart >= :sinceEpochMs)"""
    )
    suspend fun countSessionsWithGreaterDistance(distanceKm: Double, sinceEpochMs: Long?): Int

    @Query(
        """SELECT COUNT(*) FROM cycling_sessions
           WHERE elevationGainM > :elevationGainM AND (:sinceEpochMs IS NULL OR sessionStart >= :sinceEpochMs)"""
    )
    suspend fun countSessionsWithGreaterElevationGain(elevationGainM: Double, sinceEpochMs: Long?): Int

    @Query(
        """SELECT COUNT(*) FROM cycling_sessions
           WHERE netDurationSec > 0 AND (distanceKm / netDurationSec * 3600.0) > :averageSpeedKmh
           AND (:sinceEpochMs IS NULL OR sessionStart >= :sinceEpochMs)"""
    )
    suspend fun countSessionsWithGreaterAverageSpeed(averageSpeedKmh: Double, sinceEpochMs: Long?): Int

    @Query("UPDATE cycling_sessions SET intervalCount = :count, intervalTotalTimeSec = :totalSec WHERE id = :sessionId")
    suspend fun updateIntervalStats(sessionId: Long, count: Int, totalSec: Int)

    @Query("UPDATE cycling_sessions SET tag = :tag WHERE id = :sessionId")
    suspend fun updateTag(sessionId: Long, tag: String?)

    @Query("SELECT * FROM cycling_sessions ORDER BY sessionStart DESC LIMIT :limit")
    suspend fun getRecentSessionsList(limit: Int): List<CyclingSessionEntity>

    @Query(
        """SELECT id, netDurationSec, distanceKm, averagePower, normalizedPower, fatEfficiencyScore,
           avgHeartRate, elevationGainM, fatBurnedGrams, carbsBurnedGrams, cardiacDriftPercent, hasPower,
           intervalCount, intervalTotalTimeSec, timeBelowSixtyPercentFtpSec
           FROM cycling_sessions WHERE sessionStart < :beforeEpochMs ORDER BY sessionStart DESC LIMIT :limit"""
    )
    suspend fun getSessionMetricSamplesBeforeDate(beforeEpochMs: Long, limit: Int): List<SessionMetricSampleEntity>

    @Query(
        """SELECT id, netDurationSec, distanceKm, averagePower, normalizedPower, fatEfficiencyScore,
           avgHeartRate, elevationGainM, fatBurnedGrams, carbsBurnedGrams, cardiacDriftPercent, hasPower,
           intervalCount, intervalTotalTimeSec, timeBelowSixtyPercentFtpSec
           FROM cycling_sessions WHERE sessionStart < :beforeEpochMs ORDER BY sessionStart DESC"""
    )
    suspend fun getAllSessionMetricSamplesBeforeDate(beforeEpochMs: Long): List<SessionMetricSampleEntity>

    // Tag-scoped siblings of the two queries above, for the tag-scoped comparison narrative
    // (#171): same projection and ordering, additionally filtered to one persisted RideTag label.

    @Query(
        """SELECT id, netDurationSec, distanceKm, averagePower, normalizedPower, fatEfficiencyScore,
           avgHeartRate, elevationGainM, fatBurnedGrams, carbsBurnedGrams, cardiacDriftPercent, hasPower,
           intervalCount, intervalTotalTimeSec, timeBelowSixtyPercentFtpSec
           FROM cycling_sessions WHERE tag = :tag AND sessionStart < :beforeEpochMs ORDER BY sessionStart DESC LIMIT :limit"""
    )
    suspend fun getSessionMetricSamplesBeforeDateForTag(tag: String, beforeEpochMs: Long, limit: Int): List<SessionMetricSampleEntity>

    @Query(
        """SELECT id, netDurationSec, distanceKm, averagePower, normalizedPower, fatEfficiencyScore,
           avgHeartRate, elevationGainM, fatBurnedGrams, carbsBurnedGrams, cardiacDriftPercent, hasPower,
           intervalCount, intervalTotalTimeSec, timeBelowSixtyPercentFtpSec
           FROM cycling_sessions WHERE tag = :tag AND sessionStart < :beforeEpochMs ORDER BY sessionStart DESC"""
    )
    suspend fun getAllSessionMetricSamplesBeforeDateForTag(tag: String, beforeEpochMs: Long): List<SessionMetricSampleEntity>

    @Query("SELECT id, gpsTrack FROM cycling_sessions")
    suspend fun getAllIdsAndTracks(): List<SessionIdAndTrack>

    @Query("SELECT id, gpsTrack, distanceKm FROM cycling_sessions")
    suspend fun getAllClusterData(): List<SessionClusterData>

    @Query("SELECT * FROM cycling_sessions WHERE id IN (:ids) ORDER BY sessionStart DESC")
    fun getSessionsByIds(ids: List<Long>): Flow<List<CyclingSessionEntity>>

    @Query("SELECT * FROM cycling_sessions WHERE id IN (:ids) ORDER BY sessionStart DESC")
    suspend fun getSessionsByIdsList(ids: List<Long>): List<CyclingSessionEntity>
}
