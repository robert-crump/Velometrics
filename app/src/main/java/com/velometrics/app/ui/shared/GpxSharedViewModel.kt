package com.velometrics.app.ui.shared

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.data.gpx.GpxParser
import com.velometrics.app.domain.model.GpxPoiItem
import com.velometrics.app.domain.model.GpxTrack
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.PoiWithDistances
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.domain.service.MapMatcher
import com.velometrics.app.domain.service.TrackGeometryUtils
import com.velometrics.app.domain.service.TrackIndex
import com.velometrics.app.domain.service.TrackProjection
import com.velometrics.app.domain.service.RTreeSpatialIndex
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.GeoUtils
import com.velometrics.app.util.GpxAnalysisUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject
import kotlin.math.abs

@HiltViewModel
class GpxSharedViewModel @Inject constructor(
    private val mapGraphRepository: MapGraphRepository,
    private val mapMatcher: MapMatcher
) : ViewModel() {

    private val _gpxTrack = MutableStateFlow<GpxTrack?>(null)
    val gpxTrack: StateFlow<GpxTrack?> = _gpxTrack.asStateFlow()

    private val _gpxFileName = MutableStateFlow<String?>(null)
    val gpxFileName: StateFlow<String?> = _gpxFileName.asStateFlow()

    private val _showGpxPoisOverlay = MutableStateFlow(false)
    val showGpxPoisOverlay: StateFlow<Boolean> = _showGpxPoisOverlay.asStateFlow()

    private val _gpxPois = MutableStateFlow<List<PoiWithDistances>>(emptyList())
    val gpxPois: StateFlow<List<PoiWithDistances>> = _gpxPois.asStateFlow()

    private val _isLoadingPois = MutableStateFlow(false)
    val isLoadingPois: StateFlow<Boolean> = _isLoadingPois.asStateFlow()

    private val _discoveryScore = MutableStateFlow<DiscoveryScoreResult?>(null)
    val discoveryScore: StateFlow<DiscoveryScoreResult?> = _discoveryScore.asStateFlow()

    private val _speedPowerEstimate = MutableStateFlow<SpeedPowerEstimateResult?>(null)
    val speedPowerEstimate: StateFlow<SpeedPowerEstimateResult?> = _speedPowerEstimate.asStateFlow()

    private val _userLocation = MutableStateFlow<LatLng?>(null)
    val locationAvailable: StateFlow<Boolean> = _userLocation
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _selectedPoiItem = MutableStateFlow<GpxPoiItem?>(null)
    val selectedPoiItem: StateFlow<GpxPoiItem?> = _selectedPoiItem.asStateFlow()

    private var cachedTrack: List<LatLng> = emptyList()
    private var cachedTrackIndex: TrackIndex? = null
    private var poiPerpDistances: Map<String, Double> = emptyMap()

    val gpxPoiItems: StateFlow<List<GpxPoiItem>> = combine(_gpxPois, _userLocation) { pois, userLoc ->
        computePoiItems(pois, userLoc)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val gpxSegmentPoints: StateFlow<List<LatLng>> = combine(_selectedPoiItem, _userLocation) { item, userLoc ->
        computeSegmentPoints(item, userLoc)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun updateUserLocation(location: LatLng?) {
        _userLocation.value = location
    }

    fun selectPoi(item: GpxPoiItem?) {
        _selectedPoiItem.value = item
    }

    fun setGpxPoisOverlayVisible(visible: Boolean) {
        _showGpxPoisOverlay.value = visible
    }

    suspend fun loadGpxFromUri(uri: Uri, contentResolver: ContentResolver): Boolean {
        _gpxPois.value = emptyList()
        _discoveryScore.value = null
        _speedPowerEstimate.value = null
        val track = withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { stream ->
                GpxParser.parse(stream).getOrNull()
            }
        } ?: return false
        _gpxTrack.value = track
        _gpxFileName.value = withContext(Dispatchers.IO) { queryDisplayName(uri, contentResolver) }
        fetchPoisForTrack(track)
        fetchRouteAnalysis(track)
        return true
    }

    private fun queryDisplayName(uri: Uri, contentResolver: ContentResolver): String? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) it.getString(nameIndex) else null
            } else null
        }
    }

    fun clearGpx() {
        _gpxTrack.value = null
        _gpxFileName.value = null
        _showGpxPoisOverlay.value = false
        _gpxPois.value = emptyList()
        _selectedPoiItem.value = null
        _discoveryScore.value = null
        _speedPowerEstimate.value = null
        cachedTrack = emptyList()
        cachedTrackIndex = null
        poiPerpDistances = emptyMap()
    }

    private fun computePoiItems(pois: List<PoiWithDistances>, userLoc: LatLng?): List<GpxPoiItem> {
        if (pois.isEmpty()) return emptyList()
        val track = cachedTrack
        if (track.size < 2) return pois.map { GpxPoiItem(it, it.trackDistanceM ?: 0.0, true) }

        val trackIndex = cachedTrackIndex
        val startProjection = TrackProjection(0, 0.0, 0.0, track.first())
        val userProjection = if (userLoc != null) {
            trackIndex?.project(userLoc) ?: TrackGeometryUtils.projectPointOntoTrack(userLoc, track)
        } else {
            startProjection
        }
        val userTrackDistM = if (userLoc != null) {
            trackIndex?.distanceAlongTrack(userProjection)
                ?: TrackGeometryUtils.computeDistanceAlongTrack(track, startProjection, userProjection)
        } else {
            0.0
        }
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
            val trackIndex = TrackIndex.build(track.points)
            cachedTrackIndex = trackIndex
            try {
                val bbox = TrackGeometryUtils.computeBoundingBox(track.points, CORRIDOR_M)
                val candidatePois = mapGraphRepository.getPoisInBoundingBox(bbox.minLat, bbox.maxLat, bbox.minLon, bbox.maxLon)
                val startPoint = track.points.first()
                val perpDists = mutableMapOf<String, Double>()
                val result = withContext(Dispatchers.Default) {
                    candidatePois.mapNotNull { poi ->
                        val projection = trackIndex.project(LatLng(poi.lat, poi.lon))
                        val offRoute = projection.distanceFromTrackM
                        if (offRoute > CORRIDOR_M) return@mapNotNull null
                        perpDists[poi.poiId] = offRoute
                        val trackDistM = trackIndex.distanceAlongTrack(projection)
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

    /**
     * Matches the track against the road graph in chunks (#15 follow-up) and derives both the
     * discovery score and speed/power estimate from the shared result, so a route that's only
     * partially within the graph's coverage area still yields results for its covered portion.
     * Calls [GpxAnalysisUtils.findSurrogates] once after matching and passes the result to both
     * scoring functions.
     */
    private fun fetchRouteAnalysis(track: GpxTrack) {
        viewModelScope.launch {
            if (track.points.size < 2) {
                _discoveryScore.value = DiscoveryScoreResult.Unavailable
                _speedPowerEstimate.value = SpeedPowerEstimateResult.Unavailable
                return@launch
            }
            val gpsTrack = track.points.map { listOf(it.latitude, it.longitude) }
            val match = mapMatcher.matchTrackChunked(gpsTrack)
            val routeCoverage = RouteCoverage(match.matchedDistanceM, match.totalDistanceM, match.coveragePercent)

            val surrogates = if (match.matchedEdges.isNotEmpty()) {
                findSurrogatesForMatchedEdges(track, match.matchedEdges)
            } else {
                emptyMap()
            }

            _discoveryScore.value = if (match.matchedEdges.isEmpty()) {
                DiscoveryScoreResult.OutsideCoverage
            } else {
                GpxAnalysisUtils.discoveryScore(match.matchedEdges, surrogates)
                    ?.let { DiscoveryScoreResult.Score(it, routeCoverage) }
                    ?: DiscoveryScoreResult.Unavailable
            }

            _speedPowerEstimate.value = if (match.matchedEdges.isEmpty()) {
                SpeedPowerEstimateResult.OutsideCoverage
            } else {
                GpxAnalysisUtils.speedPowerEstimate(match.matchedEdges, match.totalDistanceM, surrogates)
                    ?.let {
                        if (it.coveragePercent <= 0 && it.totalCoveragePercent <= 0) {
                            SpeedPowerEstimateResult.NoRideHistory
                        } else {
                            SpeedPowerEstimateResult.Estimate(
                                it.avgSpeedKmh, it.avgPowerW,
                                it.coveragePercent, it.totalCoveragePercent
                            )
                        }
                    }
                    ?: SpeedPowerEstimateResult.Unavailable
            }
        }
    }

    private suspend fun findSurrogatesForMatchedEdges(
        track: GpxTrack,
        matchedEdges: List<MapEdge>
    ): Map<Pair<Long, Long>, MapEdge> = withContext(Dispatchers.Default) {
        val hasUncovered = matchedEdges.any {
            !it.isTraversed || it.speedMean == null || it.powerMean == null
        }
        if (!hasUncovered) return@withContext emptyMap()

        val bbox = TrackGeometryUtils.computeBoundingBox(
            track.points, CyclingConstants.GPX_SURROGATE_MAX_DISTANCE_M
        )
        val allEdges = mapGraphRepository.getEdgesNear(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon)
        if (allEdges.size > MAX_SURROGATE_EDGES) return@withContext emptyMap()

        val allNodes = mapGraphRepository.getNodesNear(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon)
            .associateBy { it.id }

        val edgeIndexLookup = HashMap<Pair<Long, Long>, Int>(allEdges.size)
        for (i in allEdges.indices) {
            edgeIndexLookup[allEdges[i].fromNode to allEdges[i].toNode] = i
        }

        val uncoveredByIndex = mutableMapOf<Long, MapEdge>()
        for (edge in matchedEdges) {
            if (!edge.isTraversed || edge.speedMean == null || edge.powerMean == null) {
                val idx = edgeIndexLookup[edge.fromNode to edge.toNode] ?: continue
                uncoveredByIndex[idx.toLong()] = allEdges[idx]
            }
        }
        if (uncoveredByIndex.isEmpty()) return@withContext emptyMap()

        val spatialIndex = RTreeSpatialIndex()
        spatialIndex.rebuildIndex(allEdges, allNodes)

        val surrogatesByIndex = GpxAnalysisUtils.findSurrogates(uncoveredByIndex, allEdges, spatialIndex)

        val result = HashMap<Pair<Long, Long>, MapEdge>(surrogatesByIndex.size)
        for ((idx, surrogateEdge) in surrogatesByIndex) {
            val uncoveredEdge = allEdges[idx.toInt()]
            result[uncoveredEdge.fromNode to uncoveredEdge.toNode] = surrogateEdge
        }
        result
    }

    private fun computeSegmentPoints(item: GpxPoiItem?, userLoc: LatLng?): List<LatLng> {
        if (item == null) return emptyList()
        val track = cachedTrack
        if (track.size < 2) return emptyList()
        val refPoint = userLoc ?: track.first()
        val userIdx = nearestPointIndex(track, refPoint)
        val poiIdx = nearestPointIndex(track, LatLng(item.poiWD.poi.lat, item.poiWD.poi.lon))
        val from = minOf(userIdx, poiIdx)
        val to = maxOf(userIdx, poiIdx)
        return if (from == to) listOf(track[from]) else track.subList(from, to + 1)
    }

    // segmentIndex is the segment's start index (0..track.size-2), so the track's final
    // point can never be returned here - an acceptable approximation for this overlay.
    private fun nearestPointIndex(track: List<LatLng>, point: LatLng): Int =
        cachedTrackIndex?.project(point)?.segmentIndex ?: track.indices.minByOrNull {
            GeoUtils.haversineDistance(
                track[it].latitude, track[it].longitude,
                point.latitude, point.longitude
            )
        } ?: 0

    companion object {
        private const val CORRIDOR_M = 200.0
        private const val MAX_SURROGATE_EDGES = 100_000
    }
}

/**
 * How much of an imported .gpx route could be matched to the road graph, used to caveat
 * [DiscoveryScoreResult.Score] when part of the route is outside the graph's coverage area.
 */
data class RouteCoverage(val matchedDistanceM: Double, val totalDistanceM: Double, val percent: Int) {
    val isFull: Boolean get() = percent >= 100
}

/** Result of matching the loaded .gpx track to the road graph and scoring it. */
sealed interface DiscoveryScoreResult {
    data class Score(val value: Int, val routeCoverage: RouteCoverage) : DiscoveryScoreResult
    object Unavailable : DiscoveryScoreResult

    /** None of the route could be matched to the road graph (it's entirely outside the graph's coverage area). */
    object OutsideCoverage : DiscoveryScoreResult
}

/** Result of matching the loaded .gpx track to the road graph and estimating speed/power from ride history. */
sealed interface SpeedPowerEstimateResult {
    data class Estimate(
        val avgSpeedKmh: Int,
        val avgPowerW: Int,
        val coveragePercent: Int,
        val totalCoveragePercent: Int = coveragePercent
    ) : SpeedPowerEstimateResult
    object NoRideHistory : SpeedPowerEstimateResult
    object Unavailable : SpeedPowerEstimateResult

    /** None of the route could be matched to the road graph (it's entirely outside the graph's coverage area). */
    object OutsideCoverage : SpeedPowerEstimateResult
}
