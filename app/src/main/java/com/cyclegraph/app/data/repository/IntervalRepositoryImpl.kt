package com.cyclegraph.app.data.repository

import com.cyclegraph.app.data.local.dao.IntervalPrototypeRouteDao
import com.cyclegraph.app.data.local.dao.IntervalSessionDao
import com.cyclegraph.app.domain.model.IntervalSession
import com.cyclegraph.app.domain.model.IntervalPrototypeRoute
import com.cyclegraph.app.domain.repository.IntervalRepository
import com.cyclegraph.app.util.toDomain
import com.cyclegraph.app.util.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntervalRepositoryImpl @Inject constructor(
    private val intervalDao: IntervalSessionDao,
    private val prototypeDao: IntervalPrototypeRouteDao
) : IntervalRepository {

    override suspend fun insertInterval(interval: IntervalSession): Long {
        return intervalDao.insert(interval.toEntity())
    }

    override suspend fun insertIntervals(intervals: List<IntervalSession>) {
        intervalDao.insertAll(intervals.map { it.toEntity() })
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

    override fun getIntervalsForPrototype(prototypeId: Long): Flow<List<IntervalSession>> {
        return intervalDao.getByPrototypeId(prototypeId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun insertPrototypeRoute(route: IntervalPrototypeRoute): Long {
        return prototypeDao.insert(route.toEntity())
    }

    override suspend fun updatePrototypeRoute(route: IntervalPrototypeRoute) {
        prototypeDao.update(route.toEntity())
    }

    override fun getAllPrototypeRoutes(): Flow<List<IntervalPrototypeRoute>> {
        return prototypeDao.getAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getPrototypeRouteById(id: Long): IntervalPrototypeRoute? {
        return prototypeDao.getById(id)?.toDomain()
    }
}
