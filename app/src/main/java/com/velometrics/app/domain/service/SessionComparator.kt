package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.SessionEnergy
import com.velometrics.app.domain.model.SessionMetricSample
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.util.median
import javax.inject.Inject

data class SessionComparison(
    val medianNetDurationSecLast5: Int?,
    val medianNetDurationSecAllPrevious: Int?,
    val medianDistanceKmLast5: Double?,
    val medianDistanceKmAllPrevious: Double?,
    val medianAvgSpeedKmhLast5: Double?,
    val medianAvgSpeedKmhAllPrevious: Double?,
    val medianAvgPowerLast5: Int?,
    val medianAvgPowerAllPrevious: Int?,
    val medianNormalizedPowerLast5: Int?,
    val medianNormalizedPowerAllPrevious: Int?,
    val medianFatEfficiencyLast5: Double?,
    val medianFatEfficiencyAllPrevious: Double?,
    val medianCardiacEfficiencyLast5: Double?,
    val medianCardiacEfficiencyAllPrevious: Double?,
    val medianTotalKcalLast5: Double?,
    val medianTotalKcalAllPrevious: Double?,
    val medianElevationGainMLast5: Double?,
    val medianElevationGainMAllPrevious: Double?,
    val medianElevGainPer100kmLast5: Double?,
    val medianElevGainPer100kmAllPrevious: Double?,
    val medianCardiacDriftPercentLast5: Double?,
    val medianCardiacDriftPercentAllPrevious: Double?,
    val medianNpToApRatioLast5: Double?,
    val medianNpToApRatioAllPrevious: Double?,
    val medianIntervalCountLast5: Int?,
    val medianIntervalCountAllPrevious: Int?,
    val medianIntervalTotalTimeSecLast5: Int?,
    val medianIntervalTotalTimeSecAllPrevious: Int?,
    val medianTimeBelowSixtyPercentFtpSecLast5: Int?,
    val medianTimeBelowSixtyPercentFtpSecAllPrevious: Int?,
    val last5SessionCount: Int,
    val allPreviousSessionCount: Int
)

/**
 * All-time tag-scoped comparison (#214): medians over every earlier ride sharing the current
 * ride's tag. [sampleCount] is the pool size, before per-metric availability filtering.
 */
data class TagComparison(val sampleCount: Int, val medians: PoolMedians)

/**
 * Per-metric medians for one reference pool (e.g. last 5 rides, or all previous rides).
 * Each field independently falls back to null when its own sub-population (e.g. power-having
 * sessions) has fewer than 2 samples — see [SessionComparator.median].
 */
data class PoolMedians(
    val netDurationSec: Int?,
    val distanceKm: Double?,
    val avgSpeedKmh: Double?,
    val avgPower: Int?,
    val normalizedPower: Int?,
    val fatEfficiency: Double?,
    val fatGrams: Double?,
    val cardiacEfficiency: Double?,
    val totalKcal: Double?,
    val elevationGainM: Double?,
    val elevGainPer100km: Double?,
    val cardiacDriftPercent: Double?,
    val npToApRatio: Double?,
    val intervalCount: Int?,
    val intervalTotalTimeSec: Int?,
    val timeBelowSixtyPercentFtpSec: Int?
)

