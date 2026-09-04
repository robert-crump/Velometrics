package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.Datapoint
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.GeoUtils
import com.google.gson.Gson
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class IntervalDetector @Inject constructor() {

    private val gson = Gson()

    fun detect(datapoints: List<Datapoint>, cyclingSessionId: Long, ftp: Int): List<IntervalSession> {
        if (datapoints.isEmpty()) return emptyList()

        val window = CyclingConstants.INTERVAL_ROLLING_WINDOW
        val threshold = ftp * CyclingConstants.INTERVAL_THRESHOLD_FACTOR
        val restTolerance = CyclingConstants.INTERVAL_ALLOWED_REST_SEC
        val minNormalizedDuration = CyclingConstants.INTERVAL_MIN_DURATION_SEC
        val ftpD = ftp.toDouble() // used as Double in normalizedDuration computation

        // Step 1 — Extract power series
        val powers = datapoints.map { it.power ?: 0 }

        // Step 2 — Compute rolling average (O(n) sliding window)
        val rollingAvg = DoubleArray(powers.size)
        var windowSum = 0.0
        for (i in powers.indices) {
            windowSum += powers[i]
            if (i >= window) {
                windowSum -= powers[i - window]
            }
            rollingAvg[i] = if (i >= window - 1) {
                windowSum / window
            } else {
                0.0 // undefined for first (window-1) points — treated as below threshold
            }
        }

        // Step 3 — Find interval candidates
        val candidateSlices = mutableListOf<Pair<Int, Int>>() // (startIdx, endIdx)
        var inInterval = false
        var startIdx: Int? = null
        var lastAboveIdx: Int? = null

        for (i in rollingAvg.indices) {
            if (rollingAvg[i] > threshold) {
                if (!inInterval) {
                    inInterval = true
                    startIdx = i
                }
                lastAboveIdx = i
            } else {
                if (inInterval && lastAboveIdx != null) {
                    if ((i - lastAboveIdx) > restTolerance) {
                        // Step 4 — Finalize interval candidate
                        val endIdx = (lastAboveIdx + window - 1).coerceAtMost(datapoints.size - 1)
                        val slice = datapoints.subList(startIdx!!, endIdx + 1)
                        val avgPower = slice.map { it.power ?: 0 }.average()
                        val normalizedDuration = slice.size * (avgPower / ftpD)

                        if (normalizedDuration >= minNormalizedDuration) {
                            candidateSlices.add(startIdx to endIdx)
                        }

                        inInterval = false
                        startIdx = null
                        lastAboveIdx = null
                    }
                    // else: tolerate the dip, keep interval open
                }
            }
        }

        // Edge case: session ends while in interval
        if (inInterval && startIdx != null && lastAboveIdx != null) {
            val endIdx = (lastAboveIdx + window - 1).coerceAtMost(datapoints.size - 1)
            val slice = datapoints.subList(startIdx, endIdx + 1)
            val avgPower = slice.map { it.power ?: 0 }.average()
            val normalizedDuration = slice.size * (avgPower / ftp)

            if (normalizedDuration >= minNormalizedDuration) {
                candidateSlices.add(startIdx to endIdx)
            }
        }

        // Step 5 — Build IntervalSession objects. nextStartIdx (null for the last interval) feeds
        // restBeforeNextIntervalSec; recovery metrics themselves read forward from `end` into the
        // full datapoints list regardless of how soon the next interval starts (#178).
        return candidateSlices.mapIndexed { i, (start, end) ->
            val nextStartIdx = candidateSlices.getOrNull(i + 1)?.first
            buildIntervalSession(datapoints, start, end, nextStartIdx, cyclingSessionId, ftpD)
        }
    }

    private fun buildIntervalSession(
        datapoints: List<Datapoint>,
        startIdx: Int,
        endIdx: Int,
        nextStartIdx: Int?,
        cyclingSessionId: Long,
        ftp: Double
    ): IntervalSession {
        val slice = datapoints.subList(startIdx, endIdx + 1)

        val first = slice.first()
        val last = slice.last()

        // Duration
        val durationSec = Duration.between(first.timestamp, last.timestamp).seconds.toInt()
            .coerceAtLeast(slice.size) // fallback to slice size if timestamps are weird

        // Power
        val avgPower = slice.map { it.power ?: 0 }.average().roundToInt()

        // Normalized duration
        val durationNormalizedSec = if (ftp > 0) {
            (durationSec * avgPower / ftp).toInt()
        } else durationSec

        // Distance
        var distanceM = 0.0
        for (i in 1 until slice.size) {
            distanceM += GeoUtils.haversineDistance(
                slice[i - 1].lat, slice[i - 1].lon,
                slice[i].lat, slice[i].lon
            )
        }

        // Speed
        val avgSpeedKmh = slice.mapNotNull { it.speedKmh }.let { speeds ->
            if (speeds.isNotEmpty()) speeds.average() else 0.0
        }
        val avgSpeedNormalizedKmh = if (avgPower > 0) {
            avgSpeedKmh / avgPower * ftp
        } else 0.0

        // Direction
        val direction = if (distanceM < 10.0) {
            "unknown"
        } else {
            val bearing = GeoUtils.computeBearing(first.lat, first.lon, last.lat, last.lon)
            when {
                bearing >= 315 || bearing < 45 -> "north"
                bearing >= 45 && bearing < 135 -> "east"
                bearing >= 135 && bearing < 225 -> "south"
                else -> "west"
            }
        }

        // GPS track — round to 6 decimal places
        val gpsTrackPoints = slice.map { dp ->
            listOf(
                (dp.lat * 1_000_000).roundToInt() / 1_000_000.0,
                (dp.lon * 1_000_000).roundToInt() / 1_000_000.0
            )
        }
        val gpsTrack = gson.toJson(gpsTrackPoints)

        // Recovery metrics (#178): read forward from endIdx into the full datapoints list.
        val recovery = computeRecoveryMetrics(datapoints, endIdx)
        val restBeforeNextIntervalSec = nextStartIdx?.let {
            Duration.between(last.timestamp, datapoints[it].timestamp).seconds.toInt().coerceAtLeast(0)
        }

        return IntervalSession(
            id = 0,
            cyclingSessionId = cyclingSessionId,
            startTimestamp = first.timestamp,
            durationSec = durationSec,
            durationNormalizedSec = durationNormalizedSec,
            distanceM = distanceM,
            avgPower = avgPower,
            avgSpeedKmh = (avgSpeedKmh * 10).roundToInt() / 10.0,
            avgSpeedNormalizedKmh = (avgSpeedNormalizedKmh * 10).roundToInt() / 10.0,
            direction = direction,
            startLat = first.lat,
            startLon = first.lon,
            endLat = last.lat,
            endLon = last.lon,
            gpsTrack = gpsTrack,
            hrr60 = recovery.hrr60,
            hrr30 = recovery.hrr30,
            avgPower60sAfter = recovery.avgPower60sAfter,
            avgPower30sAfter = recovery.avgPower30sAfter,
            restBeforeNextIntervalSec = restBeforeNextIntervalSec
        )
    }

    private data class RecoveryMetrics(
        val hrr60: Int?,
        val hrr30: Int?,
        val avgPower60sAfter: Int?,
        val avgPower30sAfter: Int?
    )

    /**
     * Reads forward from [endIdx] into the full [datapoints] list (bounded only by session end,
     * never by how soon the next interval starts) to compute HR recovery and post-interval power.
     * hrr60/hrr30 are the HR drop between the interval's last reading and the reading nearest
     * [CyclingConstants.INTERVAL_HRR60_WINDOW_SEC]/[CyclingConstants.INTERVAL_HRR30_WINDOW_SEC]
     * later; avgPower60sAfter/avgPower30sAfter are the mean power over those same windows. Any of
     * the four is null when the datapoints list runs out before the target offset is reached
     * (session ended too soon for a true fixed-duration reading) or the needed HR sample(s) are
     * missing/zero -- "insufficient data", independent of the session-level hasHR flag.
     */
    private fun computeRecoveryMetrics(datapoints: List<Datapoint>, endIdx: Int): RecoveryMetrics {
        val endDp = datapoints[endIdx]
        val hrAtEnd = endDp.heartRate?.takeIf { it > 0 }

        val hr60 = hrAtOffset(datapoints, endIdx, CyclingConstants.INTERVAL_HRR60_WINDOW_SEC)
        val hr30 = hrAtOffset(datapoints, endIdx, CyclingConstants.INTERVAL_HRR30_WINDOW_SEC)
        val hrr60 = if (hrAtEnd != null && hr60 != null) hrAtEnd - hr60 else null
        val hrr30 = if (hrAtEnd != null && hr30 != null) hrAtEnd - hr30 else null

        val avgPower60sAfter = avgPowerOverWindow(datapoints, endIdx, CyclingConstants.INTERVAL_HRR60_WINDOW_SEC)
        val avgPower30sAfter = avgPowerOverWindow(datapoints, endIdx, CyclingConstants.INTERVAL_HRR30_WINDOW_SEC)

        return RecoveryMetrics(hrr60, hrr30, avgPower60sAfter, avgPower30sAfter)
    }

    /**
     * Mean power over the [windowSec] immediately after [endIdx] -- only counted once a
     * datapoint at/past the target offset is actually reached, so a session that ends mid-window
     * yields null, not a partial-window average masquerading as the full-duration figure.
     */
    private fun avgPowerOverWindow(datapoints: List<Datapoint>, endIdx: Int, windowSec: Long): Int? {
        val endTime = datapoints[endIdx].timestamp
        var reachedWindowEnd = false
        val windowPowers = mutableListOf<Int>()
        var j = endIdx + 1
        while (j < datapoints.size) {
            val elapsedSec = Duration.between(endTime, datapoints[j].timestamp).seconds
            if (elapsedSec > windowSec) break
            windowPowers.add(datapoints[j].power ?: 0)
            if (elapsedSec >= windowSec) reachedWindowEnd = true
            j++
        }
        return if (reachedWindowEnd && windowPowers.isNotEmpty()) windowPowers.average().roundToInt() else null
    }

    /**
     * The heart rate reading at the first datapoint at/past [offsetSec] after [endIdx], or null if
     * the datapoints list runs out first (session ended before that offset was reached) or that
     * reading is missing/zero.
     */
    private fun hrAtOffset(datapoints: List<Datapoint>, endIdx: Int, offsetSec: Long): Int? {
        val endTime = datapoints[endIdx].timestamp
        var j = endIdx + 1
        while (j < datapoints.size) {
            if (Duration.between(endTime, datapoints[j].timestamp).seconds >= offsetSec) {
                return datapoints[j].heartRate?.takeIf { it > 0 }
            }
            j++
        }
        return null
    }
}
