package com.velometrics.app.ui.screens.repeatedintervals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.data.cache.RepeatedIntervalsCache
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.domain.service.IntervalClusteringService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RepeatedIntervalSortOrder {
    DISTANCE_ASC, DISTANCE_DESC,
    FREQUENCY_ASC, FREQUENCY_DESC,
    NAME_ASC, NAME_DESC
}

@HiltViewModel
class RepeatedIntervalsViewModel @Inject constructor(
    cache: RepeatedIntervalsCache,
    private val clusteringService: IntervalClusteringService
) : ViewModel() {

    private val _sortOrder = MutableStateFlow(RepeatedIntervalSortOrder.FREQUENCY_DESC)
    val sortOrder: StateFlow<RepeatedIntervalSortOrder> = _sortOrder.asStateFlow()

    val isLoading: StateFlow<Boolean> = cache.isLoading

    val repeatedIntervals: StateFlow<List<RepeatedInterval>> = cache.repeatedIntervals
        .combine(_sortOrder) { list, order ->
            when (order) {
                RepeatedIntervalSortOrder.DISTANCE_ASC -> list.sortedBy { it.distanceM }
                RepeatedIntervalSortOrder.DISTANCE_DESC -> list.sortedByDescending { it.distanceM }
                RepeatedIntervalSortOrder.FREQUENCY_ASC -> list.sortedBy { it.intervals.size }
                RepeatedIntervalSortOrder.FREQUENCY_DESC -> list.sortedByDescending { it.intervals.size }
                RepeatedIntervalSortOrder.NAME_ASC -> list.sortedBy { it.name.lowercase() }
                RepeatedIntervalSortOrder.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun setSortOrder(order: RepeatedIntervalSortOrder) {
        _sortOrder.value = order
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                clusteringService.runClustering()
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
