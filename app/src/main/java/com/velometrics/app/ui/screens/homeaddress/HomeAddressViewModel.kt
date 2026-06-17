package com.velometrics.app.ui.screens.homeaddress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.data.remote.NominatimResult
import com.velometrics.app.data.remote.NominatimService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeAddressViewModel @Inject constructor(
    private val userSettingsRepository: UserSettingsRepository,
    private val nominatimService: NominatimService
) : ViewModel() {

    private val _lat = MutableStateFlow(0.0)
    val lat: StateFlow<Double> = _lat.asStateFlow()

    private val _lon = MutableStateFlow(0.0)
    val lon: StateFlow<Double> = _lon.asStateFlow()

    private val _displayName = MutableStateFlow("")

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<NominatimResult>>(emptyList())
    val searchResults: StateFlow<List<NominatimResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    init {
        viewModelScope.launch {
            _lat.value = userSettingsRepository.homeLat.first()
            _lon.value = userSettingsRepository.homeLon.first()
            _displayName.value = userSettingsRepository.homeDisplayName.first()
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        _searchResults.value = emptyList()
    }

    fun search() {
        val query = _searchQuery.value.trim()
        if (query.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _isSearching.value = true
            try {
                _searchResults.value = nominatimService.search(query)
            } catch (_: Exception) {
                _searchResults.value = emptyList()
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun selectLocation(lat: Double, lon: Double, displayName: String = "") {
        _lat.value = lat
        _lon.value = lon
        _displayName.value = displayName
        _searchResults.value = emptyList()
    }

    fun updateLatField(text: String) {
        text.replace(',', '.').toDoubleOrNull()?.let { _lat.value = it }
    }

    fun updateLonField(text: String) {
        text.replace(',', '.').toDoubleOrNull()?.let { _lon.value = it }
    }

    fun save(onDone: () -> Unit) {
        viewModelScope.launch {
            userSettingsRepository.saveHomeLocation(_lat.value, _lon.value, _displayName.value)
            _saved.value = true
            onDone()
        }
    }
}
