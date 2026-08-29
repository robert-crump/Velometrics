package com.velometrics.app.data.repository

import com.velometrics.app.data.local.dao.SessionBestEffortDao
import com.velometrics.app.data.local.entity.SessionBestEffortEntity
import com.velometrics.app.domain.model.BestEffortRecord
import com.velometrics.app.domain.model.BestEffortValues
import com.velometrics.app.domain.repository.BestEffortRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
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

    override fun getAllWithSessionDate(): Flow<List<BestEffortRecord>> {
        return dao.getAllWithSessionDate()
    }

    override suspend fun getForSession(sessionId: Long): BestEffortValues? {
        val entity = dao.getBySessionId(sessionId) ?: return null
        return BestEffortValues(
            split25kSec = entity.split25kSec,
            split50kSec = entity.split50kSec,
            split100kSec = entity.split100kSec,
            power1s = entity.power1s,
            power3s = entity.power3s,
            power5s = entity.power5s,
            power20s = entity.power20s,
            power30s = entity.power30s,
            power1m = entity.power1m,
            power5m = entity.power5m,
            power20m = entity.power20m,
            power30m = entity.power30m
        )
    }

    override suspend fun countBestEffortsWithGreaterPower5s(power: Int, since: Instant?): Int {
        return dao.countBestEffortsWithGreaterPower5s(power, since?.toEpochMilli())
    }

    override suspend fun countBestEffortsWithGreaterPower1m(power: Int, since: Instant?): Int {
        return dao.countBestEffortsWithGreaterPower1m(power, since?.toEpochMilli())
    }

    override suspend fun countBestEffortsWithGreaterPower5m(power: Int, since: Instant?): Int {
        return dao.countBestEffortsWithGreaterPower5m(power, since?.toEpochMilli())
    }

    override suspend fun countBestEffortsWithGreaterPower20m(power: Int, since: Instant?): Int {
        return dao.countBestEffortsWithGreaterPower20m(power, since?.toEpochMilli())
    }
}
