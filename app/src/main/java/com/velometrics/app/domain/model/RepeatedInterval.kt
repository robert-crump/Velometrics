package com.velometrics.app.domain.model

data class RepeatedInterval(
    val id: Long = 0,
    val name: String,
    val intervals: List<IntervalSession>,
    val edges: List<MapEdge>,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val distanceM: Double
)
