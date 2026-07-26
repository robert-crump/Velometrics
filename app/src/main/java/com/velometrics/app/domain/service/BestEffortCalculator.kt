package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.BestEffortValues
import com.velometrics.app.domain.model.Datapoint
import com.velometrics.app.util.GeoUtils
import java.time.Duration
import kotlin.math.roundToInt

/**
 * Computes per-ride "best effort" records: the fastest time to cover a fixed distance, and the
 * best average power sustained for a fixed duration, anywhere within the ride (not just whole-ride
 * totals) — same rolling-window semantics as Strava Best Efforts / a Wahoo-style power curve.
 *
 * Distance splits only need lat/lon/timestamp and are computed for every ride. The power curve
 * needs a continuous power stream, so callers should only request it when hasPower is true and the
 * datapoints have already been through [com.velometrics.app.data.fitimport.FitImportService]'s
 * power interpolation step.
 */
object BestEffortCalculator {

    // Distance targets in meters, paired with the [BestEffortValues] field each fills.
    private val DISTANCE_TARGETS_M = listOf(25_000.0, 50_000.0, 100_000.0)

    // Power-curve duration buckets in seconds, paired with the [BestEffortValues] field each fills.
    private val POWER_DURATIONS_SEC = listOf(1, 3, 5, 20, 30, 60, 300, 1200, 1800)

    fun compute(datapoints: List<Datapoint>, hasPower: Boolean): BestEffortValues {
        if (datapoints.size < 2) return BestEffortValues()

        val n = datapoints.size
        val cumDistM = DoubleArray(n)
        val elapsedSec = DoubleArray(n)
        val t0 = datapoints[0].timestamp
        for (i in 1 until n) {
            cumDistM[i] = cumDistM[i - 1] + GeoUtils.haversineDistance(
                datapoints[i - 1].lat, datapoints[i - 1].lon, datapoints[i].lat, datapoints[i].lon
            )
            elapsedSec[i] = Duration.between(t0, datapoints[i].timestamp).toMillis() / 1000.0
        }

        val splits = DISTANCE_TARGETS_M.map { target -> bestTimeForDistance(cumDistM, elapsedSec, target) }

        val powers = if (hasPower) {
            val cumEnergy = DoubleArray(n)
            for (i in 1 until n) {
                val dt = elapsedSec[i] - elapsedSec[i - 1]
                val power = (datapoints[i - 1].power ?: 0).toDouble()
                cumEnergy[i] = cumEnergy[i - 1] + power * dt
            }
            POWER_DURATIONS_SEC.map { duration -> bestAvgPowerForDuration(elapsedSec, cumEnergy, duration.toDouble()) }
        } else {
            List(POWER_DURATIONS_SEC.size) { null }
        }

        return BestEffortValues(
            split25kSec = splits[0],
            split50kSec = splits[1],
            split100kSec = splits[2],
            power1s = powers[0],
            power3s = powers[1],
            power5s = powers[2],
            power20s = powers[3],
            power30s = powers[4],
            power1m = powers[5],
            power5m = powers[6],
            power20m = powers[7],
            power30m = powers[8]
        )
    }

    /**
     * Minimum elapsed time (seconds) to cover exactly [targetM], anywhere in the ride, via a
     * two-pointer sweep over the monotonically increasing cumulative-distance array. Interpolates
     * within the segment that crosses the target so the result isn't quantized to sample spacing.
     * Null if the ride never covers [targetM] at all.
     */
    internal fun bestTimeForDistance(cumDistM: DoubleArray, elapsedSec: DoubleArray, targetM: Double): Double? {
        val n = cumDistM.size
        if (n < 2 || cumDistM[n - 1] < targetM) return null

        var best = Double.MAX_VALUE
        var j = 0
        for (i in 0 until n) {
            if (j < i) j = i
            while (j < n && cumDistM[j] - cumDistM[i] < targetM) j++
            if (j >= n) break

            val time = if (j == i) {
                elapsedSec[i]
            } else {
                val neededDist = targetM - (cumDistM[j - 1] - cumDistM[i])
                val segDist = cumDistM[j] - cumDistM[j - 1]
                val frac = if (segDist > 0) (neededDist / segDist).coerceIn(0.0, 1.0) else 0.0
                elapsedSec[j - 1] + frac * (elapsedSec[j] - elapsedSec[j - 1])
            }

            val windowTime = time - elapsedSec[i]
            if (windowTime < best) best = windowTime
        }

        return if (best == Double.MAX_VALUE) null else best
    }

    /**
     * Maximum average power (watts) sustained for exactly [durationSec], anywhere in the ride.
     * [cumEnergy] is the cumulative power*time integral (watt-seconds), assuming power is constant
     * at datapoints[i].power over the interval [timestamp[i], timestamp[i+1]). Interpolates within
     * the segment the window boundary falls in. Null if the ride is shorter than [durationSec].
     */
    internal fun bestAvgPowerForDuration(elapsedSec: DoubleArray, cumEnergy: DoubleArray, durationSec: Double): Int? {
        val n = elapsedSec.size
        if (n < 2 || elapsedSec[n - 1] < durationSec) return null

        var best = 0.0
        var found = false
        var j = 0
        for (i in 0 until n) {
            val targetTime = elapsedSec[i] + durationSec
            if (targetTime > elapsedSec[n - 1]) break
            if (j < i) j = i
            while (j < n && elapsedSec[j] < targetTime) j++
            if (j >= n) break

            val segTime = elapsedSec[j] - elapsedSec[j - 1]
            val frac = if (segTime > 0) ((targetTime - elapsedSec[j - 1]) / segTime).coerceIn(0.0, 1.0) else 0.0
            val energyAtTarget = cumEnergy[j - 1] + frac * (cumEnergy[j] - cumEnergy[j - 1])

            val energy = energyAtTarget - cumEnergy[i]
            val avgPower = energy / durationSec
            if (avgPower > best) {
                best = avgPower
                found = true
            }
        }

        return if (found) best.roundToInt() else null
    }
}
