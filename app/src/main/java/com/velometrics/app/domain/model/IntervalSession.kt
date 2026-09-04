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
    val gpsTrack: String,
    // Import-time HR recovery metrics (#178), computed by IntervalDetector by reading forward up
    // to 60s past this interval's end (bounded by session end). Null when HR data is missing or
    // insufficient for the recovery window -- independent of the session-level hasHR flag.
    val hrr60: Int? = null,
    val hrr30: Int? = null,
    val avgPower60sAfter: Int? = null,
    // Actual seconds of rest before the next detected interval starts in this session; null if
    // this is the last interval. hrr60/hrr30/avgPower60sAfter are always computed over their full
    // fixed window regardless of this value -- this is how a truncated (contaminated) recovery
    // reading gets flagged downstream, not by nulling the metric.
    val restBeforeNextIntervalSec: Int? = null
)
