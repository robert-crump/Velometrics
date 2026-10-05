package com.velometrics.app.data.fitimport

import com.velometrics.app.domain.model.Datapoint
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.GeoUtils
import java.time.Duration
import kotlin.math.abs

/**
 * The FIT import's GPS quality filter, in line with Ride-Graph's `quality_filter.py` (#233). Runs
 * before everything else, so distance, map track, clustering and Speed IQ all see the kept stream.
 */
object GpsQualityFilter {

    data class Result(val kept: List<Datapoint>, val discardCounts: Map<String, Int>) {
        fun logLine(): String = "GPS filter: kept=${kept.size}, " +
            DISCARD_REASONS.joinToString(", ") { "discard${it.replaceFirstChar(Char::uppercase)}=${discardCounts.getValue(it)}" }
    }

    val DISCARD_REASONS = listOf("zero", "speed", "power", "accuracy", "leap", "implied", "accel")

    fun filter(datapoints: List<Datapoint>): Result {
        val kept = mutableListOf<Datapoint>()
        val discardCounts = DISCARD_REASONS.associateWith { 0 }.toMutableMap()
        fun discard(reason: String) { discardCounts[reason] = discardCounts.getValue(reason) + 1 }

        var lastValid: Datapoint? = null
        // GPS-implied speed of the segment ending at lastValid; null after a gap or at the start
        var lastImpliedMps: Double? = null

        for (dp in datapoints) {
            if (dp.lat == 0.0 || dp.lon == 0.0) { discard("zero"); continue }
            if (dp.speedKmh != null && dp.speedKmh > CyclingConstants.MAX_REALISTIC_SPEED_KMH) { discard("speed"); continue }
            if (dp.power != null && dp.power > CyclingConstants.MAX_REALISTIC_POWER) { discard("power"); continue }
            // Skipped entirely for files without the field
            if (dp.gpsAccuracyM != null && dp.gpsAccuracyM > CyclingConstants.GPS_MAX_ACCURACY_M) { discard("accuracy"); continue }

            var impliedMps: Double? = null
            if (lastValid != null) {
                val distM = GeoUtils.haversineDistance(lastValid.lat, lastValid.lon, dp.lat, dp.lon)
                val elapsedSec = Duration.between(lastValid.timestamp, dp.timestamp).seconds.toDouble()

                if (distM > CyclingConstants.GPS_LEAP_MAX_DISTANCE_M &&
                    elapsedSec < CyclingConstants.GPS_LEAP_MAX_TIME_SEC) { discard("leap"); continue }

                if (elapsedSec > 0) {
                    val mps = distM / elapsedSec
                    if (mps * CyclingConstants.MTS_PER_SEC_TO_KMH > CyclingConstants.GPS_IMPLIED_MAX_SPEED_KMH) {
                        discard("implied"); continue
                    }
                    // Across longer gaps the implied speed is artificially low, so no acceleration there
                    if (elapsedSec <= CyclingConstants.GPS_ACCELERATION_MAX_GAP_SEC) {
                        val previousMps = lastImpliedMps
                        if (previousMps != null &&
                            abs(mps - previousMps) / elapsedSec > CyclingConstants.GPS_MAX_ACCELERATION_MPS2) {
                            discard("accel"); continue
                        }
                        impliedMps = mps
                    }
                }
            }

            kept.add(dp)
            lastValid = dp
            lastImpliedMps = impliedMps
        }

        return Result(kept, discardCounts)
    }
}
