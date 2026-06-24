package com.velometrics.app.data.fitimport

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.Datapoint
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.GeoUtils
import com.google.gson.Gson
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.math.roundToInt

data class TimerEvent(val timestamp: Instant, val eventType: String)

@Singleton
class SessionMetricsCalculator @Inject constructor() {

    private val gson = Gson()

    fun compute(
        fileName: String,
        fileSha1: String,
        datapoints: List<Datapoint>,
        hasPower: Boolean,
        timerEvents: List<TimerEvent>,
        rawRecordCount: Int,
        originalPowerCount: Int,
        ftp: Int = CyclingConstants.DEFAULT_FTP,
        maxHr: Int = CyclingConstants.DEFAULT_MAX_HR
    ): CyclingSession {
        // 1. Timestamps
        val sessionStart = datapoints.first().timestamp
        val sessionEnd = datapoints.last().timestamp

        // 2. Durations
        val totalDurationSec = Duration.between(sessionStart, sessionEnd).seconds.toInt()
        val pauseDurationSec = computePauseDuration(timerEvents)
        val netDurationSec = (totalDurationSec - pauseDurationSec).coerceAtLeast(0)

        // 3. Distance
        val distanceKm = computeDistance(datapoints)

        // 4. Speed histogram
        val speedHistogram = computeSpeedHistogram(datapoints)

        // 5. Power zones
        val powerZoneDistribution = if (hasPower) computePowerZones(datapoints, ftp) else null

        // 6. Average power
        val averagePower = if (hasPower) computeAveragePower(datapoints) else null

        // 7. Normalized power
        val normalizedPower = if (hasPower) computeNormalizedPower(datapoints) else null

        // 8. Fat/carbs burned (using effective powers to guesstimate 0 W streaks)
        val effectivePowers = if (hasPower) computeEffectivePowers(datapoints) else null
        val fatBurnedGrams = if (hasPower) computeFatBurned(datapoints, effectivePowers!!) else null
        val carbsBurnedGrams = if (hasPower) computeCarbsBurned(datapoints, effectivePowers!!) else null

        // 8b. Fat efficiency histogram + score
        val fatEfficiencyHistogram = if (hasPower) computeFatEfficiencyHistogram(datapoints, effectivePowers!!) else null
        val fatEfficiencyScore = if (hasPower) computeFatEfficiencyScore(datapoints, effectivePowers!!) else null

        // 9. GPS quality
        val gpsQualityPercent = if (rawRecordCount > 0) {
            (datapoints.size.toDouble() / rawRecordCount) * 100.0
        } else 0.0

        // 10. Power quality
        val powerQualityPercent = if (hasPower && datapoints.isNotEmpty()) {
            (originalPowerCount.toDouble() / datapoints.size) * 100.0
        } else null

        // 11. GPS track
        val gpsTrack = downsampleAndSerialize(datapoints)

        // 12. Cardiac efficiency input (avg heart rate)
        val avgHeartRate = computeAvgHeartRate(datapoints)

        // 13. Elevation gain
        val elevationGainM = computeElevationGain(datapoints)

        // 14. Heart rate zones
        val hrZoneDistribution = if (avgHeartRate != null) computeHrZones(datapoints, maxHr) else null

        return CyclingSession(
            fileName = fileName,
            fileSha1 = fileSha1,
            sessionStart = sessionStart,
            sessionEnd = sessionEnd,
            totalDurationSec = totalDurationSec,
            pauseDurationSec = pauseDurationSec,
            netDurationSec = netDurationSec,
            distanceKm = distanceKm,
            averagePower = averagePower,
            normalizedPower = normalizedPower,
            fatBurnedGrams = fatBurnedGrams,
            carbsBurnedGrams = carbsBurnedGrams,
            powerZoneDistribution = powerZoneDistribution,
            speedHistogram = speedHistogram,
            intervalCount = 0,
            intervalTotalTimeSec = 0,
            gpsQualityPercent = gpsQualityPercent,
            powerQualityPercent = powerQualityPercent,
            hasPower = hasPower,
            gpsTrack = gpsTrack,
            fatEfficiencyHistogram = fatEfficiencyHistogram,
            fatEfficiencyScore = fatEfficiencyScore,
            avgHeartRate = avgHeartRate,
            elevationGainM = elevationGainM,
            hrZoneDistribution = hrZoneDistribution
        )
    }

    /**
     * Mean of non-null, non-zero heart rate readings, gated on
     * [CyclingConstants.POWER_DATA_COVERAGE_THRESHOLD] coverage of the session's datapoints.
     */
    private fun computeAvgHeartRate(datapoints: List<Datapoint>): Int? {
        if (datapoints.isEmpty()) return null
        val hrValues = datapoints.mapNotNull { it.heartRate }.filter { it > 0 }
        val hasHeartRate = hrValues.size >= (datapoints.size * CyclingConstants.POWER_DATA_COVERAGE_THRESHOLD)
        if (!hasHeartRate) return null
        return hrValues.average().roundToInt()
    }

