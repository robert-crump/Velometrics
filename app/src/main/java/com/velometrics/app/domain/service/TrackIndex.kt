package com.velometrics.app.domain.service

import com.velometrics.app.util.GeoUtils
import org.maplibre.android.geometry.LatLng
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Precomputed spatial grid + cumulative distances for a track, allowing
 * near-O(1) point projection and along-track distance lookups instead of
 * the O(N) scans in [TrackGeometryUtils].
 */
class TrackIndex private constructor(
    private val track: List<LatLng>,
    private val cumulativeDistM: DoubleArray,
    private val grid: Map<Pair<Int, Int>, List<Int>>,
    private val cellSizeLatDeg: Double,
    private val cellSizeLonDeg: Double
) {

    fun project(point: LatLng): TrackProjection {
        val cx = floor(point.latitude / cellSizeLatDeg).toInt()
        val cy = floor(point.longitude / cellSizeLonDeg).toInt()

        val candidates = mutableSetOf<Int>()
        for (dx in -1..1) {
            for (dy in -1..1) {
                grid[Pair(cx + dx, cy + dy)]?.let { candidates.addAll(it) }
            }
        }

        if (candidates.isEmpty()) {
            return TrackGeometryUtils.projectPointOntoTrack(point, track)
        }

        var bestDist = Double.MAX_VALUE
        var bestSegment = 0
        var bestFraction = 0.0
        var bestProjected = track[0]

        for (segmentIndex in candidates) {
            val a = track[segmentIndex]
            val b = track[segmentIndex + 1]
            val (fraction, projected) = TrackGeometryUtils.projectPointOntoSegment(point, a, b)
            val dist = GeoUtils.haversineDistance(
                point.latitude, point.longitude,
                projected.latitude, projected.longitude
            )
            if (dist < bestDist) {
                bestDist = dist
                bestSegment = segmentIndex
                bestFraction = fraction
                bestProjected = projected
            }
        }

        return TrackProjection(bestSegment, bestFraction, bestDist, bestProjected)
    }

    fun distanceAlongTrack(p: TrackProjection): Double {
        val a = track[p.segmentIndex]
        val b = track[p.segmentIndex + 1]
        val segLen = GeoUtils.haversineDistance(a.latitude, a.longitude, b.latitude, b.longitude)
        return cumulativeDistM[p.segmentIndex] + segLen * p.fraction
    }

    companion object {
        fun build(track: List<LatLng>, cellSizeM: Double = 200.0): TrackIndex {
            require(track.size >= 2) { "Track must have at least 2 points" }

            val cumulativeDistM = DoubleArray(track.size)
            for (i in 1 until track.size) {
                val a = track[i - 1]
                val b = track[i]
                cumulativeDistM[i] = cumulativeDistM[i - 1] +
                    GeoUtils.haversineDistance(a.latitude, a.longitude, b.latitude, b.longitude)
            }

            val cellSizeLatDeg = GeoUtils.metersToLat(cellSizeM)
            val cellSizeLonDeg = GeoUtils.metersToLon(cellSizeM, track[0].latitude)

            val grid = mutableMapOf<Pair<Int, Int>, MutableList<Int>>()
            for (i in 0 until track.size - 1) {
                val a = track[i]
                val b = track[i + 1]
                val minLat = min(a.latitude, b.latitude)
                val maxLat = max(a.latitude, b.latitude)
                val minLon = min(a.longitude, b.longitude)
                val maxLon = max(a.longitude, b.longitude)

                val minCx = floor(minLat / cellSizeLatDeg).toInt()
                val maxCx = floor(maxLat / cellSizeLatDeg).toInt()
                val minCy = floor(minLon / cellSizeLonDeg).toInt()
                val maxCy = floor(maxLon / cellSizeLonDeg).toInt()

                for (cx in minCx..maxCx) {
                    for (cy in minCy..maxCy) {
                        grid.getOrPut(Pair(cx, cy)) { mutableListOf() }.add(i)
                    }
                }
            }

            return TrackIndex(track, cumulativeDistM, grid, cellSizeLatDeg, cellSizeLonDeg)
        }
    }
}
