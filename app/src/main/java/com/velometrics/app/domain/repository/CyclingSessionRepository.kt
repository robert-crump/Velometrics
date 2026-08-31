package com.velometrics.app.domain.repository

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.CyclingSessionSummary
import com.velometrics.app.domain.model.SessionClusterData
import com.velometrics.app.domain.model.SessionMetricSample
import java.time.Instant
import kotlinx.coroutines.flow.Flow

interface CyclingSessionRepository {
    fun getAllSessions(): Flow<List<CyclingSession>>
    fun getAllSessionSummaries(): Flow<List<CyclingSessionSummary>>
    fun getRecentSessions(limit: Int): Flow<List<CyclingSession>>
    fun getSessionsByIds(ids: List<Long>): Flow<List<CyclingSession>>
    suspend fun getSessionById(id: Long): CyclingSession?
    suspend fun getSessionBySha1(sha1: String): CyclingSession?
    suspend fun existsBySha1(sha1: String): Boolean
    suspend fun insertSession(session: CyclingSession): Long
    suspend fun updateSession(session: CyclingSession)
    suspend fun deleteSession(session: CyclingSession)
    suspend fun getSessionCount(): Int
    /** Latest [CyclingSession.sessionStart] persisted, or null if the table is empty. */
    suspend fun getMaxSessionStart(): Instant?
    /** Count of persisted sessions with a strictly greater distance, since [since] (null = all-time). */
    suspend fun countSessionsWithGreaterDistance(distanceKm: Double, since: Instant?): Int
    /** Count of persisted sessions with a strictly greater elevation gain, since [since] (null = all-time). */
    suspend fun countSessionsWithGreaterElevationGain(elevationGainM: Double, since: Instant?): Int
    /** Count of persisted sessions with a strictly greater average speed, since [since] (null = all-time). */
    suspend fun countSessionsWithGreaterAverageSpeed(averageSpeedKmh: Double, since: Instant?): Int
    suspend fun updateIntervalStats(sessionId: Long, count: Int, totalSec: Int)
    /** Sets [CyclingSession.tag] directly, without a full read-modify-write round trip. */
    suspend fun updateTag(sessionId: Long, tag: String?)
    suspend fun getRecentSessionsList(limit: Int): List<CyclingSession>
    suspend fun getSessionMetricSamplesBeforeDate(epochMs: Long, limit: Int): List<SessionMetricSample>
    suspend fun getAllSessionMetricSamplesBeforeDate(epochMs: Long): List<SessionMetricSample>
    /** Tag-scoped sibling of [getSessionMetricSamplesBeforeDate] (#171). */
    suspend fun getSessionMetricSamplesBeforeDateForTag(tag: String, epochMs: Long, limit: Int): List<SessionMetricSample>
    /** Tag-scoped sibling of [getAllSessionMetricSamplesBeforeDate] (#171). */
    suspend fun getAllSessionMetricSamplesBeforeDateForTag(tag: String, epochMs: Long): List<SessionMetricSample>
    suspend fun getAllClusterData(): List<SessionClusterData>
    suspend fun getSessionsByIdsList(ids: List<Long>): List<CyclingSession>
}
