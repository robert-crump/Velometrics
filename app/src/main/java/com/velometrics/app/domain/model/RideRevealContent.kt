package com.velometrics.app.domain.model

/** Resolved content for the Ride Reveal hero sheet, shown once at the end of an import batch. */
data class RideRevealContent(
    val sessionId: Long,
    val headline: String,
    val distanceKm: Double,
    val netDurationSec: Int,
    val elevationGainM: Double?
)
