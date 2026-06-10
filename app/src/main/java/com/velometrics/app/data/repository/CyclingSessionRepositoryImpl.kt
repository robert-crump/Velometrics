package com.velometrics.app.data.repository

import com.velometrics.app.data.local.dao.CyclingSessionDao
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.CyclingSessionSummary
import com.velometrics.app.domain.model.SessionClusterData
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.util.toDomain
import com.velometrics.app.util.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CyclingSessionRepositoryImpl @Inject constructor(
    private val dao: CyclingSessionDao
) : CyclingSessionRepository {

    override fun getAllSessions(): Flow<List<CyclingSession>> {
        return dao.getAllSessions().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getAllSessionSummaries(): Flow<List<CyclingSessionSummary>> {
        return dao.getAllSessionSummaries().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getRecentSessions(limit: Int): Flow<List<CyclingSession>> {
        return dao.getRecentSessions(limit).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getSessionsByIds(ids: List<Long>): Flow<List<CyclingSession>> {
        return dao.getSessionsByIds(ids).map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getSessionById(id: Long): CyclingSession? {
        return dao.getById(id)?.toDomain()
    }

    override suspend fun getSessionBySha1(sha1: String): CyclingSession? {
        return dao.getBySha1(sha1)?.toDomain()
    }

    override suspend fun existsBySha1(sha1: String): Boolean {
        return dao.existsBySha1(sha1)
    }

    override suspend fun insertSession(session: CyclingSession): Long {
        return dao.insert(session.toEntity())
    }

    override suspend fun updateSession(session: CyclingSession) {
        dao.update(session.toEntity())
    }

    override suspend fun deleteSession(session: CyclingSession) {
        dao.delete(session.toEntity())
    }

    override suspend fun getSessionCount(): Int {
        return dao.getSessionCount()
    }

    override suspend fun updateIntervalStats(sessionId: Long, count: Int, totalSec: Int) {
        dao.updateIntervalStats(sessionId, count, totalSec)
    }

    override suspend fun getRecentSessionsList(limit: Int): List<CyclingSession> {
        return dao.getRecentSessionsList(limit).map { it.toDomain() }
    }

    override suspend fun getSessionsBeforeDate(epochMs: Long, limit: Int): List<CyclingSession> {
        return dao.getSessionsBeforeDate(epochMs, limit).map { it.toDomain() }
    }

    override suspend fun getAllClusterData(): List<SessionClusterData> {
        return dao.getAllClusterData()
    }

    override suspend fun getSessionsByIdsList(ids: List<Long>): List<CyclingSession> {
        return dao.getSessionsByIdsList(ids).map { it.toDomain() }
    }
}
