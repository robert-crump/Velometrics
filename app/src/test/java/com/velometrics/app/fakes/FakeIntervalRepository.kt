package com.velometrics.app.fakes

import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.repository.IntervalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeIntervalRepository : IntervalRepository {
    val intervals = mutableListOf<IntervalSession>()

    override suspend fun insertInterval(interval: IntervalSession): Long {
        intervals.add(interval)
        return interval.id
    }
    override suspend fun insertIntervals(intervals: List<IntervalSession>): List<Long> =
        intervals.map { insertInterval(it) }
    override suspend fun updateInterval(interval: IntervalSession) {
        val index = intervals.indexOfFirst { it.id == interval.id }
        if (index >= 0) intervals[index] = interval
    }
    override fun getIntervalsForSession(sessionId: Long): Flow<List<IntervalSession>> =
        flowOf(intervals.filter { it.cyclingSessionId == sessionId })
    override fun getAllIntervals(): Flow<List<IntervalSession>> = flowOf(intervals.toList())
}
