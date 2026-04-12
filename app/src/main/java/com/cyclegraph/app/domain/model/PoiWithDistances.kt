package com.cyclegraph.app.domain.model

data class PoiWithDistances(
    val poi: Poi,
    val airDistanceM: Double?,    // null before GPS fix
    val trackDistanceM: Double?  // null in NEARBY mode; metres along full GPX track in ALONG_TRACK mode
)
