package com.velometrics.app.ui.shared

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.data.gpx.GpxParser
import com.velometrics.app.domain.model.GpxTrack
import com.velometrics.app.domain.model.PoiWithDistances
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.domain.service.TrackGeometryUtils
import com.velometrics.app.domain.service.TrackProjection
import com.velometrics.app.util.GeoUtils
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
    }

    private fun fetchPoisForTrack(track: GpxTrack) {
        viewModelScope.launch {
            if (track.points.size < 2) return@launch
            _isLoadingPois.value = true
            try {
                val allRepoPois = mapGraphRepository.getAllPois().first()
                val startPoint = track.points.first()
                val result = withContext(Dispatchers.Default) {
                    val startProjection = TrackProjection(0, 0.0, 0.0, startPoint)
                    allRepoPois.mapNotNull { poi ->
                        val (_, offRoute) = TrackGeometryUtils.projectPoiOntoTrack(poi.lat, poi.lon, track.points)
                        if (offRoute > CORRIDOR_M) return@mapNotNull null
                        val poiProjection = TrackGeometryUtils.projectPointOntoTrack(LatLng(poi.lat, poi.lon), track.points)
                        val trackDistM = TrackGeometryUtils.computeDistanceAlongTrack(track.points, startProjection, poiProjection)
                        val airDistM = GeoUtils.haversineDistance(startPoint.latitude, startPoint.longitude, poi.lat, poi.lon)
                        PoiWithDistances(poi, airDistM, trackDistM)
                    }.sortedBy { it.trackDistanceM ?: Double.MAX_VALUE }
                }
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
