package com.cyclegraph.app.domain.repository

import com.cyclegraph.app.domain.model.CyclingSession
import kotlinx.coroutines.flow.Flow

interface CyclingSessionRepository {
    fun getAllSessions(): Flow<List<CyclingSession>>
    fun getRecentSessions(limit: Int): Flow<List<CyclingSession>>
    fun getSessionsByIds(ids: List<Long>): Flow<List<CyclingSession>>
    suspend fun getSessionById(id: Long): CyclingSession?
    suspend fun getSessionBySha1(sha1: String): CyclingSession?
    suspend fun existsBySha1(sha1: String): Boolean
    suspend fun insertSession(session: CyclingSession): Long
    suspend fun updateSession(session: CyclingSession)
    suspend fun deleteSession(session: CyclingSession)
    suspend fun getSessionCount(): Int
    suspend fun updateIntervalStats(sessionId: Long, count: Int, totalSec: Int)
    suspend fun getRecentSessionsList(limit: Int): List<CyclingSession>
    suspend fun getSessionsBeforeDate(epochMs: Long, limit: Int): List<CyclingSession>
}
