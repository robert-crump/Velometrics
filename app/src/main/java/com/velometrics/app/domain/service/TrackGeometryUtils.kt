package com.velometrics.app.domain.service

import com.velometrics.app.util.GeoUtils
import org.maplibre.android.geometry.LatLng
import kotlin.math.max
import kotlin.math.min

data class TrackProjection(
    val segmentIndex: Int,
    val fraction: Double,
    val distanceFromTrackM: Double,
    val projectedPoint: LatLng
)

data class BoundingBox(
    val minLat: Double, val minLon: Double,
    val maxLat: Double, val maxLon: Double
)

object TrackGeometryUtils {

    fun projectPointOntoTrack(point: LatLng, track: List<LatLng>): TrackProjection {
        require(track.size >= 2) { "Track must have at least 2 points" }

        var bestDist = Double.MAX_VALUE
        var bestSegment = 0
        var bestFraction = 0.0
        var bestProjected = track[0]

        for (i in 0 until track.size - 1) {
            val a = track[i]
            val b = track[i + 1]
            val (fraction, projected) = projectPointOntoSegment(point, a, b)
            val dist = GeoUtils.haversineDistance(
                point.latitude, point.longitude,
                projected.latitude, projected.longitude
            )
            if (dist < bestDist) {
                bestDist = dist
                bestSegment = i
                bestFraction = fraction
                bestProjected = projected
            }
        }

        return TrackProjection(bestSegment, bestFraction, bestDist, bestProjected)
    }

    fun remainingTrackFrom(track: List<LatLng>, projection: TrackProjection): List<LatLng> {
        if (track.size < 2) return track

        val result = mutableListOf<LatLng>()
        result.add(projection.projectedPoint)
        for (i in (projection.segmentIndex + 1) until track.size) {
            result.add(track[i])
        }
        return result
    }

    fun computeDistanceAlongTrack(
        track: List<LatLng>,
        fromProjection: TrackProjection,
        toProjection: TrackProjection
    ): Double {
        if (track.size < 2) return 0.0

        val fromSeg = fromProjection.segmentIndex
        val toSeg = toProjection.segmentIndex

        if (fromSeg > toSeg || (fromSeg == toSeg && fromProjection.fraction > toProjection.fraction)) {
            return 0.0
        }

        var distance = 0.0

        if (fromSeg == toSeg) {
            val a = track[fromSeg]
            val b = track[fromSeg + 1]
            val segLen = GeoUtils.haversineDistance(a.latitude, a.longitude, b.latitude, b.longitude)
            distance = segLen * (toProjection.fraction - fromProjection.fraction)
        } else {
            // Remaining of fromSeg
            val a0 = track[fromSeg]
            val b0 = track[fromSeg + 1]
            val segLen0 = GeoUtils.haversineDistance(a0.latitude, a0.longitude, b0.latitude, b0.longitude)
            distance += segLen0 * (1.0 - fromProjection.fraction)

            // Full segments in between
            for (i in (fromSeg + 1) until toSeg) {
                val a = track[i]
                val b = track[i + 1]
                distance += GeoUtils.haversineDistance(a.latitude, a.longitude, b.latitude, b.longitude)
            }

            // Partial toSeg
            val aLast = track[toSeg]
            val bLast = track[toSeg + 1]
            val segLenLast = GeoUtils.haversineDistance(aLast.latitude, aLast.longitude, bLast.latitude, bLast.longitude)
            distance += segLenLast * toProjection.fraction
        }

        return distance
    }

    fun computeBoundingBox(points: List<LatLng>, bufferM: Double): BoundingBox {
        require(points.isNotEmpty()) { "Points list must not be empty" }

        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE

        for (p in points) {
            if (p.latitude < minLat) minLat = p.latitude
            if (p.latitude > maxLat) maxLat = p.latitude
            if (p.longitude < minLon) minLon = p.longitude
            if (p.longitude > maxLon) maxLon = p.longitude
        }

        val latBuffer = GeoUtils.metersToLat(bufferM)
        val midLat = (minLat + maxLat) / 2.0
        val lonBuffer = GeoUtils.metersToLon(bufferM, midLat)

        return BoundingBox(
            minLat = minLat - latBuffer,
            minLon = minLon - lonBuffer,
            maxLat = maxLat + latBuffer,
            maxLon = maxLon + lonBuffer
        )
    }

    fun projectPoiOntoTrack(
        poiLat: Double,
        poiLon: Double,
        remainingTrack: List<LatLng>
    ): Pair<Double, Double> {
        if (remainingTrack.size < 2) return Pair(0.0, Double.MAX_VALUE)

        val projection = projectPointOntoTrack(LatLng(poiLat, poiLon), remainingTrack)

        // Compute distance along remaining track from start to the projected position
        val startProjection = TrackProjection(0, 0.0, 0.0, remainingTrack[0])
        val distanceAlong = computeDistanceAlongTrack(remainingTrack, startProjection, projection)

        return Pair(distanceAlong, projection.distanceFromTrackM)
    }

    fun extractSubTrack(
        track: List<LatLng>,
        projectedPosition: TrackProjection,
        lookAheadM: Double,
        lookBackM: Double
    ): List<LatLng> {
        if (track.size < 2) return track

        val result = mutableListOf<LatLng>()
        result.add(projectedPosition.projectedPoint)

        // Look-back: go backward from projected position
        val lookBack = mutableListOf<LatLng>()
        var backDist = 0.0
        var prevPoint = projectedPosition.projectedPoint
        for (i in projectedPosition.segmentIndex downTo 0) {
            val point = track[i]
            val d = GeoUtils.haversineDistance(
                prevPoint.latitude, prevPoint.longitude,
                point.latitude, point.longitude
            )
            backDist += d
            if (backDist >= lookBackM) break
            lookBack.add(0, point)
            prevPoint = point
        }
        result.addAll(0, lookBack)

        // Look-ahead: go forward from projected position
        var forwardDist = 0.0
        prevPoint = projectedPosition.projectedPoint
        for (i in (projectedPosition.segmentIndex + 1) until track.size) {
            val point = track[i]
            val d = GeoUtils.haversineDistance(
                prevPoint.latitude, prevPoint.longitude,
                point.latitude, point.longitude
            )
            forwardDist += d
            result.add(point)
            if (forwardDist >= lookAheadM) break
            prevPoint = point
        }

        return result
    }

    private fun projectPointOntoSegment(
        point: LatLng,
        segStart: LatLng,
        segEnd: LatLng
    ): Pair<Double, LatLng> {
        val dx = segEnd.longitude - segStart.longitude
        val dy = segEnd.latitude - segStart.latitude
        val lenSq = dx * dx + dy * dy

        if (lenSq < 1e-15) {
            return Pair(0.0, segStart)
        }

        val t = ((point.longitude - segStart.longitude) * dx +
                (point.latitude - segStart.latitude) * dy) / lenSq
        val clampedT = max(0.0, min(1.0, t))

        val projLat = segStart.latitude + clampedT * dy
        val projLon = segStart.longitude + clampedT * dx

        return Pair(clampedT, LatLng(projLat, projLon))
    }
}
