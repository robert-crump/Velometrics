package com.velometrics.app.ui.screens.info

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.domain.model.GraphMetadata
import com.velometrics.app.domain.repository.MapGraphRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InfoViewModel @Inject constructor(
    private val mapGraphRepository: MapGraphRepository
) : ViewModel() {

    private val _metadata = MutableStateFlow<GraphMetadata?>(null)
    val metadata: StateFlow<GraphMetadata?> = _metadata.asStateFlow()

    init {
        viewModelScope.launch {
            _metadata.value = mapGraphRepository.getMetadata()
        }
    }
}
