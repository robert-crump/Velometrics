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

    private val _lookAheadOption = MutableStateFlow(LookAheadOption.KM5)
    val lookAheadOption: StateFlow<LookAheadOption> = _lookAheadOption.asStateFlow()

    private val _allPois = MutableStateFlow<List<PoiWithDistances>>(emptyList())
    val pois: StateFlow<List<PoiWithDistances>> = _allPois.asStateFlow()

    private val _isLoadingGpx = MutableStateFlow(false)
    val isLoadingGpx: StateFlow<Boolean> = _isLoadingGpx.asStateFlow()

    private val _isLoadingPois = MutableStateFlow(false)
    val isLoadingPois: StateFlow<Boolean> = _isLoadingPois.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _poiSelection = MutableStateFlow(PoiSelectionState.None)
    val poiSelection: StateFlow<PoiSelectionState> = _poiSelection.asStateFlow()

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

    fun setLookAheadOption(option: LookAheadOption) {
        _lookAheadOption.value = option
        if (_gpxTrack.value != null) fetchPoisAlongTrack()
    }

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

    internal fun loadGpxFromTrack(track: GpxTrack) {
        _gpxTrack.value = track
    }

    fun clearGpx() {
        _gpxTrack.value = null
        _allPois.value = emptyList()
        _userPosition.value = null
        _errorMessage.value = null
        _poiSelection.value = PoiSelectionState.None
    }

    /** Gets a single GPS fix, updates user position, then fetches POIs along the track. */
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
                doFetchPois(track, position)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to get location: ${e.message}"
            } finally {
                _isLoadingPois.value = false
            }
        }
    }

    /** Re-fetches POIs using the current user position without a new GPS call. */
    fun fetchPoisAlongTrack() {
        val track = _gpxTrack.value ?: return
        val position = _userPosition.value ?: track.points.firstOrNull() ?: return
        viewModelScope.launch {
            _isLoadingPois.value = true
            _errorMessage.value = null
            try {
                doFetchPois(track, position)
            } catch (e: Exception) {
                _errorMessage.value = "POI lookup failed: ${e.message}"
            } finally {
                _isLoadingPois.value = false
            }
        }
    }

    private suspend fun doFetchPois(track: GpxTrack, position: LatLng) {
        val allRepoPois = mapGraphRepository.getAllPois().first()
        val userProjection = TrackGeometryUtils.projectPointOntoTrack(position, track.points)
        val subTrack = TrackGeometryUtils.extractSubTrack(
            track.points, userProjection,
            lookAheadM = _lookAheadOption.value.km * 1000.0,
            lookBackM  = 0.0
        )

        _allPois.value = allRepoPois.mapNotNull { poi ->
            if (subTrack.size < 2) return@mapNotNull null
            val (_, offRoute) = TrackGeometryUtils.projectPoiOntoTrack(poi.lat, poi.lon, subTrack)
            if (offRoute > CORRIDOR_M) return@mapNotNull null
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
    }
}
