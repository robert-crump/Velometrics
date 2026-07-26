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

data class AllTimeStatsUiState(
    val isLoading: Boolean = true,
    val hasAnySessions: Boolean = false,
    val bestTrio: List<RecordEntry> = emptyList(),
    val distanceSplits: List<RecordEntry> = emptyList(),
    val powerCurve: List<PowerCurvePoint> = emptyList(),
    val hasAnyPowerCurveData: Boolean = false,
    val yearStats: List<YearStat> = emptyList()
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

        val yearStats = sessions
            .groupBy { it.sessionStart.atZone(ZoneId.systemDefault()).year }
            .map { (year, yearSessions) ->
                YearStat(
                    year = year,
                    rideCount = yearSessions.size,
                    totalDistanceKm = yearSessions.sumOf { it.distanceKm },
                    totalElevationGainM = yearSessions.sumOf { it.elevationGainM ?: 0.0 },
                    totalNetDurationSec = yearSessions.sumOf { it.netDurationSec }
                )
            }
            .sortedByDescending { it.year }

        return AllTimeStatsUiState(
            isLoading = false,
            hasAnySessions = sessions.isNotEmpty(),
            bestTrio = bestTrio,
            distanceSplits = distanceSplits,
            powerCurve = powerCurve,
            hasAnyPowerCurveData = powerCurve.any { it.watts != null },
            yearStats = yearStats
        )
    }

    private fun longestRideEntry(sessions: List<CyclingSession>): RecordEntry {
        val best = sessions.maxByOrNull { it.distanceKm }
        return RecordEntry(
            label = "Longest ride",
            value = best?.let { FormatUtils.formatDistance(it.distanceKm) },
            emptyMessage = "No rides recorded yet",
            sessionId = best?.id,
            date = best?.let { FormatUtils.formatDate(it.sessionStart) }
        )
    }

    private fun biggestClimbEntry(sessions: List<CyclingSession>): RecordEntry {
        val best = sessions.filter { it.elevationGainM != null }.maxByOrNull { it.elevationGainM!! }
        return RecordEntry(
            label = "Biggest climb",
            value = best?.elevationGainM?.let { FormatUtils.formatElevationGain(it) },
            emptyMessage = "No elevation data recorded yet",
            sessionId = best?.id,
            date = best?.let { FormatUtils.formatDate(it.sessionStart) }
        )
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
