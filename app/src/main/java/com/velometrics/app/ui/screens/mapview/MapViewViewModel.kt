package com.velometrics.app.ui.screens.mapview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.FlowSegment
import com.velometrics.app.domain.model.GeoBounds
import com.velometrics.app.domain.model.GeoPoint
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.Poi
import com.velometrics.app.domain.model.PoiWithDistances
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.repository.IntervalRepository
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.domain.repository.RepeatedIntervalRepository
import com.velometrics.app.domain.service.LocationException
import com.velometrics.app.domain.service.LocationSource
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.GeoUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MapViewViewModel @Inject constructor(
    private val mapGraphRepository: MapGraphRepository,
    private val cyclingSessionRepository: CyclingSessionRepository,
    private val intervalRepository: IntervalRepository,
    private val repeatedIntervalRepository: RepeatedIntervalRepository,
    private val locationSource: LocationSource,
) : ViewModel() {

    companion object {
        const val ALL_POIS_CHIP = "All POIs"
    }

    val sessions: StateFlow<List<CyclingSession>> = cyclingSessionRepository.getAllSessions()
        .map { list -> list.sortedByDescending { it.sessionStart } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _showFlowSegments = MutableStateFlow(false)
    val showFlowSegments: StateFlow<Boolean> = _showFlowSegments.asStateFlow()

    private val _viewportBounds = MutableStateFlow<GeoBounds?>(null)

    fun toggleFlowSegments() { _showFlowSegments.update { !it } }

    @OptIn(FlowPreview::class)
    val flowSegments: StateFlow<List<FlowSegment>> = combine(
        _showFlowSegments,
        _viewportBounds.debounce(300)
    ) { show, bounds ->
        if (!show || bounds == null) emptyList()
        else mapGraphRepository.getFlowSegmentsNear(
            bounds.south, bounds.west,
            bounds.north, bounds.east
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    val allRepeatedIntervals: StateFlow<List<RepeatedInterval>> = repeatedIntervalRepository.getAllRepeatedIntervals()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleIntervalOverlay() { _showIntervalOverlay.update { !it } }

    private val _selectedGroup = MutableStateFlow<RepeatedInterval?>(null)
    val selectedGroup: StateFlow<RepeatedInterval?> = _selectedGroup.asStateFlow()

    private val _highlightedIntervalId = MutableStateFlow<Long?>(null)
    val highlightedIntervalId: StateFlow<Long?> = _highlightedIntervalId.asStateFlow()

    fun selectGroup(group: RepeatedInterval?) { _selectedGroup.value = group }
    fun highlightInterval(id: Long?) { _highlightedIntervalId.value = id }
    fun clearSelection() {
        _selectedGroup.value = null
        _highlightedIntervalId.value = null
    }

    // --- POI layer ---

    private val _allPois = MutableStateFlow<List<Poi>>(emptyList())

    // Exposed directly (not via map+stateIn) so a synchronous read right after selectPoiChip()/
    // selectPoiFromMap() sees the new value without needing a collector to pump the flow.
    private val _poiSelection = MutableStateFlow(PoiSelectionState.None)
    val poiSelection: StateFlow<PoiSelectionState> = _poiSelection.asStateFlow()

    val availablePoiCategories: StateFlow<List<String>> = _allPois
        .map { pois -> pois.map { it.category }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val visiblePois: StateFlow<List<Poi>> = combine(
        _allPois, _viewportBounds, _poiSelection
    ) { pois, bounds, selection ->
        val activeChip = selection.activeChip
        if (bounds == null || activeChip == null) return@combine emptyList()
        val filtered = if (activeChip == ALL_POIS_CHIP) pois else pois.filter { it.category == activeChip }
        filtered.filter { bounds.contains(it.lat, it.lon) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val showPoiLayer: StateFlow<Boolean> = _poiSelection
        .map { it.activeChip != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun selectPoiChip(chipLabel: String) {
        _poiSelection.update { it.selectChip(chipLabel) }
    }

    fun clearPoiChip() {
        _poiSelection.update { it.copy(activeChip = null) }
    }

    init {
        viewModelScope.launch {
            _allPois.value = mapGraphRepository.getAllPois().first()
        }
    }

    fun updateViewportBounds(bounds: GeoBounds) {
        _viewportBounds.value = bounds
    }

    fun selectPoiFromMap(poi: Poi) {
        val loc = _currentLocation.value
        val distanceM = if (loc != null) {
            GeoUtils.haversineDistance(loc.lat, loc.lon, poi.lat, poi.lon)
        } else null
        _poiSelection.update { it.selectPoi(PoiWithDistances(poi, distanceM, trackDistanceM = null)) }
    }

    fun dismissPoi() { _poiSelection.update { it.dismissPoi() } }

    // --- Current location with accuracy ---

    private val _currentLocation = MutableStateFlow<GeoPoint?>(null)
    val currentLocation: StateFlow<GeoPoint?> = _currentLocation.asStateFlow()

    private val _locationAccuracy = MutableStateFlow<Float?>(null)
    val locationAccuracy: StateFlow<Float?> = _locationAccuracy.asStateFlow()

    // True while a location subscription is running but no fix has arrived yet — drives the
    // "Locating…" indicator. Never true once permission is denied or no provider is available,
    // preserving the existing silent-ignore behavior for those cases.
    private val _showLocatingIndicator = MutableStateFlow(false)
    val showLocatingIndicator: StateFlow<Boolean> = _showLocatingIndicator.asStateFlow()

    private var locationUpdateJob: Job? = null
    private var lastDotUpdateMs = 0L

    fun startLocationUpdates() {
        locationUpdateJob?.cancel()
        _showLocatingIndicator.value = _currentLocation.value == null
        locationUpdateJob = viewModelScope.launch {
            try {
                locationSource.lastKnownFix(CyclingConstants.GPS_ROUGH_FIX_ACCURACY_M)?.let { fix ->
                    _locationAccuracy.value = fix.accuracyM
                    lastDotUpdateMs = System.currentTimeMillis()
                    _currentLocation.value = GeoPoint(fix.lat, fix.lon)
                    _showLocatingIndicator.value = false
                }
                locationSource.fixes()
                    .onEach { fix ->
                        _locationAccuracy.value = fix.accuracyM
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastDotUpdateMs >= CyclingConstants.LOCATION_DISPLAY_THROTTLE_MS) {
                            lastDotUpdateMs = nowMs
                            _currentLocation.value = GeoPoint(fix.lat, fix.lon)
                        }
                        _showLocatingIndicator.value = false
                    }
                    .collect {}
            } catch (_: LocationException.NoProvider) {
                // no location provider available; keep the map usable without a fix
                _showLocatingIndicator.value = false
            } catch (_: LocationException.PermissionDenied) {
                // permission not granted; silently ignore
                _showLocatingIndicator.value = false
            }
        }
    }
}
