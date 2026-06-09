package com.velometrics.app.ui.shared

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.data.gpx.GpxParser
import com.velometrics.app.domain.model.GpxPoiItem
import com.velometrics.app.domain.model.GpxTrack
import com.velometrics.app.domain.model.PoiWithDistances
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.domain.service.TrackGeometryUtils
import com.velometrics.app.domain.service.TrackProjection
import com.velometrics.app.util.GeoUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject
import kotlin.math.abs

@HiltViewModel
class GpxSharedViewModel @Inject constructor(
    private val mapGraphRepository: MapGraphRepository
) : ViewModel() {

    private val _gpxTrack = MutableStateFlow<GpxTrack?>(null)
    val gpxTrack: StateFlow<GpxTrack?> = _gpxTrack.asStateFlow()

    private val _gpxPois = MutableStateFlow<List<PoiWithDistances>>(emptyList())
    val gpxPois: StateFlow<List<PoiWithDistances>> = _gpxPois.asStateFlow()

    private val _isLoadingPois = MutableStateFlow(false)
    val isLoadingPois: StateFlow<Boolean> = _isLoadingPois.asStateFlow()

    private val _userLocation = MutableStateFlow<LatLng?>(null)
    val locationAvailable: StateFlow<Boolean> = _userLocation
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var cachedTrack: List<LatLng> = emptyList()
    private var poiPerpDistances: Map<String, Double> = emptyMap()

    val gpxPoiItems: StateFlow<List<GpxPoiItem>> = combine(_gpxPois, _userLocation) { pois, userLoc ->
        computePoiItems(pois, userLoc)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun updateUserLocation(location: LatLng?) {
        _userLocation.value = location
    }

    fun loadGpxFromUri(uri: Uri, contentResolver: ContentResolver) {
        viewModelScope.launch {
            _gpxPois.value = emptyList()
            val track = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { stream ->
                    GpxParser.parse(stream).getOrNull()
                }
            } ?: return@launch
            _gpxTrack.value = track
            fetchPoisForTrack(track)
        }
    }

    fun clearGpx() {
        _gpxTrack.value = null
        _gpxPois.value = emptyList()
        cachedTrack = emptyList()
        poiPerpDistances = emptyMap()
    }

    private fun computePoiItems(pois: List<PoiWithDistances>, userLoc: LatLng?): List<GpxPoiItem> {
        if (pois.isEmpty()) return emptyList()
        val track = cachedTrack
        if (track.size < 2) return pois.map { GpxPoiItem(it, it.trackDistanceM ?: 0.0, true) }

        val startProjection = TrackProjection(0, 0.0, 0.0, track.first())
        val userProjection = if (userLoc != null) {
            TrackGeometryUtils.projectPointOntoTrack(userLoc, track)
        } else {
            startProjection
        }
        val userTrackDistM = TrackGeometryUtils.computeDistanceAlongTrack(track, startProjection, userProjection)
        val perpDistUser = if (userLoc != null) userProjection.distanceFromTrackM else 0.0

        return pois.map { poiWD ->
            val poiTrackDistM = poiWD.trackDistanceM ?: 0.0
            val perpDistPoi = poiPerpDistances[poiWD.poi.poiId] ?: 0.0
            val alongTrack = abs(poiTrackDistM - userTrackDistM)
            val totalDist = perpDistUser + alongTrack + perpDistPoi
            val isAhead = poiTrackDistM >= userTrackDistM
            GpxPoiItem(poiWD, totalDist, isAhead)
        }
    }

    private fun fetchPoisForTrack(track: GpxTrack) {
        viewModelScope.launch {
            if (track.points.size < 2) return@launch
            _isLoadingPois.value = true
            cachedTrack = track.points
            try {
                val allRepoPois = mapGraphRepository.getAllPois().first()
                val startPoint = track.points.first()
                val perpDists = mutableMapOf<String, Double>()
                val result = withContext(Dispatchers.Default) {
                    val startProjection = TrackProjection(0, 0.0, 0.0, startPoint)
                    allRepoPois.mapNotNull { poi ->
                        val (_, offRoute) = TrackGeometryUtils.projectPoiOntoTrack(poi.lat, poi.lon, track.points)
                        if (offRoute > CORRIDOR_M) return@mapNotNull null
                        perpDists[poi.poiId] = offRoute
                        val poiProjection = TrackGeometryUtils.projectPointOntoTrack(LatLng(poi.lat, poi.lon), track.points)
                        val trackDistM = TrackGeometryUtils.computeDistanceAlongTrack(track.points, startProjection, poiProjection)
                        val airDistM = GeoUtils.haversineDistance(startPoint.latitude, startPoint.longitude, poi.lat, poi.lon)
                        PoiWithDistances(poi, airDistM, trackDistM)
                    }.sortedBy { it.trackDistanceM ?: Double.MAX_VALUE }
                }
                poiPerpDistances = perpDists
                _gpxPois.value = result
            } finally {
                _isLoadingPois.value = false
            }
        }
    }

    companion object {
        private const val CORRIDOR_M = 200.0
    }
}
