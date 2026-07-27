package com.velometrics.app.domain.repository

import com.velometrics.app.domain.model.BestEffortRecord
import com.velometrics.app.domain.model.BestEffortValues
import kotlinx.coroutines.flow.Flow

interface BestEffortRepository {
    suspend fun insert(sessionId: Long, values: BestEffortValues)
    fun getAllWithSessionDate(): Flow<List<BestEffortRecord>>
}
