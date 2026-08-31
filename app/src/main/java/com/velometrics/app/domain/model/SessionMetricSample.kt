package com.velometrics.app.domain.model

/**
 * Lean per-session projection carrying only the scalar fields needed to compute rolling medians —
 * skips gpsTrack and the JSON histogram/zone columns that mapping a full [CyclingSession] would
 * otherwise Gson-parse for every row, which gets expensive once the query spans a whole ride history.
 */
data class SessionMetricSample(
    val id: Long,
    val netDurationSec: Int,
    val distanceKm: Double,
    val averagePower: Int?,
    val normalizedPower: Int?,
    val fatEfficiencyScore: Int?,
    val avgHeartRate: Int?,
    val elevationGainM: Double?,
    val fatBurnedGrams: Double?,
    val carbsBurnedGrams: Double?,
    val cardiacDriftPercent: Double?,
    val hasPower: Boolean
)
