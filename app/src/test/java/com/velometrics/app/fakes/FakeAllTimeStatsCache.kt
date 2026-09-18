package com.velometrics.app.fakes

import com.velometrics.app.data.cache.AllTimeStatsCache
import com.velometrics.app.domain.model.AllTimeStatsUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeAllTimeStatsCache(
    initial: AllTimeStatsUiState = AllTimeStatsUiState()
) : AllTimeStatsCache {
    private val _uiState = MutableStateFlow(initial)
    override val uiState: StateFlow<AllTimeStatsUiState> = _uiState

    fun emit(state: AllTimeStatsUiState) {
        _uiState.value = state
    }
}
