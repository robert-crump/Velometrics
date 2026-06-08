package com.velometrics.app.domain.repository

import com.velometrics.app.domain.model.RepeatedInterval
import kotlinx.coroutines.flow.Flow

interface RepeatedIntervalRepository {
    fun getAllRepeatedIntervals(): Flow<List<RepeatedInterval>>
    fun getRepeatedIntervalById(id: Long): Flow<RepeatedInterval?>
    suspend fun getAllRepeatedIntervalsList(): List<RepeatedInterval>
    suspend fun saveRepeatedInterval(interval: RepeatedInterval): Long
    suspend fun renameRepeatedInterval(id: Long, newName: String)
    suspend fun deleteRepeatedIntervalsByIds(ids: List<Long>)
    suspend fun deleteAll()
}
