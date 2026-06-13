package com.velometrics.app.util

import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.PoiWithDistances
import org.maplibre.android.geometry.LatLng
import kotlin.math.roundToInt

object GpxAnalysisUtils {

    const val POI_DENSITY_BUCKET_SIZE_M = 5_000.0
    const val ELEVATION_SMOOTHING_WINDOW = 5

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

    /**
     * Pairs each point with non-null elevation to its cumulative distance (m) along [points],
     * for plotting an elevation profile. Points with null elevation are skipped.
     */
    fun elevationProfile(points: List<LatLng>, elevations: List<Double?>): List<Pair<Double, Double>> {
        val profile = mutableListOf<Pair<Double, Double>>()
        var cumulativeM = 0.0
        for (i in points.indices) {
            if (i > 0) {
                cumulativeM += GeoUtils.haversineDistance(
                    points[i - 1].latitude, points[i - 1].longitude,
                    points[i].latitude, points[i].longitude
                )
            }
            val elevation = elevations.getOrNull(i) ?: continue
            profile.add(cumulativeM to elevation)
        }
        return profile
    }

    /**
     * Smooths a raw elevation series with a centered moving average of [windowSize] points, to
     * avoid inflated gain/loss totals from GPS altitude jitter.
     */
    fun smoothElevations(elevations: List<Double>, windowSize: Int = ELEVATION_SMOOTHING_WINDOW): List<Double> {
        if (elevations.isEmpty()) return elevations
        val halfWindow = windowSize / 2
        return elevations.indices.map { i ->
            val from = (i - halfWindow).coerceAtLeast(0)
            val to = (i + halfWindow).coerceAtMost(elevations.lastIndex)
            elevations.subList(from, to + 1).average()
        }
    }

    /** Total elevation gain and loss (m), as non-negative integers, from a smoothed elevation series. */
    fun elevationGainLoss(smoothed: List<Double>): Pair<Int, Int> {
        var gainM = 0.0
        var lossM = 0.0
        smoothed.zipWithNext().forEach { (prev, next) ->
            val delta = next - prev
            if (delta > 0) gainM += delta else lossM -= delta
        }
        return gainM.roundToInt() to lossM.roundToInt()
    }

    /** Elevation gain normalized to a 100km distance, as an integer. Returns 0 if [totalDistanceM] is 0. */
    fun elevationGainPer100km(gainM: Int, totalDistanceM: Double): Int {
        if (totalDistanceM <= 0.0) return 0
        return (gainM * 100_000.0 / totalDistanceM).roundToInt()
    }
}
