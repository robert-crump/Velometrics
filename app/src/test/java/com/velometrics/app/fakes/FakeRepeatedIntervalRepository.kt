package com.velometrics.app.fakes

import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.domain.repository.RepeatedIntervalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeRepeatedIntervalRepository : RepeatedIntervalRepository {
    override fun getAllRepeatedIntervals(): Flow<List<RepeatedInterval>> = flowOf(emptyList())
    override fun getRepeatedIntervalById(id: Long): Flow<RepeatedInterval?> = flowOf(null)
    override suspend fun getAllRepeatedIntervalsList(): List<RepeatedInterval> = emptyList()
    override suspend fun saveRepeatedInterval(interval: RepeatedInterval): Long = 0L
    override suspend fun renameRepeatedInterval(id: Long, newName: String) {}
    override suspend fun deleteRepeatedIntervalsByIds(ids: List<Long>) {}
    override suspend fun deleteAll() {}
}
