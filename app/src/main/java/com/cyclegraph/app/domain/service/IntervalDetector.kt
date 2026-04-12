package com.cyclegraph.app.domain.service

import com.cyclegraph.app.domain.model.Datapoint
import com.cyclegraph.app.domain.model.IntervalSession
import com.cyclegraph.app.util.CyclingConstants
import com.cyclegraph.app.util.GeoUtils
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

        // Step 5 — Build IntervalSession objects
        return candidateSlices.map { (start, end) ->
            buildIntervalSession(datapoints.subList(start, end + 1), cyclingSessionId, ftpD)
        }
    }

    private fun buildIntervalSession(
        slice: List<Datapoint>,
        cyclingSessionId: Long,
        ftp: Double
    ): IntervalSession {

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
            prototypeRouteId = null
        )
    }
}
