package com.velometrics.app.fakes

import com.velometrics.app.data.cache.TrainingLoadCache
import com.velometrics.app.domain.model.TrainingLoadUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeTrainingLoadCache(
    initial: TrainingLoadUiState = TrainingLoadUiState()
) : TrainingLoadCache {
    private val _uiState = MutableStateFlow(initial)
    override val uiState: StateFlow<TrainingLoadUiState> = _uiState

    fun emit(state: TrainingLoadUiState) {
        _uiState.value = state
    }
}
