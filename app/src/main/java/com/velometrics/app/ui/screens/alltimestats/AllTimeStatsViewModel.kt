package com.velometrics.app.ui.screens.alltimestats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.domain.model.BestEffortRecord
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.repository.BestEffortRepository
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.util.FormatUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.Year
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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

@HiltViewModel
class AllTimeStatsViewModel @Inject constructor(
    private val sessionRepository: CyclingSessionRepository,
    private val bestEffortRepository: BestEffortRepository
) : ViewModel() {

    val uiState: StateFlow<AllTimeStatsUiState> = sessionRepository.getAllSessions()
        .map { sessions -> buildUiState(sessions, bestEffortRepository.getAllWithSessionDate()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AllTimeStatsUiState())

    private fun buildUiState(sessions: List<CyclingSession>, bestEfforts: List<BestEffortRecord>): AllTimeStatsUiState {
        val bestTrio = listOf(
            longestRideEntry(sessions),
            biggestClimbEntry(sessions),
            longestDurationEntry(sessions)
        )

        val distanceSplits = listOf(
            splitEntry("25 km", "No 25k effort recorded yet", bestEfforts) { it.split25kSec },
            splitEntry("50 km", "No 50k effort recorded yet", bestEfforts) { it.split50kSec },
            splitEntry("100 km", "No 100k effort recorded yet", bestEfforts) { it.split100kSec }
        )

        val powerCurve = listOf(
            powerCurvePoint(1, "1s", bestEfforts) { it.power1s },
            powerCurvePoint(3, "3s", bestEfforts) { it.power3s },
            powerCurvePoint(5, "5s", bestEfforts) { it.power5s },
            powerCurvePoint(20, "20s", bestEfforts) { it.power20s },
            powerCurvePoint(30, "30s", bestEfforts) { it.power30s },
            powerCurvePoint(60, "1min", bestEfforts) { it.power1m },
            powerCurvePoint(300, "5min", bestEfforts) { it.power5m },
            powerCurvePoint(1200, "20min", bestEfforts) { it.power20m },
            powerCurvePoint(1800, "30min", bestEfforts) { it.power30m }
        )

        val yearStats = buildYearStats(sessions)

        return AllTimeStatsUiState(
            isLoading = false,
            hasAnySessions = sessions.isNotEmpty(),
            bestTrio = bestTrio,
            distanceSplits = distanceSplits,
            powerCurve = powerCurve,
            hasAnyPowerCurveData = powerCurve.any { it.watts != null },
            yearStats = yearStats,
            powerSpeedPoints = buildPowerSpeedPoints(sessions)
        )
    }

    private fun buildPowerSpeedPoints(sessions: List<CyclingSession>): List<PowerSpeedPoint> {
        val allPoints = sessions
            .filter { it.hasPower && it.averagePower != null && it.elevationGainM != null && it.netDurationSec > 0 && it.distanceKm > 0 }
            .map { s ->
                val speed = (s.distanceKm / s.netDurationSec * 3600).toFloat()
                val elevGainPer100km = s.elevationGainM!! / s.distanceKm * 100
                PowerSpeedPoint(
                    avgPowerW = s.averagePower!!.toFloat(),
                    avgSpeedKmh = speed,
                    elevationBucket = elevationBucketFor(elevGainPer100km)
                )
            }

        // Keep only representative rides: drop outliers whose power or speed falls
        // outside the 2.5th-97.5th percentile range of all valid rides.
        val powerRange = percentileRange(allPoints.map { it.avgPowerW })
        val speedRange = percentileRange(allPoints.map { it.avgSpeedKmh })
        return allPoints.filter { it.avgPowerW in powerRange && it.avgSpeedKmh in speedRange }
    }

    private fun percentileRange(values: List<Float>): ClosedFloatingPointRange<Float> {
        val sorted = values.sorted()
        return percentile(sorted, 2.5)..percentile(sorted, 97.5)
    }

    private fun percentile(sorted: List<Float>, p: Double): Float {
        if (sorted.isEmpty()) return 0f
        if (sorted.size == 1) return sorted[0]
        val rank = p / 100.0 * (sorted.size - 1)
        val lowerIdx = kotlin.math.floor(rank).toInt()
        val upperIdx = kotlin.math.ceil(rank).toInt()
        if (lowerIdx == upperIdx) return sorted[lowerIdx]
        val frac = (rank - lowerIdx).toFloat()
        return sorted[lowerIdx] + (sorted[upperIdx] - sorted[lowerIdx]) * frac
    }

    private fun elevationBucketFor(elevGainPer100km: Double): Int = when {
        elevGainPer100km < 200 -> 0
        elevGainPer100km < 800 -> 1
        elevGainPer100km < 1400 -> 2
        elevGainPer100km < 2000 -> 3
        else -> 4
    }

    private fun longestRideEntry(sessions: List<CyclingSession>): RecordEntry {
        val best = sessions.maxByOrNull { it.distanceKm }
        return RecordEntry(
            label = "Longest ride",
            value = best?.let { FormatUtils.formatDistanceRounded(it.distanceKm) },
            emptyMessage = "No rides recorded yet",
            sessionId = best?.id,
            date = best?.let { FormatUtils.formatDate(it.sessionStart) }
        )
    }

    private fun biggestClimbEntry(sessions: List<CyclingSession>): RecordEntry {
        val best = sessions.filter { it.elevationGainM != null }.maxByOrNull { it.elevationGainM!! }
        return RecordEntry(
            label = "Biggest climb",
            value = best?.elevationGainM?.let { FormatUtils.formatElevationGainRounded(it) },
            emptyMessage = "No elevation data recorded yet",
            sessionId = best?.id,
            date = best?.let { FormatUtils.formatDate(it.sessionStart) }
        )
    }

    // Contiguous range from the current calendar year down to the earliest year with a
    // recorded session, so the year-navigation arrows always step one calendar year at a
    // time and the view can start on the current year even before any ride is logged for it.
    private fun buildYearStats(sessions: List<CyclingSession>): List<YearStat> {
        if (sessions.isEmpty()) return emptyList()

        val byYear = sessions.groupBy { it.sessionStart.atZone(ZoneId.systemDefault()).year }
        val currentYear = Year.now(ZoneId.systemDefault()).value
        val earliestYear = minOf(byYear.keys.min(), currentYear)

        return (currentYear downTo earliestYear).map { year ->
            val yearSessions = byYear[year].orEmpty()
            YearStat(
                year = year,
                rideCount = yearSessions.size,
                totalDistanceKm = yearSessions.sumOf { it.distanceKm },
                totalElevationGainM = yearSessions.sumOf { it.elevationGainM ?: 0.0 },
                totalNetDurationSec = yearSessions.sumOf { it.netDurationSec }
            )
        }
    }

    private fun longestDurationEntry(sessions: List<CyclingSession>): RecordEntry {
        val best = sessions.maxByOrNull { it.netDurationSec }
        return RecordEntry(
            label = "Longest duration",
            value = best?.let { FormatUtils.formatDuration(it.netDurationSec) },
            emptyMessage = "No rides recorded yet",
            sessionId = best?.id,
            date = best?.let { FormatUtils.formatDate(it.sessionStart) }
        )
    }

    private fun splitEntry(
        label: String,
        emptyMessage: String,
        bestEfforts: List<BestEffortRecord>,
        selector: (BestEffortRecord) -> Double?
    ): RecordEntry {
        val best = bestEfforts.mapNotNull { record -> selector(record)?.let { it to record } }
            .minByOrNull { it.first }
        return RecordEntry(
            label = label,
            value = best?.let { FormatUtils.formatDuration(it.first.roundToInt()) },
            emptyMessage = emptyMessage,
            sessionId = best?.second?.sessionId,
            date = best?.second?.sessionStart?.let { FormatUtils.formatDate(Instant.ofEpochMilli(it)) }
        )
    }

    private fun powerCurvePoint(
        durationSec: Int,
        label: String,
        bestEfforts: List<BestEffortRecord>,
        selector: (BestEffortRecord) -> Int?
    ): PowerCurvePoint {
        val best = bestEfforts.mapNotNull { record -> selector(record)?.let { it to record } }
            .maxByOrNull { it.first }
        return PowerCurvePoint(
            durationSec = durationSec,
            label = label,
            watts = best?.first,
            sessionId = best?.second?.sessionId,
            date = best?.second?.sessionStart?.let { FormatUtils.formatDate(Instant.ofEpochMilli(it)) }
        )
    }
}
