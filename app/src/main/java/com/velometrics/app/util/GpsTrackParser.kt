package com.velometrics.app.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

object GpsTrackParser {

    private val gson = Gson()

    fun parse(gpsTrackJson: String?): List<LatLng> {
        if (gpsTrackJson.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<List<Double>>>() {}.type
            val raw: List<List<Double>> = gson.fromJson(gpsTrackJson, type)
            raw.mapNotNull { coords ->
                if (coords.size >= 2) LatLng(coords[0], coords[1]) else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun computeBounds(points: List<LatLng>): LatLngBounds? {
        if (points.size < 2) return null
        val builder = LatLngBounds.Builder()
        points.forEach { builder.include(it) }
        return builder.build()
    }
}
