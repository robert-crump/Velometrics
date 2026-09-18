package com.velometrics.app.fakes

import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.repository.IntervalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeIntervalRepository : IntervalRepository {
    override suspend fun insertInterval(interval: IntervalSession): Long = 0L
    override suspend fun insertIntervals(intervals: List<IntervalSession>): List<Long> = emptyList()
    override suspend fun updateInterval(interval: IntervalSession) {}
    override fun getIntervalsForSession(sessionId: Long): Flow<List<IntervalSession>> = flowOf(emptyList())
    override fun getAllIntervals(): Flow<List<IntervalSession>> = flowOf(emptyList())
}
