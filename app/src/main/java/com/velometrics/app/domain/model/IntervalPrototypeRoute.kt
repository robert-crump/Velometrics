package com.velometrics.app.domain.model

data class IntervalPrototypeRoute(
    val id: Long = 0,
    val name: String,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val distanceM: Double?,
    val avgGpsTrack: String?
)
