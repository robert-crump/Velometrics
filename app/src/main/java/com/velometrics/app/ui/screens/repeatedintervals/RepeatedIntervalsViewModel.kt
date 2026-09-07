package com.velometrics.app.ui.screens.repeatedintervals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.data.cache.RepeatedIntervalsCache
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.domain.service.IntervalClusteringService
import com.velometrics.app.ui.components.RepeatedEntrySortOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RepeatedIntervalsViewModel @Inject constructor(
    cache: RepeatedIntervalsCache,
    private val clusteringService: IntervalClusteringService
) : ViewModel() {

    private val _sortOrder = MutableStateFlow(RepeatedEntrySortOrder.FREQUENCY_DESC)
    val sortOrder: StateFlow<RepeatedEntrySortOrder> = _sortOrder.asStateFlow()

    val isLoading: StateFlow<Boolean> = cache.isLoading

    val repeatedIntervals: StateFlow<List<RepeatedInterval>> = cache.repeatedIntervals
        .combine(_sortOrder) { list, order ->
            when (order) {
                RepeatedEntrySortOrder.DISTANCE_ASC -> list.sortedBy { it.distanceM }
                RepeatedEntrySortOrder.DISTANCE_DESC -> list.sortedByDescending { it.distanceM }
                RepeatedEntrySortOrder.FREQUENCY_ASC -> list.sortedBy { it.intervals.size }
                RepeatedEntrySortOrder.FREQUENCY_DESC -> list.sortedByDescending { it.intervals.size }
                RepeatedEntrySortOrder.NAME_ASC -> list.sortedBy { it.name.lowercase() }
                RepeatedEntrySortOrder.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun setSortOrder(order: RepeatedEntrySortOrder) {
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
