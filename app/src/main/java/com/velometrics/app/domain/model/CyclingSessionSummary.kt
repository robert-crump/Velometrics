package com.velometrics.app.domain.model

import java.time.Instant

data class CyclingSessionSummary(
    val id: Long,
    val sessionStart: Instant,
    val distanceKm: Double,
    val netDurationSec: Int,
    val averagePower: Int?,
    val hasPower: Boolean
)
