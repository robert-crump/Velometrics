package com.velometrics.app.domain.model

data class RecordEntry(
    val label: String,
    val value: String?,
    val emptyMessage: String,
    val sessionId: Long?,
    val date: String?
)

data class PowerCurvePoint(
    val durationSec: Int,
    val label: String,
    val watts: Int?,
    val sessionId: Long?,
    val date: String?
)

data class YearStat(
    val year: Int,
    val rideCount: Int,
    val totalDistanceKm: Double,
    val totalElevationGainM: Double,
    val totalNetDurationSec: Int
)

// elevationBucket indexes the 5 climb-density ranges (0-200, 200-800, 800-1400, 1400-2000,
// >2000 m/100km), lower-inclusive, so the UI can map it to one of 5 shades of a single color.
data class PowerSpeedPoint(
    val avgPowerW: Float,
    val avgSpeedKmh: Float,
    val elevationBucket: Int
)

data class AllTimeStatsUiState(
    val isLoading: Boolean = true,
    val hasAnySessions: Boolean = false,
    val bestTrio: List<RecordEntry> = emptyList(),
    val distanceSplits: List<RecordEntry> = emptyList(),
    val powerCurve: List<PowerCurvePoint> = emptyList(),
    val hasAnyPowerCurveData: Boolean = false,
    val yearStats: List<YearStat> = emptyList(),
    val powerSpeedPoints: List<PowerSpeedPoint> = emptyList()
)
