package com.velometrics.app.data.repository

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.SessionClusterData
import com.velometrics.app.domain.repository.CyclingSessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeCyclingSessionRepository : CyclingSessionRepository {

    val sessions = mutableListOf<CyclingSession>()

    override fun getAllSessions(): Flow<List<CyclingSession>> = flowOf(sessions.toList())

    override fun getRecentSessions(limit: Int): Flow<List<CyclingSession>> =
        flowOf(sessions.sortedByDescending { it.sessionStart }.take(limit))

    override suspend fun getSessionById(id: Long): CyclingSession? =
        sessions.find { it.id == id }

    override suspend fun getSessionBySha1(sha1: String): CyclingSession? =
        sessions.find { it.fileSha1 == sha1 }

    override suspend fun existsBySha1(sha1: String): Boolean =
        sessions.any { it.fileSha1 == sha1 }

    override suspend fun insertSession(session: CyclingSession): Long {
        val id = (sessions.maxOfOrNull { it.id } ?: 0L) + 1
        sessions.add(session.copy(id = id))
        return id
    }

    override suspend fun updateSession(session: CyclingSession) {
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) sessions[index] = session
    }

    override suspend fun deleteSession(session: CyclingSession) {
        sessions.removeAll { it.id == session.id }
    }

    override suspend fun getSessionCount(): Int = sessions.size

    override suspend fun updateIntervalStats(sessionId: Long, count: Int, totalSec: Int) {
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index >= 0) {
            sessions[index] = sessions[index].copy(
                intervalCount = count,
                intervalTotalTimeSec = totalSec
            )
        }
    }

    override suspend fun getRecentSessionsList(limit: Int): List<CyclingSession> =
        sessions.sortedByDescending { it.sessionStart }.take(limit)

    override fun getSessionsByIds(ids: List<Long>): Flow<List<CyclingSession>> =
        flowOf(sessions.filter { it.id in ids })

    override suspend fun getSessionsBeforeDate(epochMs: Long, limit: Int): List<CyclingSession> =
        sessions.filter { it.sessionStart.toEpochMilli() < epochMs }
            .sortedByDescending { it.sessionStart }
            .take(limit)

    override suspend fun getAllClusterData(): List<SessionClusterData> =
        sessions.map { SessionClusterData(it.id, it.gpsTrack, it.distanceKm) }

    override suspend fun getSessionsByIdsList(ids: List<Long>): List<CyclingSession> =
        sessions.filter { it.id in ids }
}
