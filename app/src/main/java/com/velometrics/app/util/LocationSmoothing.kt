package com.velometrics.app.util

import com.velometrics.app.domain.model.GeoPoint
import kotlin.math.pow

/** One raw fix retained for the accuracy-weighted smoothing window. */
data class LocationSample(val lat: Double, val lon: Double, val accuracyM: Float)

/**
 * Smooths the on-screen user-location marker across recent fixes. Rendering only — POI-distance
 * and Fast-Way-Home calculations use the unsmoothed current location, not this average.
 */
object LocationSmoothing {

    const val WINDOW_SIZE = 5

    /**
     * Accuracy-weighted moving average of [samples]. Weight is 1/accuracy², so tighter fixes
     * pull the average toward themselves. Returns null for an empty window.
     */
    fun weightedAverage(samples: List<LocationSample>): GeoPoint? {
        if (samples.isEmpty()) return null

        var weightSum = 0.0
        var latSum = 0.0
        var lonSum = 0.0
        samples.forEach { sample ->
            val weight = 1.0 / sample.accuracyM.toDouble().coerceAtLeast(1.0).pow(2)
            weightSum += weight
            latSum += sample.lat * weight
            lonSum += sample.lon * weight
        }

        return GeoPoint(latSum / weightSum, lonSum / weightSum)
    }
}
