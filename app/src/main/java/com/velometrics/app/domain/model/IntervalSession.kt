package com.velometrics.app.domain.model

import java.time.Instant

data class IntervalSession(
    val id: Long = 0,
    val cyclingSessionId: Long,
    val startTimestamp: Instant,
    val durationSec: Int,
    val durationNormalizedSec: Int,
    val distanceM: Double,
    val avgPower: Int,
    val avgSpeedKmh: Double,
    val avgSpeedNormalizedKmh: Double,
    val direction: String,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val gpsTrack: String
)
