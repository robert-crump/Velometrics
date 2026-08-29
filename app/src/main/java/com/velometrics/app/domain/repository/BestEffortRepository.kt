package com.velometrics.app.domain.repository

import com.velometrics.app.domain.model.BestEffortRecord
import com.velometrics.app.domain.model.BestEffortValues
import java.time.Instant
import kotlinx.coroutines.flow.Flow

interface BestEffortRepository {
    suspend fun insert(sessionId: Long, values: BestEffortValues)
    fun getAllWithSessionDate(): Flow<List<BestEffortRecord>>

    suspend fun getForSession(sessionId: Long): BestEffortValues?

    // Power-curve rank queries for Ride Reveal (see PowerCurveAchievementEvaluator): each counts
    // best efforts that beat the given power for that duration, optionally scoped to on/after
    // [since] (null means all-time). Rank = count + 1.
    suspend fun countBestEffortsWithGreaterPower5s(power: Int, since: Instant?): Int
    suspend fun countBestEffortsWithGreaterPower1m(power: Int, since: Instant?): Int
    suspend fun countBestEffortsWithGreaterPower5m(power: Int, since: Instant?): Int
    suspend fun countBestEffortsWithGreaterPower20m(power: Int, since: Instant?): Int
}
