package com.velometrics.app.domain.repository

import com.velometrics.app.domain.model.CardiacDriftCause
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
    /** Cheap pre-download dedup check for Dropbox sync - see [CyclingSessionDao.existsByFileName]. */
    suspend fun existsByFileName(fileName: String): Boolean
    suspend fun insertSession(session: CyclingSession): Long
    suspend fun updateSession(session: CyclingSession)
    suspend fun deleteSession(session: CyclingSession)
    /** Atomic bulk delete for Home's multiselect (#194): all-or-nothing on a failure partway through. */
    suspend fun deleteSessions(ids: List<Long>)
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
    /** Sets [CyclingSession.cardiacDriftCauses] and its duration baseline directly (#222). */
    suspend fun updateCardiacDriftAdvice(sessionId: Long, causes: List<CardiacDriftCause>?, durationBaselineSec: Int?)
    /** Net durations of the rides starting at or after [from] and before [to] (#222). */
    suspend fun getNetDurationsBetween(from: Instant, to: Instant): List<Int>
    /** Rides with drift >= [minPercent] whose drift causes were never evaluated (#222). */
    suspend fun getSessionsMissingCardiacDriftCauses(minPercent: Double): List<CyclingSession>
    suspend fun getRecentSessionsList(limit: Int): List<CyclingSession>
    /** Speed IQ P of the latest [limit] rides with power starting before [before], newest first (#229). */
    suspend fun getSpeedIqReferencePowersBefore(before: Instant, limit: Int): List<Int>
    suspend fun getSessionMetricSamplesBeforeDate(epochMs: Long, limit: Int): List<SessionMetricSample>
    suspend fun getAllSessionMetricSamplesBeforeDate(epochMs: Long): List<SessionMetricSample>
    /** Tag-scoped sibling of [getAllSessionMetricSamplesBeforeDate] (#171). */
    suspend fun getAllSessionMetricSamplesBeforeDateForTag(tag: String, epochMs: Long): List<SessionMetricSample>
    suspend fun getAllClusterData(): List<SessionClusterData>
    suspend fun getSessionsByIdsList(ids: List<Long>): List<CyclingSession>
}
