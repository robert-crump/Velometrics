package com.velometrics.app.ui.screens.trainingload

import androidx.lifecycle.ViewModel
import com.velometrics.app.data.cache.TrainingLoadCache
import com.velometrics.app.domain.model.TrainingLoadUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class TrainingLoadViewModel @Inject constructor(
    cache: TrainingLoadCache
) : ViewModel() {

    val uiState: StateFlow<TrainingLoadUiState> = cache.uiState
}
