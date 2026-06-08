package com.velometrics.app.ui.screens.repeatedintervaldetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.domain.repository.RepeatedIntervalRepository
import com.velometrics.app.util.PolylineDecoder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject

data class RepeatedIntervalDetailUiState(
    val repeatedInterval: RepeatedInterval? = null,
    val isLoading: Boolean = true,
    val avgDurationSec: Int = 0,
    val avgDistanceM: Double = 0.0,
    val avgSpeedKmh: Double = 0.0,
    val avgPowerW: Int = 0,
    /** Decoded edge-list geometry, concatenated in sequence order, for map preview. */
    val trackPoints: List<LatLng> = emptyList()
)

@HiltViewModel
class RepeatedIntervalDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RepeatedIntervalRepository
) : ViewModel() {

    private val intervalId: Long = checkNotNull(savedStateHandle["intervalId"])

    val uiState: StateFlow<RepeatedIntervalDetailUiState> = repository
        .getRepeatedIntervalById(intervalId)
        .map { repeatedInterval ->
            if (repeatedInterval == null) {
                RepeatedIntervalDetailUiState(isLoading = false)
            } else {
                val intervals = repeatedInterval.intervals
                val count = intervals.size

                val avgDuration = if (count > 0) intervals.sumOf { it.durationNormalizedSec } / count else 0
                val avgDistance = if (count > 0) intervals.sumOf { it.distanceM } / count else 0.0
                val avgSpeed = if (count > 0) intervals.sumOf { it.avgSpeedNormalizedKmh } / count else 0.0
                val avgPower = if (count > 0) intervals.sumOf { it.avgPower } / count else 0

                val trackPoints = repeatedInterval.edges.flatMap { edge ->
                    PolylineDecoder.decode(edge.geometryEncoded)
                }

                RepeatedIntervalDetailUiState(
                    repeatedInterval = repeatedInterval,
                    isLoading = false,
                    avgDurationSec = avgDuration,
                    avgDistanceM = avgDistance,
                    avgSpeedKmh = avgSpeed,
                    avgPowerW = avgPower,
                    trackPoints = trackPoints
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RepeatedIntervalDetailUiState())

    fun rename(newName: String) {
        viewModelScope.launch {
            repository.renameRepeatedInterval(intervalId, newName.trim().ifEmpty { "Repeated Interval" })
        }
    }
}
