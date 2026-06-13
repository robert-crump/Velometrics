package com.velometrics.app.util

import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.PoiWithDistances
import org.maplibre.android.geometry.LatLng
import kotlin.math.roundToInt

object GpxAnalysisUtils {

    const val POI_DENSITY_BUCKET_SIZE_M = 5_000.0

    /** Sums haversine distances between consecutive track points. */
    fun totalTrackDistanceM(points: List<LatLng>): Double =
        points.zipWithNext().sumOf { (a, b) ->
            GeoUtils.haversineDistance(a.latitude, a.longitude, b.latitude, b.longitude)
        }

    /**
     * Buckets [pois] into 5km segments along the track based on `trackDistanceM`, returning the
     * POI count per bucket across the track's full [totalDistanceM]. Tracks shorter than 5km
     * produce a single bucket. POIs with a null `trackDistanceM` are ignored.
     */
    fun poiCountsPer5kmBucket(pois: List<PoiWithDistances>, totalDistanceM: Double): List<Int> {
        val bucketCount = if (totalDistanceM <= POI_DENSITY_BUCKET_SIZE_M) {
            1
        } else {
            kotlin.math.ceil(totalDistanceM / POI_DENSITY_BUCKET_SIZE_M).toInt()
        }
        val counts = MutableList(bucketCount) { 0 }
        for (poi in pois) {
            val distance = poi.trackDistanceM ?: continue
            val bucket = (distance / POI_DENSITY_BUCKET_SIZE_M).toInt().coerceIn(0, bucketCount - 1)
            counts[bucket]++
        }
        return counts
    }

    /**
     * Discovery score (0-100): the percentage of [matchedEdges]' total length where
     * `isTraversed == false`, i.e. roads not yet ridden according to the graph metadata. Returns
     * null if [matchedEdges] is empty or has zero total length (nothing to score).
     */
    fun discoveryScore(matchedEdges: List<MapEdge>): Int? {
        val totalLengthM = matchedEdges.sumOf { it.lengthM }
        if (totalLengthM <= 0.0) return null
        val untraversedLengthM = matchedEdges.filterNot { it.isTraversed }.sumOf { it.lengthM }
        return (100 * untraversedLengthM / totalLengthM).roundToInt().coerceIn(0, 100)
    }
}
