package com.cyclegraph.app.ui.screens.mapview

import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cyclegraph.app.data.heatmap.HeatmapCell
import com.cyclegraph.app.domain.model.CyclingSession
import com.cyclegraph.app.domain.service.HeatmapService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import com.cyclegraph.app.domain.model.IntervalPrototypeRoute
import com.cyclegraph.app.domain.model.IntervalSession
import com.cyclegraph.app.domain.model.MapEdge
import com.cyclegraph.app.domain.repository.CyclingSessionRepository
import com.cyclegraph.app.domain.repository.IntervalRepository
import com.cyclegraph.app.domain.repository.MapGraphRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import com.cyclegraph.app.util.CyclingConstants
import com.cyclegraph.app.util.IntervalGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class MapViewViewModel @Inject constructor(
    private val mapGraphRepository: MapGraphRepository,
    private val cyclingSessionRepository: CyclingSessionRepository,
    private val intervalRepository: IntervalRepository,
    private val heatmapService: HeatmapService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // Eagerly started so the graph stays loaded even when the Map tab is not active
    val allEdges: StateFlow<List<MapEdge>> = mapGraphRepository.getAllEdges()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val sessions: StateFlow<List<CyclingSession>> = cyclingSessionRepository.getAllSessions()
        .map { list -> list.sortedByDescending { it.sessionStart } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _showAllRidesLayer = MutableStateFlow(false)
    val showAllRidesLayer: StateFlow<Boolean> = _showAllRidesLayer.asStateFlow()

    private val _showSpeedOverlay = MutableStateFlow(false)
    val showSpeedOverlay: StateFlow<Boolean> = _showSpeedOverlay.asStateFlow()

    // Active speed categories; when overlay first enabled, show the 40+ category by default
    private val _selectedSpeedCategories = MutableStateFlow<Set<String>>(emptySet())
    val selectedSpeedCategories: StateFlow<Set<String>> = _selectedSpeedCategories.asStateFlow()

    private val _showStopSpots = MutableStateFlow(false)
    val showStopSpots: StateFlow<Boolean> = _showStopSpots.asStateFlow()

    fun toggleAllRidesLayer() { _showAllRidesLayer.update { !it } }

    fun toggleSpeedOverlay() {
        val newShow = !_showSpeedOverlay.value
        _showSpeedOverlay.value = newShow
        // When enabling, start with the fastest category; when disabling, clear selection
        _selectedSpeedCategories.value = if (newShow) setOf("40-50 km/h") else emptySet()
    }

    fun toggleSpeedCategory(categoryKey: String) {
        _selectedSpeedCategories.update { current ->
            if (categoryKey in current) current - categoryKey else current + categoryKey
        }
    }

    fun toggleStopSpots() { _showStopSpots.update { !it } }

    // --- Heatmap overlay ---

    private val _showHeatmap = MutableStateFlow(false)
    val showHeatmap: StateFlow<Boolean> = _showHeatmap.asStateFlow()

    private val _heatmapLoading = MutableStateFlow(false)
    val heatmapLoading: StateFlow<Boolean> = _heatmapLoading.asStateFlow()

    private val _heatmapCells = MutableStateFlow<List<HeatmapCell>>(emptyList())
    val heatmapCells: StateFlow<List<HeatmapCell>> = _heatmapCells.asStateFlow()

    fun toggleHeatmap() {
        val enabling = !_showHeatmap.value
        _showHeatmap.value = enabling
        if (enabling && _heatmapCells.value.isEmpty()) {
            viewModelScope.launch {
                _heatmapLoading.value = true
                _heatmapCells.value = heatmapService.getOrUpdateHeatmap()
                _heatmapLoading.value = false
            }
        }
    }

    private val _visibleSessionIds = MutableStateFlow<Set<Long>>(emptySet())
    val visibleSessionIds: StateFlow<Set<Long>> = _visibleSessionIds.asStateFlow()

    fun toggleSession(id: Long) {
        _visibleSessionIds.update { current ->
            if (id in current) current - id else current + id
        }
    }

    fun showAll() {
        _visibleSessionIds.update {
            sessions.value.filter { it.gpsTrack != null }.map { it.id }.toSet()
        }
    }

    fun hideAll() {
        _visibleSessionIds.update { emptySet() }
    }

    // --- Interval overlay ---

    private val _showIntervalOverlay = MutableStateFlow(false)
    val showIntervalOverlay: StateFlow<Boolean> = _showIntervalOverlay.asStateFlow()

    val allIntervals: StateFlow<List<IntervalSession>> = intervalRepository.getAllIntervals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPrototypeRoutes: StateFlow<List<IntervalPrototypeRoute>> = intervalRepository.getAllPrototypeRoutes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleIntervalOverlay() { _showIntervalOverlay.update { !it } }

    // Tap interaction state
    private val _selectedInterval = MutableStateFlow<IntervalSession?>(null)
    val selectedInterval: StateFlow<IntervalSession?> = _selectedInterval.asStateFlow()

    private val _selectedGroup = MutableStateFlow<IntervalGroup?>(null)
    val selectedGroup: StateFlow<IntervalGroup?> = _selectedGroup.asStateFlow()

    private val _highlightedIntervalId = MutableStateFlow<Long?>(null)
    val highlightedIntervalId: StateFlow<Long?> = _highlightedIntervalId.asStateFlow()

    fun selectInterval(interval: IntervalSession?) { _selectedInterval.value = interval }
    fun selectGroup(group: IntervalGroup?) { _selectedGroup.value = group }
    fun highlightInterval(id: Long?) { _highlightedIntervalId.value = id }
    fun clearSelection() {
        _selectedInterval.value = null
        _selectedGroup.value = null
        _highlightedIntervalId.value = null
    }

    // --- Current location with accuracy ---

    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

    private val _locationAccuracy = MutableStateFlow<Float?>(null)
    val locationAccuracy: StateFlow<Float?> = _locationAccuracy.asStateFlow()

    private val _poorGpsSnackbar = MutableStateFlow(false)
    val poorGpsSnackbar: StateFlow<Boolean> = _poorGpsSnackbar.asStateFlow()

    fun dismissPoorGpsSnackbar() { _poorGpsSnackbar.value = false }

    private val _isAcquiringGps = MutableStateFlow(false)
    val isAcquiringGps: StateFlow<Boolean> = _isAcquiringGps.asStateFlow()

    private var locationListener: LocationListener? = null
    private var locationUpdateJob: Job? = null

    private var lastDotUpdateMs = 0L

    fun startLocationUpdates() {
        locationUpdateJob?.cancel()
        _poorGpsSnackbar.value = false
        _isAcquiringGps.value = true
        locationUpdateJob = viewModelScope.launch(Dispatchers.Main) {
            var locationManager: LocationManager? = null
            try {
                locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val provider = LocationManager.GPS_PROVIDER
                if (!locationManager.isProviderEnabled(provider)) return@launch

                // Remove any existing listener before registering a new one
                locationListener?.let { locationManager.removeUpdates(it) }

                val listener = LocationListener { location ->
                    // Always update accuracy so the halo and fine-fix zoom stay reactive
                    _locationAccuracy.value = location.accuracy

                    // Only move the blue dot every 5 seconds to avoid jitter
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastDotUpdateMs >= CyclingConstants.LOCATION_DISPLAY_THROTTLE_MS) {
                        lastDotUpdateMs = nowMs
                        _currentLocation.value = LatLng(location.latitude, location.longitude)
                    }

                    // Good enough fix — stop acquiring
                    if (location.accuracy <= CyclingConstants.GPS_FINE_FIX_ACCURACY_M) {
                        locationListener?.let {
                            try { locationManager?.removeUpdates(it) } catch (_: Exception) {}
                        }
                        locationUpdateJob?.cancel()
                    }
                }
                locationListener = listener

                @Suppress("MissingPermission")
                locationManager.requestLocationUpdates(
                    provider,
                    CyclingConstants.LOCATION_UPDATE_MIN_TIME_MS,
                    0f,
                    listener,
                    context.mainLooper
                )

                // Wait up to 15 seconds for a good fix
                delay(CyclingConstants.GPS_ACQUISITION_TIMEOUT_MS)
                // Only reached if no <10 m fix was obtained
                _poorGpsSnackbar.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: SecurityException) {
                // Location permission not granted
            } catch (_: Exception) {
                // Provider unavailable
            } finally {
                _isAcquiringGps.value = false
                locationManager?.let { lm ->
                    locationListener?.let { l ->
                        try { lm.removeUpdates(l) } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationListener?.let { locationManager.removeUpdates(it) }
        } catch (_: Exception) {}
    }
}
