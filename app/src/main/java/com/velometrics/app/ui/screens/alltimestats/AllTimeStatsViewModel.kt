package com.velometrics.app.ui.screens.alltimestats

import androidx.lifecycle.ViewModel
import com.velometrics.app.data.cache.AllTimeStatsCache
import com.velometrics.app.domain.model.AllTimeStatsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class AllTimeStatsViewModel @Inject constructor(
    cache: AllTimeStatsCache
) : ViewModel() {

    val uiState: StateFlow<AllTimeStatsUiState> = cache.uiState
}
