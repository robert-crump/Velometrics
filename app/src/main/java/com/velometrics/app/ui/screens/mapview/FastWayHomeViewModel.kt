package com.velometrics.app.ui.screens.mapview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.domain.service.FastWayHomeResult
import com.velometrics.app.domain.service.FastWayHomeService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject

@HiltViewModel
class FastWayHomeViewModel @Inject constructor(
    private val fastWayHomeService: FastWayHomeService,
    private val userSettingsRepository: UserSettingsRepository
) : ViewModel() {

    private val _fastWayHomeResult = MutableStateFlow<FastWayHomeResult?>(null)
    val fastWayHomeResult: StateFlow<FastWayHomeResult?> = _fastWayHomeResult.asStateFlow()

    private val _isFindingFastWayHome = MutableStateFlow(false)
    val isFindingFastWayHome: StateFlow<Boolean> = _isFindingFastWayHome.asStateFlow()

    private val _fastWayHomeMessage = MutableStateFlow<String?>(null)
    val fastWayHomeMessage: StateFlow<String?> = _fastWayHomeMessage.asStateFlow()

    val homeLocation: StateFlow<LatLng?> = combine(
        userSettingsRepository.homeLat, userSettingsRepository.homeLon
    ) { lat, lon -> LatLng(lat, lon) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun findFastWayHome(currentLocation: StateFlow<LatLng?>, locationAccuracy: StateFlow<Float?>) {
        viewModelScope.launch {
            _isFindingFastWayHome.value = true
            _fastWayHomeMessage.value = null
            _fastWayHomeResult.value = null
            try {
                if (currentLocation.value == null) {
                    _fastWayHomeMessage.value = "Waiting for GPS signal…"
                    return@launch
                }
                val result = fastWayHomeService.findFastWayHome(currentLocation, locationAccuracy)
                if (result == null) {
                    _fastWayHomeMessage.value = "No known route home from here"
                } else {
                    _fastWayHomeResult.value = result
                }
            } finally {
                _isFindingFastWayHome.value = false
            }
        }
    }

    fun clearFastWayHome() {
        _fastWayHomeResult.value = null
        _fastWayHomeMessage.value = null
    }
}
