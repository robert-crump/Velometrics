package com.cyclegraph.app.ui.screens.routeplanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cyclegraph.app.domain.model.RepeatedRoute
import com.cyclegraph.app.domain.repository.RepeatedRouteRepository
import com.cyclegraph.app.domain.service.RouteClusteringService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RouteSortOrder {
    DISTANCE_ASC, DISTANCE_DESC,
    FREQUENCY_ASC, FREQUENCY_DESC,
    NAME_ASC, NAME_DESC
}

@HiltViewModel
class RepeatedRoutesViewModel @Inject constructor(
    repository: RepeatedRouteRepository,
    private val clusteringService: RouteClusteringService
) : ViewModel() {

    private val _sortOrder = MutableStateFlow(RouteSortOrder.FREQUENCY_DESC)
    val sortOrder: StateFlow<RouteSortOrder> = _sortOrder.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val routes: StateFlow<List<RepeatedRoute>> = repository
        .getAllRoutes()
        .onEach { _isLoading.value = false }
        .combine(_sortOrder) { list, order ->
            when (order) {
                RouteSortOrder.DISTANCE_ASC -> list.sortedBy { r ->
                    if (r.sessions.isEmpty()) 0.0 else r.sessions.sumOf { it.distanceKm } / r.sessions.size
                }
                RouteSortOrder.DISTANCE_DESC -> list.sortedByDescending { r ->
                    if (r.sessions.isEmpty()) 0.0 else r.sessions.sumOf { it.distanceKm } / r.sessions.size
                }
                RouteSortOrder.FREQUENCY_ASC -> list.sortedBy { it.sessions.size }
                RouteSortOrder.FREQUENCY_DESC -> list.sortedByDescending { it.sessions.size }
                RouteSortOrder.NAME_ASC -> list.sortedBy { it.name.lowercase() }
                RouteSortOrder.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun setSortOrder(order: RouteSortOrder) {
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
