package com.velometrics.app.domain.repository

import com.velometrics.app.domain.model.BestEffortRecord
import com.velometrics.app.domain.model.BestEffortValues

interface BestEffortRepository {
    suspend fun insert(sessionId: Long, values: BestEffortValues)
    suspend fun getAllWithSessionDate(): List<BestEffortRecord>
}
