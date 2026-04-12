package com.cyclegraph.app.util

import org.maplibre.android.geometry.LatLng

object PolylineDecoder {

    fun decode(encoded: String): List<LatLng> {
        val result = mutableListOf<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
            var shift = 0
            var b: Int
            var dlat = 0
            do {
                b = encoded[index++].code - 63
                dlat = dlat or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (dlat and 1 != 0) (dlat shr 1).inv() else dlat shr 1

            shift = 0
            var dlng = 0
            do {
                b = encoded[index++].code - 63
                dlng = dlng or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (dlng and 1 != 0) (dlng shr 1).inv() else dlng shr 1

            result.add(LatLng(lat / 1e5, lng / 1e5))
        }

        return result
    }
}
