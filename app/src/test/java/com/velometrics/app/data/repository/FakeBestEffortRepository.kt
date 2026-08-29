package com.velometrics.app.data.repository

import com.velometrics.app.domain.model.BestEffortRecord
import com.velometrics.app.domain.model.BestEffortValues
import com.velometrics.app.domain.repository.BestEffortRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeBestEffortRepository : BestEffortRepository {

    val records = mutableListOf<BestEffortRecord>()

    override suspend fun insert(sessionId: Long, values: BestEffortValues) {
        throw UnsupportedOperationException("not used by AllTimeStatsCache")
    }

    override fun getAllWithSessionDate(): Flow<List<BestEffortRecord>> = flowOf(records.toList())

    override suspend fun getForSession(sessionId: Long): BestEffortValues? =
        records.find { it.sessionId == sessionId }?.let {
            BestEffortValues(
                split25kSec = it.split25kSec,
                split50kSec = it.split50kSec,
                split100kSec = it.split100kSec,
                power1s = it.power1s,
                power3s = it.power3s,
                power5s = it.power5s,
                power20s = it.power20s,
                power30s = it.power30s,
                power1m = it.power1m,
                power5m = it.power5m,
                power20m = it.power20m,
                power30m = it.power30m
            )
        }

    override suspend fun countBestEffortsWithGreaterPower5s(power: Int, since: Instant?): Int =
        countGreater(since) { it.power5s != null && it.power5s > power }

    override suspend fun countBestEffortsWithGreaterPower1m(power: Int, since: Instant?): Int =
        countGreater(since) { it.power1m != null && it.power1m > power }

    override suspend fun countBestEffortsWithGreaterPower5m(power: Int, since: Instant?): Int =
        countGreater(since) { it.power5m != null && it.power5m > power }

    override suspend fun countBestEffortsWithGreaterPower20m(power: Int, since: Instant?): Int =
        countGreater(since) { it.power20m != null && it.power20m > power }

    private fun countGreater(since: Instant?, predicate: (BestEffortRecord) -> Boolean): Int {
        val sinceMs = since?.toEpochMilli()
        return records.count { (sinceMs == null || it.sessionStart >= sinceMs) && predicate(it) }
    }
}
