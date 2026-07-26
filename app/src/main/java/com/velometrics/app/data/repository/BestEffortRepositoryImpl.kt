package com.velometrics.app.data.repository

import com.velometrics.app.data.local.dao.SessionBestEffortDao
import com.velometrics.app.data.local.entity.SessionBestEffortEntity
import com.velometrics.app.domain.model.BestEffortRecord
import com.velometrics.app.domain.model.BestEffortValues
import com.velometrics.app.domain.repository.BestEffortRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BestEffortRepositoryImpl @Inject constructor(
    private val dao: SessionBestEffortDao
) : BestEffortRepository {

    override suspend fun insert(sessionId: Long, values: BestEffortValues) {
        dao.insert(
            SessionBestEffortEntity(
                sessionId = sessionId,
                split25kSec = values.split25kSec,
                split50kSec = values.split50kSec,
                split100kSec = values.split100kSec,
                power1s = values.power1s,
                power3s = values.power3s,
                power5s = values.power5s,
                power20s = values.power20s,
                power30s = values.power30s,
                power1m = values.power1m,
                power5m = values.power5m,
                power20m = values.power20m,
                power30m = values.power30m
            )
        )
    }

    override suspend fun getAllWithSessionDate(): List<BestEffortRecord> {
        return dao.getAllWithSessionDate()
    }
}
