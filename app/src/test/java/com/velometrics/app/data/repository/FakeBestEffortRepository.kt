package com.velometrics.app.data.repository

import com.velometrics.app.domain.model.BestEffortRecord
import com.velometrics.app.domain.model.BestEffortValues
import com.velometrics.app.domain.repository.BestEffortRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeBestEffortRepository : BestEffortRepository {

    val records = mutableListOf<BestEffortRecord>()

    override suspend fun insert(sessionId: Long, values: BestEffortValues) {
        throw UnsupportedOperationException("not used by AllTimeStatsCache")
    }

    override fun getAllWithSessionDate(): Flow<List<BestEffortRecord>> = flowOf(records.toList())
}
