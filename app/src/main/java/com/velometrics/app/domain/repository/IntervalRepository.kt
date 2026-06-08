package com.velometrics.app.domain.repository

import com.velometrics.app.domain.model.IntervalSession
import kotlinx.coroutines.flow.Flow

interface IntervalRepository {
    suspend fun insertInterval(interval: IntervalSession): Long
    suspend fun insertIntervals(intervals: List<IntervalSession>): List<Long>
    suspend fun updateInterval(interval: IntervalSession)
    fun getIntervalsForSession(sessionId: Long): Flow<List<IntervalSession>>
    fun getAllIntervals(): Flow<List<IntervalSession>>
}
