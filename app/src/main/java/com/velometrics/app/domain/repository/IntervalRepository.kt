package com.velometrics.app.domain.repository

import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.IntervalPrototypeRoute
import kotlinx.coroutines.flow.Flow

interface IntervalRepository {
    suspend fun insertInterval(interval: IntervalSession): Long
    suspend fun insertIntervals(intervals: List<IntervalSession>)
    suspend fun updateInterval(interval: IntervalSession)
    fun getIntervalsForSession(sessionId: Long): Flow<List<IntervalSession>>
    fun getAllIntervals(): Flow<List<IntervalSession>>
    fun getIntervalsForPrototype(prototypeId: Long): Flow<List<IntervalSession>>

    suspend fun insertPrototypeRoute(route: IntervalPrototypeRoute): Long
    suspend fun updatePrototypeRoute(route: IntervalPrototypeRoute)
    fun getAllPrototypeRoutes(): Flow<List<IntervalPrototypeRoute>>
    suspend fun getPrototypeRouteById(id: Long): IntervalPrototypeRoute?
}
