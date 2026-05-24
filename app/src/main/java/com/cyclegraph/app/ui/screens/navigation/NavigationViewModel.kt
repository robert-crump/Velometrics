package com.cyclegraph.app.ui.screens.navigation

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cyclegraph.app.data.gpx.GpxParser
import com.cyclegraph.app.domain.model.GpxTrack
import com.cyclegraph.app.domain.model.PoiWithDistances
import com.cyclegraph.app.domain.repository.MapGraphRepository
import com.cyclegraph.app.domain.service.LocationException
import com.cyclegraph.app.domain.service.LocationSource
import com.cyclegraph.app.domain.service.NavigationRouteHolder
import com.cyclegraph.app.domain.service.TrackGeometryUtils
import com.cyclegraph.app.util.CyclingConstants
import com.cyclegraph.app.util.GeoUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject

enum class PoiMode { NEARBY, ALONG_TRACK }
enum class PoiTab  { LIST, MAP }

@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val mapGraphRepository: MapGraphRepository,
    private val navigationRouteHolder: NavigationRouteHolder,
    private val locationSource: LocationSource
) : ViewModel() {

    private val _gpxTrack = MutableStateFlow<GpxTrack?>(null)
    val gpxTrack: StateFlow<GpxTrack?> = _gpxTrack.asStateFlow()

    private val _userPosition = MutableStateFlow<LatLng?>(null)
    val userPosition: StateFlow<LatLng?> = _userPosition.asStateFlow()

    private val _allPois = MutableStateFlow<List<PoiWithDistances>>(emptyList())

    private val _lookAheadKm = MutableStateFlow(2.0)
    val lookAheadKm: StateFlow<Double> = _lookAheadKm.asStateFlow()

    private val _selectedMode = MutableStateFlow<PoiMode?>(null)
    val selectedMode: StateFlow<PoiMode?> = _selectedMode.asStateFlow()

    private val _selectedTab = MutableStateFlow(PoiTab.LIST)
    val selectedTab: StateFlow<PoiTab> = _selectedTab.asStateFlow()

    // Nearby radius in metres; discrete values: 200, 500, 1000, 2000, 5000
    private val _nearbyRadiusM = MutableStateFlow(500.0)
    val nearbyRadiusM: StateFlow<Double> = _nearbyRadiusM.asStateFlow()

    // Search corridor for ALONG_TRACK mode in metres; discrete values: 50, 200, 500, 1000, 5000
    private val _searchCorridorM = MutableStateFlow(200.0)
    val searchCorridorM: StateFlow<Double> = _searchCorridorM.asStateFlow()

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    val pois: StateFlow<List<PoiWithDistances>> = combine(_allPois, _selectedCategory) { all, filter ->
        if (filter == null) all else all.filter { it.poi.category == filter }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** All distinct categories present in the current POI result set, sorted alphabetically. */
    val availableCategories: StateFlow<List<String>> = _allPois
        .map { allPois -> allPois.map { it.poi.category }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoadingGpx = MutableStateFlow(false)
    val isLoadingGpx: StateFlow<Boolean> = _isLoadingGpx.asStateFlow()

    private val _isLoadingPois = MutableStateFlow(false)
    val isLoadingPois: StateFlow<Boolean> = _isLoadingPois.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _poiSelection = MutableStateFlow(PoiSelectionState.None)
    val poiSelection: StateFlow<PoiSelectionState> = _poiSelection.asStateFlow()

    private var locationTrackingJob: Job? = null

    init {
        navigationRouteHolder.pendingRoute?.let { route ->
            val name = navigationRouteHolder.pendingRouteName ?: "Planned Route"
            loadGpxFromPoints(route, name)
            navigationRouteHolder.pendingRoute = null
            navigationRouteHolder.pendingRouteName = null
        }
    }

    // --- Mode / Tab management ---

    fun setMode(mode: PoiMode) {
        _selectedMode.value = mode
        _allPois.value = emptyList()
        _selectedTab.value = PoiTab.LIST
        _poiSelection.value = PoiSelectionState.None
        _errorMessage.value = null
    }

    fun resetMode() {
        _selectedMode.value = null
        _allPois.value = emptyList()
        _poiSelection.value = PoiSelectionState.None
        _errorMessage.value = null
    }

    fun setSelectedTab(tab: PoiTab) { _selectedTab.value = tab }
    fun setNearbyRadius(m: Double) { _nearbyRadiusM.value = m }
    fun setSearchCorridorM(m: Double) { _searchCorridorM.value = m }

    fun setLookAheadKm(km: Double) {
        _lookAheadKm.value = km
        if (_gpxTrack.value != null) fetchPoisAlongTrack()
    }

    // --- POI selection / popup ---

    fun pickPoiFromList(poiWD: PoiWithDistances) {
        _poiSelection.update { it.pickFromList(poiWD) }
        _selectedTab.value = PoiTab.MAP
    }

    fun pickPoiFromMap(poiWD: PoiWithDistances) {
        _poiSelection.update { it.pickFromMap(poiWD) }
    }

    fun dismissPoi() {
        _poiSelection.update { it.dismiss() }
    }

    fun consumePoiCameraMove() {
        _poiSelection.update { it.consumeCameraMove() }
    }

    fun setCategoryFilter(category: String?) { _selectedCategory.value = category }

    // --- GPX loading ---

    fun loadGpxFromUri(uri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            _isLoadingGpx.value = true
            _errorMessage.value = null
            _allPois.value = emptyList()
            try {
                val track = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        GpxParser.parse(stream).getOrThrow()
                    } ?: error("Could not open file")
                }
                _gpxTrack.value = track
            } catch (e: Exception) {
                _errorMessage.value = "Failed to load GPX: ${e.message}"
            } finally {
                _isLoadingGpx.value = false
            }
        }
    }

    fun loadGpxFromPoints(points: List<LatLng>, name: String) {
        viewModelScope.launch {
            _isLoadingGpx.value = true
            _errorMessage.value = null
            _allPois.value = emptyList()
            _gpxTrack.value = GpxTrack(name = name, points = points)
            _isLoadingGpx.value = false
        }
    }

    // --- POI fetching ---

    /** Fetches POIs within a radius of the user's current GPS position (NEARBY mode). */
    fun fetchPoisNearbyByRadius() {
        viewModelScope.launch {
            _isLoadingPois.value = true
            _errorMessage.value = null
            try {
                val position = fetchRoughGpsPosition() ?: run {
                    _errorMessage.value = "No GPS position available"
                    return@launch
                }
                _userPosition.value = position

                val radiusM = _nearbyRadiusM.value
                val allRepoPois = mapGraphRepository.getAllPois().first()

                val result = allRepoPois.mapNotNull { poi ->
                    val airDistM = GeoUtils.haversineDistance(
                        position.latitude, position.longitude, poi.lat, poi.lon
                    )
                    if (airDistM <= radiusM) PoiWithDistances(poi, airDistM, trackDistanceM = null)
                    else null
                }.sortedBy { it.airDistanceM ?: Double.MAX_VALUE }

                _allPois.value = result
                startContinuousLocationTracking()
            } catch (e: Exception) {
                _errorMessage.value = "POI lookup failed: ${e.message}"
            } finally {
                _isLoadingPois.value = false
            }
        }
    }

    /** Fetches POIs along the loaded GPX track (ALONG_TRACK mode). */
    fun fetchPoisAlongTrack() {
        val track = _gpxTrack.value ?: run {
            _errorMessage.value = "Load a GPX track first"
            return
        }

        viewModelScope.launch {
            _isLoadingPois.value = true
            _errorMessage.value = null

            try {
                val position = fetchRoughGpsPosition() ?: track.points.firstOrNull()
                if (position == null) {
                    _errorMessage.value = "No position available"
                    return@launch
                }
                _userPosition.value = position

                val allRepoPois = mapGraphRepository.getAllPois().first()
                val userProjection = TrackGeometryUtils.projectPointOntoTrack(position, track.points)
                val subTrack = TrackGeometryUtils.extractSubTrack(
                    track.points, userProjection,
                    lookAheadM = _lookAheadKm.value * 1000.0,
                    lookBackM  = _lookAheadKm.value * 1000.0
                )

                val corridorM = _searchCorridorM.value

                val result = allRepoPois.mapNotNull { poi ->
                    if (subTrack.size < 2) return@mapNotNull null
                    val (_, offRoute) = TrackGeometryUtils.projectPoiOntoTrack(
                        poi.lat, poi.lon, subTrack
                    )
                    if (offRoute > corridorM) return@mapNotNull null

                    val poiProjection = TrackGeometryUtils.projectPointOntoTrack(
                        LatLng(poi.lat, poi.lon), track.points
                    )
                    val trackDistM = TrackGeometryUtils.computeDistanceAlongTrack(
                        track.points, userProjection, poiProjection
                    )
                    val airDistM = GeoUtils.haversineDistance(
                        position.latitude, position.longitude, poi.lat, poi.lon
                    )
                    PoiWithDistances(poi, airDistM, trackDistM)
                }.sortedBy { it.trackDistanceM ?: Double.MAX_VALUE }

                _allPois.value = result
                startContinuousLocationTracking()
            } catch (e: Exception) {
                _errorMessage.value = "POI lookup failed: ${e.message}"
            } finally {
                _isLoadingPois.value = false
            }
        }
    }

    fun refreshDistances() {
        when (_selectedMode.value) {
            PoiMode.NEARBY      -> fetchPoisNearbyByRadius()
            PoiMode.ALONG_TRACK -> fetchPoisAlongTrack()
            null                -> {}
        }
    }

    fun setUserPositionFromPermission(granted: Boolean) {
        if (!granted && _selectedMode.value == PoiMode.ALONG_TRACK) {
            val fallback = _gpxTrack.value?.points?.firstOrNull()
            if (fallback != null) {
                _userPosition.value = fallback
                _errorMessage.value = "Location denied — using track start"
            }
        }
    }

    private suspend fun fetchRoughGpsPosition(): LatLng? = try {
        val cached = locationSource.lastKnownFix(CyclingConstants.GPS_ROUGH_FIX_ACCURACY_M)
        val fix = cached
            ?: locationSource.fixes().first { it.accuracyM <= CyclingConstants.GPS_ROUGH_FIX_ACCURACY_M }
        LatLng(fix.lat, fix.lon)
    } catch (_: LocationException) {
        null
    }

    private fun startContinuousLocationTracking() {
        locationTrackingJob?.cancel()
        locationTrackingJob = viewModelScope.launch {
            try {
                locationSource.fixes().collect {
                    _userPosition.value = LatLng(it.lat, it.lon)
                }
            } catch (_: LocationException) { /* swallow */ }
        }
    }
}
