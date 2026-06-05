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
import com.cyclegraph.app.domain.service.TrackGeometryUtils
import com.cyclegraph.app.util.CyclingConstants
import com.cyclegraph.app.util.GeoUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import javax.inject.Inject

enum class LookAheadOption(val km: Int) { KM5(5), KM10(10), KM25(25) }

@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val mapGraphRepository: MapGraphRepository,
    private val locationSource: LocationSource
) : ViewModel() {

    private val _gpxTrack = MutableStateFlow<GpxTrack?>(null)
    val gpxTrack: StateFlow<GpxTrack?> = _gpxTrack.asStateFlow()

    private val _userPosition = MutableStateFlow<LatLng?>(null)
    val userPosition: StateFlow<LatLng?> = _userPosition.asStateFlow()

    private val _lookAheadOption = MutableStateFlow<LookAheadOption?>(null)
    val lookAheadOption: StateFlow<LookAheadOption?> = _lookAheadOption.asStateFlow()

    private var allTrackPois: List<PoiWithDistances> = emptyList()

    private val _pois = MutableStateFlow<List<PoiWithDistances>>(emptyList())
    val pois: StateFlow<List<PoiWithDistances>> = _pois.asStateFlow()

    private val _isLoadingGpx = MutableStateFlow(false)
    val isLoadingGpx: StateFlow<Boolean> = _isLoadingGpx.asStateFlow()

    private val _isLoadingPois = MutableStateFlow(false)
    val isLoadingPois: StateFlow<Boolean> = _isLoadingPois.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _poiSelection = MutableStateFlow(PoiSelectionState.None)
    val poiSelection: StateFlow<PoiSelectionState> = _poiSelection.asStateFlow()

    private val _pendingCameraBounds = MutableStateFlow<LatLngBounds?>(null)
    val pendingCameraBounds: StateFlow<LatLngBounds?> = _pendingCameraBounds.asStateFlow()

    private val _offTrackDialogKm = MutableStateFlow<Double?>(null)
    val offTrackDialogKm: StateFlow<Double?> = _offTrackDialogKm.asStateFlow()

    private var hasCheckedOffTrack = false

    fun pickPoiFromList(poiWD: PoiWithDistances) {
        _poiSelection.value = _poiSelection.value.pickFromList(poiWD)
    }

    fun pickPoiFromMap(poiWD: PoiWithDistances) {
        _poiSelection.value = _poiSelection.value.pickFromMap(poiWD)
    }

    fun dismissPoi() {
        _poiSelection.value = _poiSelection.value.dismiss()
    }

    fun consumePoiCameraMove() {
        _poiSelection.value = _poiSelection.value.consumeCameraMove()
    }

    fun consumeCameraFit() {
        _pendingCameraBounds.value = null
    }

    fun dismissOffTrackDialog() {
        _offTrackDialogKm.value = null
    }

    fun setLookAheadOption(option: LookAheadOption) {
        if (_lookAheadOption.value == option) {
            _lookAheadOption.value = null
            _pois.value = allTrackPois
            _pendingCameraBounds.value = computeFullTrackBounds()
        } else {
            _lookAheadOption.value = option
            _pois.value = filterPoisForLookAhead(option)
            _pendingCameraBounds.value = computeLookAheadBounds(option)
        }
    }

    fun loadGpxFromUri(uri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            _isLoadingGpx.value = true
            _errorMessage.value = null
            allTrackPois = emptyList()
            _pois.value = emptyList()
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

    internal fun loadGpxFromTrack(track: GpxTrack) {
        _gpxTrack.value = track
    }

    fun clearGpx() {
        _gpxTrack.value = null
        allTrackPois = emptyList()
        _pois.value = emptyList()
        _userPosition.value = null
        _errorMessage.value = null
        _poiSelection.value = PoiSelectionState.None
        _lookAheadOption.value = null
        _pendingCameraBounds.value = null
        _offTrackDialogKm.value = null
        hasCheckedOffTrack = false
    }

    fun refreshUserPosition() {
        val track = _gpxTrack.value ?: return
        viewModelScope.launch {
            _isLoadingPois.value = true
            _errorMessage.value = null
            try {
                val position = fetchRoughGpsPosition() ?: run {
                    _errorMessage.value = "Location unavailable — using track start"
                    track.points.firstOrNull()
                } ?: return@launch
                _userPosition.value = position
                if (!hasCheckedOffTrack) {
                    hasCheckedOffTrack = true
                    checkOffTrack(position, track)
                }
                doFetchAllTrackPois(track, position)
                val option = _lookAheadOption.value
                if (option != null) _pois.value = filterPoisForLookAhead(option)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to get location: ${e.message}"
            } finally {
                _isLoadingPois.value = false
            }
        }
    }

    private fun checkOffTrack(position: LatLng, track: GpxTrack) {
        val projection = TrackGeometryUtils.projectPointOntoTrack(position, track.points)
        if (projection.distanceFromTrackM > OFF_TRACK_THRESHOLD_M) {
            _offTrackDialogKm.value = projection.distanceFromTrackM / 1000.0
        }
    }

    private fun filterPoisForLookAhead(option: LookAheadOption): List<PoiWithDistances> {
        val limitM = option.km * 1000.0
        return allTrackPois.filter { poiWD ->
            val dist = poiWD.trackDistanceM ?: return@filter false
            dist in 0.0..limitM
        }
    }

    private fun computeLookAheadBounds(option: LookAheadOption): LatLngBounds? {
        val track = _gpxTrack.value ?: return null
        val position = _userPosition.value ?: return null
        val userProjection = TrackGeometryUtils.projectPointOntoTrack(position, track.points)
        val subTrack = TrackGeometryUtils.extractSubTrack(
            track.points, userProjection,
            lookAheadM = option.km * 1000.0,
            lookBackM = 0.0
        )
        if (subTrack.size < 2) return null
        return LatLngBounds.Builder().apply {
            include(position)
            subTrack.forEach { include(it) }
        }.build()
    }

    private fun computeFullTrackBounds(): LatLngBounds? {
        val track = _gpxTrack.value ?: return null
        if (track.points.size < 2) return null
        return LatLngBounds.Builder().apply {
            track.points.forEach { include(it) }
        }.build()
    }

    private suspend fun doFetchAllTrackPois(track: GpxTrack, position: LatLng) {
        val allRepoPois = mapGraphRepository.getAllPois().first()
        val result = withContext(Dispatchers.Default) {
            val userProjection = TrackGeometryUtils.projectPointOntoTrack(position, track.points)
            allRepoPois.mapNotNull { poi ->
                val (_, offRoute) = TrackGeometryUtils.projectPoiOntoTrack(poi.lat, poi.lon, track.points)
                if (offRoute > CORRIDOR_M) return@mapNotNull null
                val poiProjection = TrackGeometryUtils.projectPointOntoTrack(LatLng(poi.lat, poi.lon), track.points)
                val isAhead = poiProjection.segmentIndex > userProjection.segmentIndex ||
                    (poiProjection.segmentIndex == userProjection.segmentIndex &&
                        poiProjection.fraction >= userProjection.fraction)
                val trackDistM = if (isAhead) {
                    TrackGeometryUtils.computeDistanceAlongTrack(track.points, userProjection, poiProjection)
                } else {
                    -TrackGeometryUtils.computeDistanceAlongTrack(track.points, poiProjection, userProjection)
                }
                val airDistM = GeoUtils.haversineDistance(position.latitude, position.longitude, poi.lat, poi.lon)
                PoiWithDistances(poi, airDistM, trackDistM)
            }.sortedBy { it.trackDistanceM ?: Double.MAX_VALUE }
        }
        allTrackPois = result
        _pois.value = result
    }

    private suspend fun fetchRoughGpsPosition(): LatLng? = try {
        val cached = locationSource.lastKnownFix(CyclingConstants.GPS_ROUGH_FIX_ACCURACY_M)
        val fix = cached
            ?: locationSource.fixes().first { it.accuracyM <= CyclingConstants.GPS_ROUGH_FIX_ACCURACY_M }
        LatLng(fix.lat, fix.lon)
    } catch (_: LocationException) {
        null
    }

    companion object {
        private const val CORRIDOR_M = 200.0
        private const val OFF_TRACK_THRESHOLD_M = 1000.0
    }
}
