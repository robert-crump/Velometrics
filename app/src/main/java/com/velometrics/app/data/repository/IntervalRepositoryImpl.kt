package com.velometrics.app.data.repository

import com.velometrics.app.data.local.dao.IntervalSessionDao
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.repository.IntervalRepository
import com.velometrics.app.util.toDomain
import com.velometrics.app.util.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntervalRepositoryImpl @Inject constructor(
    private val intervalDao: IntervalSessionDao
) : IntervalRepository {

    override suspend fun insertInterval(interval: IntervalSession): Long {
        return intervalDao.insert(interval.toEntity())
    }

    override suspend fun insertIntervals(intervals: List<IntervalSession>): List<Long> {
        return intervalDao.insertAll(intervals.map { it.toEntity() })
    }

    override suspend fun updateInterval(interval: IntervalSession) {
        intervalDao.update(interval.toEntity())
    }

    override fun getIntervalsForSession(sessionId: Long): Flow<List<IntervalSession>> {
        return intervalDao.getBySessionId(sessionId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getAllIntervals(): Flow<List<IntervalSession>> {
        return intervalDao.getAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }
}