class SessionComparator @Inject constructor(
    private val cyclingSessionRepository: CyclingSessionRepository
) {
    suspend fun computeComparison(currentSession: CyclingSession): SessionComparison {
        val beforeEpochMs = currentSession.sessionStart.toEpochMilli()

        val last5 = cyclingSessionRepository.getSessionMetricSamplesBeforeDate(beforeEpochMs, 5)
            .filter { it.id != currentSession.id }
        val allPrevious = cyclingSessionRepository.getAllSessionMetricSamplesBeforeDate(beforeEpochMs)
            .filter { it.id != currentSession.id }

        val last5Medians = computeMedians(last5)
        val allPreviousMedians = computeMedians(allPrevious)

        return SessionComparison(
            medianNetDurationSecLast5 = last5Medians.netDurationSec,
            medianNetDurationSecAllPrevious = allPreviousMedians.netDurationSec,
            medianDistanceKmLast5 = last5Medians.distanceKm,
            medianDistanceKmAllPrevious = allPreviousMedians.distanceKm,
            medianAvgSpeedKmhLast5 = last5Medians.avgSpeedKmh,
            medianAvgSpeedKmhAllPrevious = allPreviousMedians.avgSpeedKmh,
            medianAvgPowerLast5 = last5Medians.avgPower,
            medianAvgPowerAllPrevious = allPreviousMedians.avgPower,
            medianNormalizedPowerLast5 = last5Medians.normalizedPower,
            medianNormalizedPowerAllPrevious = allPreviousMedians.normalizedPower,
            medianFatEfficiencyLast5 = last5Medians.fatEfficiency,
            medianFatEfficiencyAllPrevious = allPreviousMedians.fatEfficiency,
            medianCardiacEfficiencyLast5 = last5Medians.cardiacEfficiency,
            medianCardiacEfficiencyAllPrevious = allPreviousMedians.cardiacEfficiency,
            medianTotalKcalLast5 = last5Medians.totalKcal,
            medianTotalKcalAllPrevious = allPreviousMedians.totalKcal,
            medianElevationGainMLast5 = last5Medians.elevationGainM,
            medianElevationGainMAllPrevious = allPreviousMedians.elevationGainM,
            medianElevGainPer100kmLast5 = last5Medians.elevGainPer100km,
            medianElevGainPer100kmAllPrevious = allPreviousMedians.elevGainPer100km,
            medianCardiacDriftPercentLast5 = last5Medians.cardiacDriftPercent,
            medianCardiacDriftPercentAllPrevious = allPreviousMedians.cardiacDriftPercent,
            medianNpToApRatioLast5 = last5Medians.npToApRatio,
            medianNpToApRatioAllPrevious = allPreviousMedians.npToApRatio,
            medianIntervalCountLast5 = last5Medians.intervalCount,
            medianIntervalCountAllPrevious = allPreviousMedians.intervalCount,
            medianIntervalTotalTimeSecLast5 = last5Medians.intervalTotalTimeSec,
            medianIntervalTotalTimeSecAllPrevious = allPreviousMedians.intervalTotalTimeSec,
            medianTimeBelowSixtyPercentFtpSecLast5 = last5Medians.timeBelowSixtyPercentFtpSec,
            medianTimeBelowSixtyPercentFtpSecAllPrevious = allPreviousMedians.timeBelowSixtyPercentFtpSec,
            last5SessionCount = last5.size,
            allPreviousSessionCount = allPrevious.size
        )
    }

    /** Medians over all earlier rides sharing [tag] (#214) — the tag-scoped recap's reference pool. */
    suspend fun computeTagComparison(currentSession: CyclingSession, tag: String): TagComparison {
        val pool = cyclingSessionRepository
            .getAllSessionMetricSamplesBeforeDateForTag(tag, currentSession.sessionStart.toEpochMilli())
            .filter { it.id != currentSession.id }
        return TagComparison(pool.size, computeMedians(pool))
    }

    private fun computeMedians(samples: List<SessionMetricSample>): PoolMedians {
        val durations = samples.map { it.netDurationSec.toDouble() }
        val distances = samples.map { it.distanceKm }
        val speeds = samples.map {
            if (it.netDurationSec > 0) it.distanceKm / it.netDurationSec * 3600 else 0.0
        }

        val powerSamples = samples.filter { it.hasPower }
        val avgPowers = powerSamples.mapNotNull { it.averagePower?.toDouble() }
        val normPowers = powerSamples.mapNotNull { it.normalizedPower?.toDouble() }
        val fatEffScores = powerSamples.mapNotNull { it.fatEfficiencyScore?.toDouble() }
        val cardiacEfficiencies = powerSamples.mapNotNull { sample ->
            val hr = sample.avgHeartRate
            val power = sample.averagePower
            if (hr != null && hr != 0 && power != null) power.toDouble() / hr else null
        }
        val npToApRatios = powerSamples.mapNotNull { sample ->
            val avg = sample.averagePower
            val np = sample.normalizedPower
            if (avg != null && avg != 0 && np != null) np.toDouble() / avg else null
        }

        val fatGrams = samples.mapNotNull { SessionEnergy.from(it.fatBurnedGrams, it.carbsBurnedGrams)?.fatGrams }
        val kcals = samples.mapNotNull { SessionEnergy.from(it.fatBurnedGrams, it.carbsBurnedGrams)?.totalKcal?.toDouble() }
        val elevGains = samples.mapNotNull { it.elevationGainM }
        val elevGainsPer100km = samples.mapNotNull { sample ->
            val gain = sample.elevationGainM
            if (gain != null && sample.distanceKm > 0) gain / sample.distanceKm * 100 else null
        }
        val cardiacDrifts = samples.mapNotNull { it.cardiacDriftPercent }
        val intervalCounts = samples.map { it.intervalCount.toDouble() }
        val intervalTotalTimes = samples.map { it.intervalTotalTimeSec.toDouble() }
        val timeBelowSixtyPercentFtpSecs = powerSamples.mapNotNull { it.timeBelowSixtyPercentFtpSec?.toDouble() }

        return PoolMedians(
            netDurationSec = median(durations)?.toInt(),
            distanceKm = median(distances),
            avgSpeedKmh = median(speeds),
            avgPower = median(avgPowers)?.toInt(),
            normalizedPower = median(normPowers)?.toInt(),
            fatEfficiency = median(fatEffScores),
            fatGrams = median(fatGrams),
            cardiacEfficiency = median(cardiacEfficiencies),
            totalKcal = median(kcals),
            elevationGainM = median(elevGains),
            elevGainPer100km = median(elevGainsPer100km),
            cardiacDriftPercent = median(cardiacDrifts),
            npToApRatio = median(npToApRatios),
            intervalCount = median(intervalCounts)?.toInt(),
            intervalTotalTimeSec = median(intervalTotalTimes)?.toInt(),
            timeBelowSixtyPercentFtpSec = median(timeBelowSixtyPercentFtpSecs)?.toInt()
        )
    }

    /** Null below 2 samples — a single-sample "median" isn't a meaningful trend reference. */
    private fun median(values: List<Double>): Double? = if (values.size < 2) null else values.median()
}
