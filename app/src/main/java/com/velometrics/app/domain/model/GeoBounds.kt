package com.velometrics.app.domain.model

/** A lat/lon bounding box, free of any map-rendering library dependency. */
data class GeoBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    fun contains(lat: Double, lon: Double): Boolean =
        lat in south..north && lon in west..east
}
