package com.velometrics.app.ui.screens.mapview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.domain.service.GeneratorConfig
import com.velometrics.app.domain.service.RankedCandidate
import com.velometrics.app.domain.service.RouteGenerator
import com.velometrics.app.domain.service.RoutePlanResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlanARideViewModel @Inject constructor(
    private val repository: MapGraphRepository,
    private val userSettingsRepository: UserSettingsRepository,
) : ViewModel() {

    private val _candidates = MutableStateFlow<List<RankedCandidate>>(emptyList())
    val candidates: StateFlow<List<RankedCandidate>> = _candidates.asStateFlow()

    private val _selectedCandidateIndex = MutableStateFlow<Int?>(null)
    val selectedCandidateIndex: StateFlow<Int?> = _selectedCandidateIndex.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun planARide(targetDistanceKm: Double) {
        viewModelScope.launch {
            _isGenerating.value = true
            _message.value = null
            _candidates.value = emptyList()
            _selectedCandidateIndex.value = null
            try {
                val homeLat = userSettingsRepository.homeLat.first()
                val homeLon = userSettingsRepository.homeLon.first()
                val targetDistanceM = targetDistanceKm * 1000.0

                val result = RouteGenerator.generate(
                    homeLat, homeLon, targetDistanceM, repository,
                    config = GeneratorConfig(),
                )

                when (result) {
                    is RoutePlanResult.Success -> {
                        _candidates.value = result.candidates
                        if (result.candidates.isNotEmpty()) {
                            _selectedCandidateIndex.value = 0
                        }
                    }
                    is RoutePlanResult.Failure -> {
                        _message.value = result.reason
                    }
                }
            } catch (e: Exception) {
                _message.value = "Route generation failed: ${e.message}"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun selectCandidate(index: Int) {
        if (index in _candidates.value.indices) {
            _selectedCandidateIndex.value = index
        }
    }

    fun clearPlan() {
        _candidates.value = emptyList()
        _selectedCandidateIndex.value = null
        _message.value = null
    }
}
