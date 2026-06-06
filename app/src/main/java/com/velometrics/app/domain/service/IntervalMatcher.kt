package com.velometrics.app.domain.service

import com.velometrics.app.data.local.dao.IntervalPrototypeRouteDao
import com.velometrics.app.domain.model.IntervalPrototypeRoute
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.util.GeoUtils
import com.velometrics.app.util.toDomain
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntervalMatcher @Inject constructor(
    private val prototypeRouteDao: IntervalPrototypeRouteDao
) {

    companion object {
        private const val TAG = "IntervalMatcher"
        private const val MATCH_RADIUS_M = 50.0
    }

    private val gson = Gson()

    suspend fun matchToPrototypes(intervals: List<IntervalSession>): List<IntervalSession> {
        if (intervals.isEmpty()) return intervals

        val prototypes = prototypeRouteDao.getAll().first().map { it.toDomain() }
        return matchIntervals(intervals, prototypes)
    }

    fun matchIntervals(
        intervals: List<IntervalSession>,
        prototypes: List<IntervalPrototypeRoute>
    ): List<IntervalSession> {
        if (prototypes.isEmpty()) return intervals

        return intervals.map { interval ->
            val matchedId = findBestPrototype(interval, prototypes)
            if (matchedId != null) interval.copy(prototypeRouteId = matchedId) else interval
        }
    }

    private fun findBestPrototype(
        interval: IntervalSession,
        prototypes: List<IntervalPrototypeRoute>
    ): Long? {
        val trackPoints = parseGpsTrack(interval.gpsTrack)
        if (trackPoints.isEmpty()) return null

        var bestProtoId: Long? = null
        var bestScore = -1

        for (proto in prototypes) {
            // a. Check if interval start is within 50 m of prototype start
            val startDist = GeoUtils.haversineDistance(
                interval.startLat, interval.startLon,
                proto.startLat, proto.startLon
            )
            if (startDist > MATCH_RADIUS_M) continue

            // b. Walk through GPS track points, find if any comes within 50 m of prototype end
            var score = 0
            var matched = false
            for (point in trackPoints) {
                score++
                val endDist = GeoUtils.haversineDistance(
                    point[0], point[1],
                    proto.endLat, proto.endLon
                )
                if (endDist <= MATCH_RADIUS_M) {
                    matched = true
                    break
                }
            }

            // c/d. Among candidates, pick the one with highest score
            if (matched && score > bestScore) {
                bestScore = score
                bestProtoId = proto.id
            }
        }

        return bestProtoId
    }

    private fun parseGpsTrack(gpsTrackJson: String): List<List<Double>> {
        return try {
            val type = object : TypeToken<List<List<Double>>>() {}.type
            gson.fromJson(gpsTrackJson, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GPS track JSON", e)
            emptyList()
        }
    }
}