    private fun computeHrZones(datapoints: List<Datapoint>, maxHr: Int): Map<String, Int> {
        val zones = CyclingConstants.HR_ZONES.associate { (label, _) -> label to 0 }.toMutableMap()
        val maxHrDouble = maxHr.toDouble()

        for (dp in datapoints) {
            val hr = dp.heartRate ?: continue
            if (hr <= 0) continue
            val ratio = hr / maxHrDouble
            for ((label, range) in CyclingConstants.HR_ZONES) {
                if (ratio >= range.first && ratio < range.second) {
                    zones[label] = (zones[label] ?: 0) + 1
                    break
                }
            }
        }
        return zones
    }

    /**
     * Cumulative positive elevation gain using a hysteresis algorithm: tracks a running
     * reference low point, banking a gain (and raising the reference) once altitude rises
     * at least [CyclingConstants.ELEVATION_GAIN_THRESHOLD_M] above it, and lowering the
     * reference on descents. Rejects GPS/barometric sensor jitter.
     */
    private fun computeElevationGain(datapoints: List<Datapoint>): Double? {
        val altitudes = datapoints.mapNotNull { it.altitude }
        if (altitudes.size < 2) return null

        var referenceLow = altitudes.first()
        var totalGain = 0.0

        for (i in 1 until altitudes.size) {
            val alt = altitudes[i]
            if (alt - referenceLow >= CyclingConstants.ELEVATION_GAIN_THRESHOLD_M) {
                totalGain += alt - referenceLow
                referenceLow = alt
            } else if (alt < referenceLow) {
                referenceLow = alt
            }
        }

        return totalGain
    }

    private fun computePauseDuration(timerEvents: List<TimerEvent>): Int {
        var pauseSec = 0
        var lastStopTime: Instant? = null

        for (event in timerEvents) {
            when (event.eventType) {
                "stop", "stop_all" -> {
                    if (lastStopTime == null) {
                        lastStopTime = event.timestamp
                    }
                }
                "start" -> {
                    if (lastStopTime != null) {
                        pauseSec += Duration.between(lastStopTime, event.timestamp).seconds.toInt()
                        lastStopTime = null
                    }
                }
            }
        }
        return pauseSec
    }

    private fun computeDistance(datapoints: List<Datapoint>): Double {
        var totalMeters = 0.0
        for (i in 1 until datapoints.size) {
            val prev = datapoints[i - 1]
            val curr = datapoints[i]
            totalMeters += GeoUtils.haversineDistance(prev.lat, prev.lon, curr.lat, curr.lon)
        }
        return totalMeters / 1000.0
    }

    private fun computeSpeedHistogram(datapoints: List<Datapoint>): Map<String, Int> {
        val histogram = CyclingConstants.SPEED_HISTOGRAM_BINS.associate { (label, _) ->
            label to 0
        }.toMutableMap()

        for (dp in datapoints) {
            val speed = dp.speedKmh ?: 0.0
            for ((label, range) in CyclingConstants.SPEED_HISTOGRAM_BINS) {
                if (speed >= range.first && speed < range.second) {
                    histogram[label] = (histogram[label] ?: 0) + 1
                    break
                }
            }
        }
        return histogram
    }

    private fun computePowerZones(datapoints: List<Datapoint>, ftp: Int): Map<String, Int> {
        val zones = mutableMapOf("0 W" to 0)
        CyclingConstants.POWER_ZONES.forEach { (label, _) -> zones[label] = 0 }

        val ftp = ftp.toDouble()

        for (dp in datapoints) {
            val power = dp.power ?: 0
            if (power == 0) {
                zones["0 W"] = (zones["0 W"] ?: 0) + 1
                continue
            }
            val ratio = power / ftp
            for ((label, range) in CyclingConstants.POWER_ZONES) {
                if (ratio >= range.first && ratio < range.second) {
                    zones[label] = (zones[label] ?: 0) + 1
                    break
                }
            }
        }
        return zones
    }

    private fun computeAveragePower(datapoints: List<Datapoint>): Int? {
        val powers = datapoints.mapNotNull { it.power }
        if (powers.isEmpty()) return null
        return powers.average().roundToInt()
    }

    private fun computeNormalizedPower(datapoints: List<Datapoint>): Int? {
        val powers = datapoints.mapNotNull { it.power }
        if (powers.isEmpty()) return null
        if (powers.size <= CyclingConstants.NORMALIZED_POWER_WINDOW) return powers.average().roundToInt()

        // 30-second rolling average
        val rollingAvg = mutableListOf<Double>()
        for (i in CyclingConstants.NORMALIZED_POWER_WINDOW until powers.size) {
            val window = powers.subList(i - CyclingConstants.NORMALIZED_POWER_WINDOW, i + 1)
            rollingAvg.add(window.average())
        }

        // Raise to 4th power, take mean, then 4th root
        val meanFourthPower = rollingAvg.map { it.pow(4) }.average()
        return meanFourthPower.pow(0.25).roundToInt()
    }

