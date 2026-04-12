package com.cyclegraph.app.ui.screens.repeatedroute

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cyclegraph.app.domain.model.CyclingSession
import com.cyclegraph.app.domain.model.RepeatedRoute
import com.cyclegraph.app.domain.repository.RepeatedRouteRepository
import com.cyclegraph.app.util.CyclingConstants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Linear regression model: average power (W) → estimated net duration (seconds).
 * @param sliderValues discrete power values (10 W steps) the slider can select
 * @param defaultIndex second-lowest position (index 1, or 0 if only one value)
 * @param slope regression slope (seconds / watt)
 * @param intercept regression intercept (seconds)
 * @param medianDistanceKm median route distance used to estimate average speed
 */
data class PowerDurationModel(
    val sliderValues: List<Int>,
    val defaultIndex: Int,
    val slope: Double,
    val intercept: Double,
    val medianDistanceKm: Double
) {
    /** Returns the estimated net duration in seconds for [power] W, or null if negative. */
    fun estimatedSec(power: Int): Int? {
        val v = slope * power + intercept
        return if (v > 0) v.toInt() else null
    }
}

data class RepeatedRouteDetailUiState(
    val route: RepeatedRoute? = null,
    val isLoading: Boolean = true,
    val avgNetDurationSec: Int = 0,
    val avgDistanceKm: Double = 0.0,
    val avgSpeedKmh: Double = 0.0,
    val avgPowerW: Int? = null,
    val avgNormalizedPowerW: Int? = null,
    val speedPowerPoints: List<Pair<Float, Float>> = emptyList(),
    val speedNpPoints: List<Pair<Float, Float>> = emptyList(),
    val showPowerPlots: Boolean = false,
    val powerDurationModel: PowerDurationModel? = null,
    /** Average speed distribution percentages (0–100) per bin, across all sessions. */
    val avgSpeedHistogram: Map<String, Float> = emptyMap()
)

@HiltViewModel
class RepeatedRouteDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RepeatedRouteRepository
) : ViewModel() {

    private val routeId: Long = checkNotNull(savedStateHandle["routeId"])

    val uiState: StateFlow<RepeatedRouteDetailUiState> = repository
        .getRouteById(routeId)
        .map { route ->
            if (route == null) {
                RepeatedRouteDetailUiState(isLoading = false)
            } else {
                val sessions = route.sessions
                val count = sessions.size

                val avgDuration = if (count > 0) sessions.sumOf { it.netDurationSec } / count else 0
                val avgDist = if (count > 0) sessions.sumOf { it.distanceKm } / count else 0.0
                val avgSpeed = if (avgDuration > 0) avgDist / avgDuration * 3600.0 else 0.0

                val powerSessions = sessions.filter { it.hasPower && it.averagePower != null }
                val avgPower = if (powerSessions.isNotEmpty())
                    powerSessions.sumOf { it.averagePower!! } / powerSessions.size
                else null

                val npSessions = sessions.filter { it.hasPower && it.normalizedPower != null }
                val avgNp = if (npSessions.isNotEmpty())
                    npSessions.sumOf { it.normalizedPower!! } / npSessions.size
                else null

                val showPlots = sessions.any { it.hasPower }

                // x = power, y = speed
                val speedPowerPoints = if (showPlots) {
                    powerSessions.map { s ->
                        val spd = if (s.netDurationSec > 0)
                            (s.distanceKm / s.netDurationSec * 3600).toFloat()
                        else 0f
                        Pair(s.averagePower!!.toFloat(), spd)
                    }
                } else emptyList()

                val speedNpPoints = if (showPlots) {
                    npSessions.map { s ->
                        val spd = if (s.netDurationSec > 0)
                            (s.distanceKm / s.netDurationSec * 3600).toFloat()
                        else 0f
                        Pair(s.normalizedPower!!.toFloat(), spd)
                    }
                } else emptyList()

                // Average speed histogram: mean percentage per bin across all sessions
                val bins = CyclingConstants.SPEED_HISTOGRAM_BINS.map { it.first }
                val avgSpeedHistogram = bins.associateWith { binName ->
                    val perSession = sessions.mapNotNull { s ->
                        s.speedHistogram?.let { hist ->
                            val total = hist.values.sum().toFloat().coerceAtLeast(1f)
                            (hist[binName] ?: 0).toFloat() / total * 100f
                        }
                    }
                    if (perSession.isNotEmpty()) perSession.average().toFloat() else 0f
                }

                RepeatedRouteDetailUiState(
                    route = route,
                    isLoading = false,
                    avgNetDurationSec = avgDuration,
                    avgDistanceKm = avgDist,
                    avgSpeedKmh = avgSpeed,
                    avgPowerW = avgPower,
                    avgNormalizedPowerW = avgNp,
                    speedPowerPoints = speedPowerPoints,
                    speedNpPoints = speedNpPoints,
                    showPowerPlots = showPlots,
                    powerDurationModel = buildPowerDurationModel(powerSessions, sessions),
                    avgSpeedHistogram = avgSpeedHistogram
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RepeatedRouteDetailUiState())

    fun renameRoute(newName: String) {
        viewModelScope.launch {
            repository.renameRoute(routeId, newName.trim().ifEmpty { "Repeated Route" })
        }
    }

    private fun buildPowerDurationModel(
        powerSessions: List<CyclingSession>,
        allSessions: List<CyclingSession>
    ): PowerDurationModel? {
        if (powerSessions.size < 2) return null

        val xs = powerSessions.map { it.averagePower!!.toDouble() }
        val ys = powerSessions.map { it.netDurationSec.toDouble() }
        val n = xs.size.toDouble()
        val xMean = xs.sum() / n
        val yMean = ys.sum() / n
        val sxx = xs.sumOf { (it - xMean) * (it - xMean) }
        if (sxx == 0.0) return null  // all sessions have identical power — no useful slope

        val sxy = xs.zip(ys).sumOf { (x, y) -> (x - xMean) * (y - yMean) }
        val slope = sxy / sxx
        val intercept = yMean - slope * xMean

        val minPower = xs.min().toInt()
        val maxPower = xs.max().toInt()

        // round min DOWN to nearest 10 W after subtracting 10
        val sliderMin = ((minPower - 10) / 10) * 10
        // round max UP to nearest 10 W after adding 10
        val raw = maxPower + 10
        val sliderMax = if (raw % 10 == 0) raw else (raw / 10 + 1) * 10

        val sliderValues = (sliderMin..sliderMax step 10).toList()
        val defaultIndex = if (sliderValues.size >= 2) 1 else 0

        // Median distance across all sessions for the speed estimate
        val sortedDists = allSessions.map { it.distanceKm }.sorted()
        val medianDistKm = if (sortedDists.isEmpty()) 0.0
        else if (sortedDists.size % 2 == 0)
            (sortedDists[sortedDists.size / 2 - 1] + sortedDists[sortedDists.size / 2]) / 2.0
        else sortedDists[sortedDists.size / 2]

        return PowerDurationModel(sliderValues, defaultIndex, slope, intercept, medianDistKm)
    }
}
