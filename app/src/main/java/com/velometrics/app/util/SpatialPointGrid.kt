package com.velometrics.app.util

import kotlin.math.cos
import kotlin.math.floor

/**
 * Spatial hash grid answering "is there a point within [cellRadiusM] of (lat, lon)" against a
 * fixed point set. Bins points into cells sized to [cellRadiusM] and, on query, scans the 3×3
 * neighborhood of cells around the query point, checking true (haversine) distance against each
 * candidate.
 */
class SpatialPointGrid(points: List<List<Double>>, private val cellRadiusM: Double) {
    private val latCellSize: Double
    private val lonCellSize: Double
    private val cells = HashMap<Long, MutableList<List<Double>>>()

    init {
        val avgLat = if (points.isEmpty()) 0.0 else points.sumOf { it[0] } / points.size
        latCellSize = cellRadiusM / GeoUtils.METERS_PER_DEG_LAT
        lonCellSize = cellRadiusM / (GeoUtils.METERS_PER_DEG_LAT * cos(Math.toRadians(avgLat)))
        for (pt in points) {
            cells.getOrPut(cellKey(pt[0], pt[1])) { mutableListOf() }.add(pt)
        }
    }

    private fun cellKey(lat: Double, lon: Double): Long {
        val row = floor(lat / latCellSize).toLong()
        val col = floor(lon / lonCellSize).toLong()
        return row * 2_000_000L + col + 1_000_000L
    }

    fun hasPointWithin(lat: Double, lon: Double): Boolean {
        val row = floor(lat / latCellSize).toLong()
        val col = floor(lon / lonCellSize).toLong()
        for (dr in -1L..1L) {
            for (dc in -1L..1L) {
                val bucket = cells[(row + dr) * 2_000_000L + (col + dc) + 1_000_000L]
                    ?: continue
                for (pt in bucket) {
                    if (GeoUtils.haversineDistance(lat, lon, pt[0], pt[1]) <= cellRadiusM) return true
                }
            }
        }
        return false
    }
}