    /**
     * For each datapoint, returns an effective power value.
     * - Non-zero power: effective = actual power.
     * - 0W streak: effective = median of the 20 most recent non-zero power values
     *   that precede the first point of the streak (0W values within those 20 are excluded).
     *   If no previous non-zero values exist, effective = 0.
     */
    private fun computeEffectivePowers(datapoints: List<Datapoint>): List<Double> {
        val n = datapoints.size
        val result = DoubleArray(n)
        var i = 0
        while (i < n) {
            val power = datapoints[i].power?.toDouble() ?: 0.0
            if (power > 0.0) {
                result[i] = power
                i++
            } else {
                // Identify the full 0W streak starting at i
                val streakStart = i
                while (i < n && (datapoints[i].power ?: 0) == 0) i++
                // Collect up to 20 non-zero power values preceding this streak
                val prevPowers = mutableListOf<Double>()
                var j = streakStart - 1
                while (j >= 0 && prevPowers.size < 20) {
                    val p = datapoints[j].power?.toDouble() ?: 0.0
                    if (p > 0.0) prevPowers.add(p)
                    j--
                }
                val substitute = if (prevPowers.isEmpty()) {
                    0.0
                } else {
                    val sorted = prevPowers.sorted()
                    val mid = sorted.size / 2
                    if (sorted.size % 2 == 1) sorted[mid]
                    else (sorted[mid - 1] + sorted[mid]) / 2.0
                }
                for (k in streakStart until i) result[k] = substitute
            }
        }
        return result.toList()
    }

    private fun computeFatBurned(datapoints: List<Datapoint>, effectivePowers: List<Double>): Double {
        val totalFatKcal = datapoints.indices.sumOf { idx ->
            GeoUtils.fatBurnKcalPerSec(effectivePowers[idx])
        }
        return totalFatKcal / CyclingConstants.KCAL_PER_GRAM_FAT
    }

    private fun computeCarbsBurned(datapoints: List<Datapoint>, effectivePowers: List<Double>): Double {
        val totalCarbsKcal = datapoints.indices.sumOf { idx ->
            GeoUtils.carbBurnKcalPerSec(effectivePowers[idx])
        }
        return totalCarbsKcal / 4.1
    }

    /**
     * Fat efficiency score (0-100).
     *
     * For each data point with effective power > 0:
     *   term = weight × (fatBurn / fatMaxBurn)
     *   where weight = 1 if power ≤ FatMax,
     *         weight = carbBurnAtFatMax / carbBurn if power > FatMax
     * Score = sum(terms) / N × 100, where N = count of data points with effective power > 0.
     */
    private fun computeFatEfficiencyScore(datapoints: List<Datapoint>, effectivePowers: List<Double>): Int? {
        val fatMaxWatt = -CyclingConstants.FAT_B / (2.0 * CyclingConstants.FAT_A)
        val fatMaxKcalPerSec = GeoUtils.fatBurnKcalPerSec(fatMaxWatt).coerceAtLeast(1e-9)
        val carbBurnRateAtFatMax = GeoUtils.carbBurnKcalPerSec(fatMaxWatt).coerceAtLeast(1e-9)

        var weightedSum = 0.0
        var count = 0

        for (idx in datapoints.indices) {
            val power = effectivePowers[idx]
            if (power <= 0.0) continue

            val relativeFatBurn = GeoUtils.fatBurnKcalPerSec(power) / fatMaxKcalPerSec
            val weight = if (power <= fatMaxWatt) {
                1.0
            } else {
                val carbRate = GeoUtils.carbBurnKcalPerSec(power)
                carbBurnRateAtFatMax / carbRate.coerceAtLeast(1e-9)
            }

            weightedSum += relativeFatBurn * weight
            count++
        }

        if (count == 0) return null
        return (weightedSum / count * 100).roundToInt()
    }

    private fun computeFatEfficiencyHistogram(datapoints: List<Datapoint>, effectivePowers: List<Double>): Map<String, Int> {
        val fatMaxWatt = -CyclingConstants.FAT_B / (2.0 * CyclingConstants.FAT_A)
        val fatMaxKcalPerSec = GeoUtils.fatBurnKcalPerSec(fatMaxWatt).coerceAtLeast(1e-9)

        val histogram = mutableMapOf("Low (0-50%)" to 0, "Medium (50-80%)" to 0, "High (>80%)" to 0)

        for (idx in datapoints.indices) {
            val power = effectivePowers[idx]
            if (power <= 0.0) continue
            val efficiency = (GeoUtils.fatBurnKcalPerSec(power) / fatMaxKcalPerSec * 100.0)
                .coerceIn(0.0, 100.0)
            when {
                efficiency >= 80.0 -> histogram["High (>80%)"] = (histogram["High (>80%)"] ?: 0) + 1
                efficiency >= 50.0 -> histogram["Medium (50-80%)"] = (histogram["Medium (50-80%)"] ?: 0) + 1
                else -> histogram["Low (0-50%)"] = (histogram["Low (0-50%)"] ?: 0) + 1
            }
        }
        return histogram
    }

    private fun downsampleAndSerialize(datapoints: List<Datapoint>): String? {
        if (datapoints.isEmpty()) return null
        val coords = datapoints.map { listOf(it.lat, it.lon) }
        return gson.toJson(coords)
    }
}
