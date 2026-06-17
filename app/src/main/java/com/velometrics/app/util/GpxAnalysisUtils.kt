package com.velometrics.app.util

import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.PoiWithDistances
import org.maplibre.android.geometry.LatLng
import kotlin.math.roundToInt

object GpxAnalysisUtils {

    const val POI_DENSITY_BUCKET_SIZE_M = 5_000.0
    const val POI_DENSITY_TARGET_BUCKET_COUNT = 10
    // "Nice" bucket sizes for longer routes, smallest first. The smallest size whose bucket count
    // is within POI_DENSITY_TARGET_BUCKET_COUNT is used, so a 100km route gets ~10km bins instead
    // of 20 cramped 5km bins.
    val POI_DENSITY_NICE_SIZES_M = listOf(5_000.0, 10_000.0, 20_000.0, 25_000.0, 50_000.0, 100_000.0)
    const val ELEVATION_SMOOTHING_WINDOW = 5
    // "Nice" elevation axis steps, smallest first. The smallest step that keeps the tick count
    // (range / step + 1) at or below ELEVATION_AXIS_MAX_TICKS is used.
    val ELEVATION_AXIS_STEPS_M = listOf(50.0, 100.0, 200.0)
    const val ELEVATION_AXIS_MAX_TICKS = 6
    const val MAX_FILE_NAME_LENGTH = 25

    /** Sums haversine distances between consecutive track points. */
    fun totalTrackDistanceM(points: List<LatLng>): Double =
        points.zipWithNext().sumOf { (a, b) ->
            GeoUtils.haversineDistance(a.latitude, a.longitude, b.latitude, b.longitude)
        }

    /**
     * Picks a bucket size for [poiCountsPerBucket] from [POI_DENSITY_NICE_SIZES_M]: the smallest
     * size that keeps the bucket count within [POI_DENSITY_TARGET_BUCKET_COUNT], so long routes
     * get coarser bins instead of dozens of 5km bins.
     */
    fun poiDensityBucketSizeM(totalDistanceM: Double): Double =
        POI_DENSITY_NICE_SIZES_M.firstOrNull { kotlin.math.ceil(totalDistanceM / it) <= POI_DENSITY_TARGET_BUCKET_COUNT }
            ?: POI_DENSITY_NICE_SIZES_M.last()

    /**
     * Buckets [pois] into [bucketSizeM] segments along the track based on `trackDistanceM`,
     * returning the POI count per bucket across the track's full [totalDistanceM]. Tracks shorter
     * than [bucketSizeM] produce a single bucket. POIs with a null `trackDistanceM` are ignored.
     */
    fun poiCountsPerBucket(pois: List<PoiWithDistances>, totalDistanceM: Double, bucketSizeM: Double): List<Int> {
        val bucketCount = if (totalDistanceM <= bucketSizeM) {
            1
        } else {
            kotlin.math.ceil(totalDistanceM / bucketSizeM).toInt()
        }
        val counts = MutableList(bucketCount) { 0 }
        for (poi in pois) {
            val distance = poi.trackDistanceM ?: continue
            val bucket = (distance / bucketSizeM).toInt().coerceIn(0, bucketCount - 1)
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

    /**
     * Picks an elevation axis step from [ELEVATION_AXIS_STEPS_M]: the smallest step that keeps
     * the tick count (the range from [minEle] to [maxEle], rounded out to multiples of the step,
     * divided by the step, plus one) at or below [ELEVATION_AXIS_MAX_TICKS].
     */
    fun elevationAxisStep(minEle: Double, maxEle: Double): Double {
        for (step in ELEVATION_AXIS_STEPS_M) {
            val axisMin = kotlin.math.floor(minEle / step) * step
            val rawMax = kotlin.math.ceil(maxEle / step) * step
            val axisMax = if (rawMax <= axisMin) axisMin + step else rawMax
            val tickCount = ((axisMax - axisMin) / step).roundToInt() + 1
            if (tickCount <= ELEVATION_AXIS_MAX_TICKS) return step
        }
        return ELEVATION_AXIS_STEPS_M.last()
    }

    /**
     * Shortens [name] to at most [maxLength] characters for display. If [name] already fits, it's
     * returned unchanged (extension included). Otherwise the extension is dropped first; if that
     * still doesn't fit, the result is cut and suffixed with "…".
     */
    fun truncateFileName(name: String, maxLength: Int = MAX_FILE_NAME_LENGTH): String {
        if (name.length <= maxLength) return name
        val withoutExtension = name.substringBeforeLast('.', name)
        if (withoutExtension.length <= maxLength) return withoutExtension
        return withoutExtension.take(maxLength - 1) + "…"
    }

    /**
     * Distance-weighted average speed/power and ride-history coverage over [matchedEdges].
     * Coverage is the percentage of [routeTotalDistanceM] (the full .gpx route's length) covered
     * by edges with ride-history metadata (`isTraversed == true` and non-null
     * `speedMean`/`powerMean`); the averages are weighted by `lengthM` over just those edges.
     * Returns null if [matchedEdges] is empty or has zero total length (nothing to score).
     */
    fun speedPowerEstimate(matchedEdges: List<MapEdge>, routeTotalDistanceM: Double): SpeedPowerEstimate? {
        val totalLengthM = matchedEdges.sumOf { it.lengthM }
        if (totalLengthM <= 0.0) return null

        val rideHistoryEdges = matchedEdges.filter {
            it.isTraversed && it.speedMean != null && it.powerMean != null
        }
        val coveredLengthM = rideHistoryEdges.sumOf { it.lengthM }
        val coveragePercent = if (routeTotalDistanceM > 0.0) {
            (100 * coveredLengthM / routeTotalDistanceM).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
        if (coveredLengthM <= 0.0) return SpeedPowerEstimate(avgSpeedKmh = 0, avgPowerW = 0, coveragePercent = 0)

        val avgSpeedKmh = (rideHistoryEdges.sumOf { it.speedMean!! * it.lengthM } / coveredLengthM).roundToInt()
        val avgPowerW = (rideHistoryEdges.sumOf { it.powerMean!! * it.lengthM } / coveredLengthM).roundToInt()
        return SpeedPowerEstimate(avgSpeedKmh, avgPowerW, coveragePercent)
    }
}

/** Distance-weighted average speed (km/h) and power (W) over a route's ride-history coverage. */
data class SpeedPowerEstimate(val avgSpeedKmh: Int, val avgPowerW: Int, val coveragePercent: Int)
