package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.repository.CyclingSessionRepository
import javax.inject.Inject

data class SessionComparison(
    val medianNetDurationSec: Int?,
    val medianDistanceKm: Double?,
    val medianAvgSpeedKmh: Double?,
    val medianAvgPower: Int?,
    val medianNormalizedPower: Int?,
    val medianIntervalTimeSec: Int?,
    val medianFatEfficiency: Double?,
    val previousSessionCount: Int
)

class SessionComparator @Inject constructor(
    private val cyclingSessionRepository: CyclingSessionRepository
) {
    suspend fun computeComparison(currentSession: CyclingSession): SessionComparison? {
        val recentSessions = cyclingSessionRepository.getSessionsBeforeDate(
            currentSession.sessionStart.toEpochMilli(), 5
        )
        val previous = recentSessions.filter { it.id != currentSession.id }

        if (previous.size < 2) return null

        val durations = previous.map { it.netDurationSec.toDouble() }
        val distances = previous.map { it.distanceKm }
        val speeds = previous.map {
            if (it.netDurationSec > 0) it.distanceKm / it.netDurationSec * 3600 else 0.0
        }
        val intervalTimes = previous.map { it.intervalTotalTimeSec.toDouble() }

        val powerSessions = previous.filter { it.hasPower }
        val avgPowers = powerSessions.mapNotNull { it.averagePower?.toDouble() }
        val normPowers = powerSessions.mapNotNull { it.normalizedPower?.toDouble() }

        val fatEffScores = powerSessions.mapNotNull { session ->
            session.fatEfficiencyScore?.toDouble()
        }

        return SessionComparison(
            medianNetDurationSec = median(durations)?.toInt(),
            medianDistanceKm = median(distances),
            medianAvgSpeedKmh = median(speeds),
            medianAvgPower = if (avgPowers.isNotEmpty()) median(avgPowers)?.toInt() else null,
            medianNormalizedPower = if (normPowers.isNotEmpty()) median(normPowers)?.toInt() else null,
            medianIntervalTimeSec = median(intervalTimes)?.toInt(),
            medianFatEfficiency = if (fatEffScores.isNotEmpty()) median(fatEffScores) else null,
            previousSessionCount = previous.size
        )
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}
