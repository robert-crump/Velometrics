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
    suspend fun updateIntervalStats(sessionId: Long, count: Int, totalSec: Int)
    suspend fun getRecentSessionsList(limit: Int): List<CyclingSession>
    suspend fun getSessionMetricSamplesBeforeDate(epochMs: Long, limit: Int): List<SessionMetricSample>
    suspend fun getAllSessionMetricSamplesBeforeDate(epochMs: Long): List<SessionMetricSample>
    suspend fun getAllClusterData(): List<SessionClusterData>
    suspend fun getSessionsByIdsList(ids: List<Long>): List<CyclingSession>
}
