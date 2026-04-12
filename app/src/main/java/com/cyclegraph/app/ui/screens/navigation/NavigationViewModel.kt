package com.cyclegraph.app.ui.screens.navigation

import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cyclegraph.app.data.gpx.GpxParser
import com.cyclegraph.app.domain.model.GpxTrack
import com.cyclegraph.app.domain.model.Poi
import com.cyclegraph.app.domain.model.PoiWithDistances
import com.cyclegraph.app.domain.repository.MapGraphRepository
import com.cyclegraph.app.domain.service.NavigationRouteHolder
import com.cyclegraph.app.domain.service.TrackGeometryUtils
import com.cyclegraph.app.util.CyclingConstants
import com.cyclegraph.app.util.GeoUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject
import kotlin.coroutines.resume

enum class PoiMode { NEARBY, ALONG_TRACK }
enum class PoiTab  { LIST, MAP }

@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val mapGraphRepository: MapGraphRepository,
    private val navigationRouteHolder: NavigationRouteHolder,
    @ApplicationContext private val context: Context
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

    private val _selectedPoi = MutableStateFlow<Poi?>(null)
    val selectedPoi: StateFlow<Poi?> = _selectedPoi.asStateFlow()

    private val _highlightedPoiId = MutableStateFlow<String?>(null)
    val highlightedPoiId: StateFlow<String?> = _highlightedPoiId.asStateFlow()

    private val _popupPoiWithDistances = MutableStateFlow<PoiWithDistances?>(null)
    val popupPoiWithDistances: StateFlow<PoiWithDistances?> = _popupPoiWithDistances.asStateFlow()

    /** One-shot: set when a POI is selected from the list to trigger camera zoom on the map. */
    private val _navigateToPoi = MutableStateFlow<Poi?>(null)
    val navigateToPoi: StateFlow<Poi?> = _navigateToPoi.asStateFlow()

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
        _popupPoiWithDistances.value = null
        _selectedPoi.value = null
        _navigateToPoi.value = null
        _errorMessage.value = null
    }

    fun resetMode() {
        _selectedMode.value = null
        _allPois.value = emptyList()
        _popupPoiWithDistances.value = null
        _selectedPoi.value = null
        _navigateToPoi.value = null
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

    fun setHighlightedPoi(poiId: String?) { _highlightedPoiId.value = poiId }

    fun selectPoiFromMap(poiWD: PoiWithDistances) {
        _popupPoiWithDistances.value = poiWD
        _selectedPoi.value = poiWD.poi
    }

    fun clearPoiPopup() {
        _popupPoiWithDistances.value = null
        _selectedPoi.value = null
    }

    fun selectPoi(poiWD: PoiWithDistances) {
        _selectedPoi.value = poiWD.poi
        _navigateToPoi.value = poiWD.poi   // triggers camera zoom in screen
        _popupPoiWithDistances.value = poiWD
        _selectedTab.value = PoiTab.MAP
    }

    fun consumeNavigateToPoi() { _navigateToPoi.value = null }

    fun setCategoryFilter(category: String?) { _selectedCategory.value = category }

    // --- GPX loading ---

    fun loadGpxFromUri(uri: Uri) {
        viewModelScope.launch {
            _isLoadingGpx.value = true
            _errorMessage.value = null
            _allPois.value = emptyList()
            try {
                val track = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
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

    private suspend fun fetchRoughGpsPosition(): LatLng? = withContext(Dispatchers.Main) {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = LocationManager.GPS_PROVIDER

            if (!locationManager.isProviderEnabled(provider)) return@withContext null

            val lastKnown = try {
                @Suppress("MissingPermission")
                locationManager.getLastKnownLocation(provider)
            } catch (_: SecurityException) { null }

            if (lastKnown != null && lastKnown.accuracy <= CyclingConstants.GPS_ROUGH_FIX_ACCURACY_M) {
                return@withContext LatLng(lastKnown.latitude, lastKnown.longitude)
            }

            val networkProvider = LocationManager.NETWORK_PROVIDER
            if (locationManager.isProviderEnabled(networkProvider)) {
                val networkLast = try {
                    @Suppress("MissingPermission")
                    locationManager.getLastKnownLocation(networkProvider)
                } catch (_: SecurityException) { null }
                if (networkLast != null && networkLast.accuracy <= CyclingConstants.GPS_ROUGH_FIX_ACCURACY_M) {
                    return@withContext LatLng(networkLast.latitude, networkLast.longitude)
                }
            }

            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: android.location.Location) {
                        if (location.accuracy <= CyclingConstants.GPS_ROUGH_FIX_ACCURACY_M) {
                            try {
                                @Suppress("MissingPermission")
                                locationManager.removeUpdates(this)
                            } catch (_: Exception) {}
                            if (cont.isActive) cont.resume(LatLng(location.latitude, location.longitude))
                        }
                    }
                }
                cont.invokeOnCancellation {
                    try {
                        @Suppress("MissingPermission")
                        locationManager.removeUpdates(listener)
                    } catch (_: Exception) {}
                }
                try {
                    @Suppress("MissingPermission")
                    locationManager.requestLocationUpdates(
                        provider, CyclingConstants.LOCATION_UPDATE_MIN_TIME_MS, 0f, listener, context.mainLooper
                    )
                } catch (e: SecurityException) {
                    cont.resume(null)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun startContinuousLocationTracking() {
        locationTrackingJob?.cancel()
        locationTrackingJob = viewModelScope.launch(Dispatchers.Main) {
            try {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val provider = LocationManager.GPS_PROVIDER
                if (!locationManager.isProviderEnabled(provider)) return@launch

                suspendCancellableCoroutine<Unit> { cont ->
                    val listener = LocationListener { location ->
                        _userPosition.value = LatLng(location.latitude, location.longitude)
                    }
                    cont.invokeOnCancellation {
                        try {
                            @Suppress("MissingPermission")
                            locationManager.removeUpdates(listener)
                        } catch (_: Exception) {}
                    }
                    try {
                        @Suppress("MissingPermission")
                        locationManager.requestLocationUpdates(
                            provider, CyclingConstants.LOCATION_UPDATE_MIN_TIME_MS, 0f, listener, context.mainLooper
                        )
                    } catch (e: SecurityException) {
                        cont.resume(Unit)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun onCleared() {
        super.onCleared()
        locationTrackingJob?.cancel()
    }
}
