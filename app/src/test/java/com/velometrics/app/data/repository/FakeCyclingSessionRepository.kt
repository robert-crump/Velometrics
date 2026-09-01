package com.velometrics.app.data.repository

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.CyclingSessionSummary
import com.velometrics.app.domain.model.SessionClusterData
import com.velometrics.app.domain.model.SessionMetricSample
import com.velometrics.app.domain.repository.CyclingSessionRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeCyclingSessionRepository : CyclingSessionRepository {

    val sessions = mutableListOf<CyclingSession>()

    override fun getAllSessions(): Flow<List<CyclingSession>> = flowOf(sessions.toList())

    override fun getAllSessionSummaries(): Flow<List<CyclingSessionSummary>> = flowOf(
        sessions.map {
            CyclingSessionSummary(
                id = it.id,
                sessionStart = it.sessionStart,
                distanceKm = it.distanceKm,
                netDurationSec = it.netDurationSec,
                averagePower = it.averagePower,
                hasPower = it.hasPower,
                tag = it.tag
            )
        }
    )

    override fun getRecentSessions(limit: Int): Flow<List<CyclingSession>> =
        flowOf(sessions.sortedByDescending { it.sessionStart }.take(limit))

    override suspend fun getSessionById(id: Long): CyclingSession? =
        sessions.find { it.id == id }

    override suspend fun getSessionBySha1(sha1: String): CyclingSession? =
        sessions.find { it.fileSha1 == sha1 }

    override suspend fun existsBySha1(sha1: String): Boolean =
        sessions.any { it.fileSha1 == sha1 }

    override suspend fun insertSession(session: CyclingSession): Long {
        val id = (sessions.maxOfOrNull { it.id } ?: 0L) + 1
        sessions.add(session.copy(id = id))
        return id
    }

    override suspend fun updateSession(session: CyclingSession) {
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) sessions[index] = session
    }

    override suspend fun deleteSession(session: CyclingSession) {
        sessions.removeAll { it.id == session.id }
    }

    override suspend fun getSessionCount(): Int = sessions.size

    override suspend fun getMaxSessionStart(): Instant? = sessions.maxOfOrNull { it.sessionStart }

    override suspend fun countSessionsWithGreaterDistance(distanceKm: Double, since: Instant?): Int =
        sessions.count { (since == null || it.sessionStart >= since) && it.distanceKm > distanceKm }

    override suspend fun countSessionsWithGreaterElevationGain(elevationGainM: Double, since: Instant?): Int =
        sessions.count {
            (since == null || it.sessionStart >= since) &&
                it.elevationGainM != null && it.elevationGainM > elevationGainM
        }

    override suspend fun countSessionsWithGreaterAverageSpeed(averageSpeedKmh: Double, since: Instant?): Int =
        sessions.count {
            (since == null || it.sessionStart >= since) &&
                it.netDurationSec > 0 && (it.distanceKm / it.netDurationSec * 3600) > averageSpeedKmh
        }

    override suspend fun updateIntervalStats(sessionId: Long, count: Int, totalSec: Int) {
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index >= 0) {
            sessions[index] = sessions[index].copy(
                intervalCount = count,
                intervalTotalTimeSec = totalSec
            )
        }
    }

    override suspend fun updateTag(sessionId: Long, tag: String?) {
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index >= 0) sessions[index] = sessions[index].copy(tag = tag)
    }

    override suspend fun getRecentSessionsList(limit: Int): List<CyclingSession> =
        sessions.sortedByDescending { it.sessionStart }.take(limit)

    override fun getSessionsByIds(ids: List<Long>): Flow<List<CyclingSession>> =
        flowOf(sessions.filter { it.id in ids })

    override suspend fun getSessionMetricSamplesBeforeDate(epochMs: Long, limit: Int): List<SessionMetricSample> =
        sessions.filter { it.sessionStart.toEpochMilli() < epochMs }
            .sortedByDescending { it.sessionStart }
            .take(limit)
            .map { it.toMetricSample() }

    override suspend fun getAllSessionMetricSamplesBeforeDate(epochMs: Long): List<SessionMetricSample> =
        sessions.filter { it.sessionStart.toEpochMilli() < epochMs }
            .sortedByDescending { it.sessionStart }
            .map { it.toMetricSample() }

    override suspend fun getSessionMetricSamplesBeforeDateForTag(tag: String, epochMs: Long, limit: Int): List<SessionMetricSample> =
        sessions.filter { it.tag == tag && it.sessionStart.toEpochMilli() < epochMs }
            .sortedByDescending { it.sessionStart }
            .take(limit)
            .map { it.toMetricSample() }

    override suspend fun getAllSessionMetricSamplesBeforeDateForTag(tag: String, epochMs: Long): List<SessionMetricSample> =
        sessions.filter { it.tag == tag && it.sessionStart.toEpochMilli() < epochMs }
            .sortedByDescending { it.sessionStart }
            .map { it.toMetricSample() }

    private fun CyclingSession.toMetricSample() = SessionMetricSample(
        id = id,
        netDurationSec = netDurationSec,
        distanceKm = distanceKm,
        averagePower = averagePower,
        normalizedPower = normalizedPower,
        fatEfficiencyScore = fatEfficiencyScore,
        avgHeartRate = avgHeartRate,
        elevationGainM = elevationGainM,
        fatBurnedGrams = fatBurnedGrams,
        carbsBurnedGrams = carbsBurnedGrams,
        cardiacDriftPercent = cardiacDriftPercent,
        hasPower = hasPower,
        intervalCount = intervalCount,
        timeBelowSixtyPercentFtpSec = timeBelowSixtyPercentFtpSec
    )

    override suspend fun getAllClusterData(): List<SessionClusterData> =
        sessions.map { SessionClusterData(it.id, it.gpsTrack, it.distanceKm) }

    override suspend fun getSessionsByIdsList(ids: List<Long>): List<CyclingSession> =
        sessions.filter { it.id in ids }
}
