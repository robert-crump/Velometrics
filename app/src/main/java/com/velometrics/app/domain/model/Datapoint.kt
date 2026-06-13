package com.velometrics.app.domain.model

import java.time.Instant

/**
 * Transient data structure used during .fit file import.
 * Not persisted to the database.
 */
data class Datapoint(
    val lat: Double,
    val lon: Double,
    val speedKmh: Double?,
    val power: Int?,
    val timestamp: Instant,
    val vectorX: Float? = null,
    val vectorY: Float? = null,
    val angleDeg: Double? = null,
    val heartRate: Int? = null,
    val altitude: Double? = null
)
